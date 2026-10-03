package com.v2ray.ang.core

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.colitu.app.ColituQuickStart
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.receiver.WidgetProvider
import com.v2ray.ang.service.CoreVpnService
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.ProcessFinder
import java.lang.ref.SoftReference
import java.net.InetSocketAddress

/**
 * Runs the Xray core inside the VPN process and talks to the Colitu UI
 * through the app's private broadcasts ([MessageUtil]).
 */
object CoreServiceManager {

    private val coreController: CoreController = CoreNativeManager.newCoreController(CoreCallback())
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null
    private var processFinder: XrayProcessFinder? = null
    private var colituTrafficJob: Job? = null
    private val colituStore by lazy { MMKV.mmkvWithID("COLITU_SETTINGS", MMKV.MULTI_PROCESS_MODE) }

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            val service = value?.get()?.getService()
            CoreNativeManager.initCoreEnv(service)
            if (service != null && processFinder == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                processFinder = XrayProcessFinder(service)
                coreController.registerProcessFinder(processFinder)
            }
        }

    /**
     * Starts the VPN service with [guid] (the Colitu profile the caller just
     * imported or [ColituQuickStart] allowed).
     */
    fun startVService(context: Context, guid: String? = null) {
        if (guid != null) MmkvManager.setSelectServer(guid)
        try {
            startContextService(context)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: ${e.message}", e)
            MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_START_FAILURE, e.message ?: e.javaClass.simpleName)
        }
    }

    fun stopVService(context: Context) {
        MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
    }

    /** True in the VPN process while the core runs; always false elsewhere. */
    fun isRunning() = coreController.isRunning

    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    @Throws(Exception::class)
    private fun startContextService(context: Context) {
        if (coreController.isRunning) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return
        }
        val guid = MmkvManager.getSelectServer() ?: error("NO_PROFILE")
        MmkvManager.decodeServerConfig(guid) ?: error("CONFIG_NOT_READY")
        ContextCompat.startForegroundService(context, Intent(context.applicationContext, CoreVpnService::class.java))
    }

    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        if (coreController.isRunning) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return false
        }
        val service = getService() ?: run {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Service is null")
            return false
        }
        return try {
            doStartCoreLoop(service, vpnInterface)
            true
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: $message", e)
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            NotificationManager.cancelNotification()
            false
        }
    }

    @Throws(Exception::class)
    private fun doStartCoreLoop(service: Service, vpnInterface: ParcelFileDescriptor?) {
        val guid = MmkvManager.getSelectServer() ?: error("NO_PROFILE")
        val config = MmkvManager.decodeServerConfig(guid) ?: error("CONFIG_NOT_READY")

        val result = CoreConfigManager.getV2rayConfig(service, guid)
        if (!result.status) error(result.errorMessage.ifBlank { "ENGINE_FAILED" })

        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
        mFilter.addAction(Intent.ACTION_SCREEN_ON)
        mFilter.addAction(Intent.ACTION_SCREEN_OFF)
        ContextCompat.registerReceiver(service, mMsgReceive, mFilter, Utils.receiverFlags())

        currentConfig = config
        // hev-socks5-tunnel reads the TUN; Xray only listens on its
        // authenticated loopback SOCKS inbound.
        val tunFd = if (SettingsManager.isUsingHevTun()) 0 else vpnInterface?.fd ?: 0

        NotificationManager.showNotification(currentConfig)
        coreController.startLoop(result.content, tunFd)
        if (!coreController.isRunning) error("ENGINE_FAILED")

        colituStore.encode(AppConfig.PREF_COLITU_CONNECTED_AT, System.currentTimeMillis())
        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
        WidgetProvider.refresh(service, true)
        NotificationManager.startSpeedNotification(currentConfig)
        startColituTraffic(service)
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core started successfully")
    }

    fun stopCoreLoop(): Boolean {
        val service = getService() ?: return false

        if (coreController.isRunning) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    coreController.stopLoop()
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop V2Ray loop", e)
                }
            }
        }

        stopColituTraffic()
        colituStore.removeValueForKey(AppConfig.PREF_COLITU_CONNECTED_AT)
        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        WidgetProvider.refresh(service, false)
        NotificationManager.cancelNotification()

        try {
            service.unregisterReceiver(mMsgReceive)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: receiver was not registered")
        }
        return true
    }

    fun queryStats(tag: String, link: String): Long = coreController.queryStats(tag, link)

    /**
     * Sends the proxy outbound's speed to the Colitu home screen once a
     * second while the screen is on. The speed notification reads (and
     * resets) the same counters, so this stays off when that is enabled.
     */
    private fun startColituTraffic(service: Service) {
        if (colituTrafficJob != null || !coreController.isRunning) return
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED)) return
        colituTrafficJob = CoroutineScope(Dispatchers.IO).launch {
            var last = SystemClock.elapsedRealtime()
            while (isActive && coreController.isRunning) {
                delay(1000L)
                val now = SystemClock.elapsedRealtime()
                val seconds = ((now - last) / 1000.0).coerceAtLeast(0.2)
                last = now
                val up = runCatching { coreController.queryStats(AppConfig.TAG_PROXY, AppConfig.UPLINK) }.getOrDefault(0L)
                val down = runCatching { coreController.queryStats(AppConfig.TAG_PROXY, AppConfig.DOWNLINK) }.getOrDefault(0L)
                MessageUtil.sendMsg2UI(service, AppConfig.MSG_COLITU_TRAFFIC, "${(up / seconds).toLong()},${(down / seconds).toLong()}")
            }
        }
    }

    private fun stopColituTraffic() {
        colituTrafficJob?.cancel()
        colituTrafficJob = null
    }

    /**
     * Real request through the running tunnel for the Colitu connect flow:
     * replies with the delay in ms, or -1 when nothing came back.
     */
    private fun verifyColituTunnel(id: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val service = getService() ?: return@launch
            var time = -1L
            if (coreController.isRunning) {
                for (url in listOf(SettingsManager.getDelayTestUrl(), SettingsManager.getDelayTestUrl(true))) {
                    time = runCatching { coreController.measureDelay(url) }.getOrDefault(-1L)
                    if (time >= 0) break
                }
            }
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_COLITU_VERIFY_RESULT, "$id,$time")
        }
    }

    private fun getService(): Service? = serviceControl?.get()?.getService()

    private class CoreCallback : CoreCallbackHandler {
        override fun startup(): Long = 0

        override fun shutdown(): Long {
            val serviceControl = serviceControl?.get() ?: return -1
            return try {
                serviceControl.stopService()
                0
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop service", e)
                -1
            }
        }

        override fun onEmitStatus(l: Long, s: String?): Long = 0
    }

    /** Finds the app that owns a connection (Android 10+), for process rules in the routing policy. */
    private class XrayProcessFinder(context: Context) : ProcessFinder {
        private val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

        override fun findProcessByConnection(network: String, srcIP: String, srcPort: Long, destIP: String, destPort: Long): Long {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1L
            if (cm == null) return -1L
            val proto = when (network) {
                "tcp" -> OsConstants.IPPROTO_TCP
                "udp" -> OsConstants.IPPROTO_UDP
                else -> return -1L
            }
            if (destIP.isBlank() || destPort == 0L) return -1L
            return try {
                cm.getConnectionOwnerUid(
                    proto,
                    InetSocketAddress(srcIP, srcPort.toInt()),
                    InetSocketAddress(destIP, destPort.toInt()),
                ).toLong()
            } catch (_: Exception) {
                -1L
            }
        }
    }

    /** Messages from the Colitu UI and the notification (private broadcasts only). */
    private class ReceiveMessageHandler : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> MessageUtil.sendMsg2UI(
                    serviceControl.getService(),
                    if (coreController.isRunning) AppConfig.MSG_STATE_RUNNING else AppConfig.MSG_STATE_NOT_RUNNING,
                    "",
                )

                AppConfig.MSG_STATE_STOP -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Stop service")
                    serviceControl.stopService()
                }

                AppConfig.MSG_STATE_RESTART -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                    val app = serviceControl.getService().applicationContext
                    serviceControl.stopService()
                    // A restart is a quick start: only while the profile is still valid.
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(500L)
                        ColituQuickStart.start(app)
                    }
                }

                AppConfig.MSG_COLITU_VERIFY -> verifyColituTunnel(intent.getSerializableExtra("content")?.toString().orEmpty())
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    NotificationManager.stopSpeedNotification(currentConfig)
                    stopColituTraffic()
                }

                Intent.ACTION_SCREEN_ON -> {
                    NotificationManager.startSpeedNotification(currentConfig)
                    startColituTraffic(serviceControl.getService())
                }
            }
        }
    }
}
