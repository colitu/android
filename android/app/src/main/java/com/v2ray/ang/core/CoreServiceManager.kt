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
import com.v2ray.ang.colitu.app.ColituNoticeCenter
import com.v2ray.ang.colitu.app.ColituQuickStart
import com.v2ray.ang.colitu.repository.ColituVpnRepository
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    /** [mMsgReceive] stays registered through a hold (switch); never twice. */
    @Volatile private var msgReceiverRegistered = false
    private var currentConfig: ProfileItem? = null
    private var processFinder: XrayProcessFinder? = null
    private var colituTrafficJob: Job? = null
    private val colituStore by lazy { MMKV.mmkvWithID("COLITU_SETTINGS", MMKV.MULTI_PROCESS_MODE) }

    /**
     * Why the next stop happens, sent with [AppConfig.MSG_STATE_STOP_SUCCESS]:
     * [STOP_REQUESTED] (the app, notification, tile or a shortcut asked),
     * [STOP_REVOKED] (another VPN took over), [STOP_HOLD] (a switch), or empty
     * when the tunnel went down by itself; only then does the app reconnect.
     */
    @Volatile var colituStopReason: String = ""

    const val STOP_REQUESTED = "requested"
    const val STOP_REVOKED = "revoked"
    const val STOP_HOLD = "hold"

    /**
     * Generic endpoints for the Colitu traffic check, asked at the same time;
     * the first 2xx/204 wins. Never the Colitu API (it may be blocked or
     * reachable outside the tunnel for other reasons).
     */
    private const val COLITU_CHECK_TIMEOUT_MS = 6_000L

    /** The spare health check: one plain-HTTP request, no TLS of its own (the probe leaves at the node). */
    private const val COLITU_SPARE_CHECK_URL = "http://www.gstatic.com/generate_204"

    private val COLITU_CHECK_URLS = listOf(
        "https://cp.cloudflare.com/generate_204",
        "https://www.gstatic.com/generate_204",
        "http://www.msftconnecttest.com/connecttest.txt",
    )

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

    /**
     * Colitu switch: stops the core but keeps the VPN interface, so traffic is
     * blocked rather than sent around the tunnel until the next
     * [startVService] (see CoreVpnService.holdService).
     */
    fun holdVService(context: Context) {
        MessageUtil.sendMsg2Service(context, AppConfig.MSG_COLITU_HOLD, "")
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

    /** [reportFailure] false: the caller retries, only its last attempt reaches the UI. */
    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?, reportFailure: Boolean = true): Boolean {
        // After a hold the previous core may still be stopping (stopLoop runs
        // asynchronously); give it a moment instead of failing the switch.
        val deadline = SystemClock.elapsedRealtime() + CORE_STOP_WAIT_MS
        while (coreController.isRunning && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50)
        }
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
            if (reportFailure) {
                MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
                NotificationManager.cancelNotification()
            }
            false
        }
    }

    @Throws(Exception::class)
    private fun doStartCoreLoop(service: Service, vpnInterface: ParcelFileDescriptor?) {
        // Registered first: a failed start is held (CoreVpnService), and the UI's stop must still arrive.
        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
        mFilter.addAction(Intent.ACTION_SCREEN_ON)
        mFilter.addAction(Intent.ACTION_SCREEN_OFF)
        if (!msgReceiverRegistered) {
            ContextCompat.registerReceiver(service, mMsgReceive, mFilter, Utils.receiverFlags())
            msgReceiverRegistered = true
        }

        val guid = MmkvManager.getSelectServer() ?: error("NO_PROFILE")
        val config = MmkvManager.decodeServerConfig(guid) ?: error("CONFIG_NOT_READY")
        // Every start, whatever started it, gets a new SOCKS port and account; never a reused, guessable port.
        if (!ColituVpnRepository.renewLocalProxy(guid)) error("CONFIG_NOT_READY")

        val result = CoreConfigManager.getV2rayConfig(service, guid)
        if (!result.status) error(result.errorMessage.ifBlank { "ENGINE_FAILED" })
        LogUtil.w(AppConfig.TAG, "Colitu: core routing ${com.v2ray.ang.colitu.data.ColituWarmSpare.describe(result.content)}")

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
        ColituNoticeCenter.startTunnelWatcher() // announcements every ~3 h while the tunnel is up
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core started successfully")
    }

    /** [keepReceiver]: a hold, the service still has to hear the next stop or hold. */
    fun stopCoreLoop(keepReceiver: Boolean = false): Boolean {
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
        val reason = if (keepReceiver) STOP_HOLD else colituStopReason
        colituStopReason = ""
        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, reason)
        WidgetProvider.refresh(service, false)
        NotificationManager.cancelNotification()

        if (!keepReceiver) {
            try {
                service.unregisterReceiver(mMsgReceive)
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "StartCore-Manager: receiver was not registered")
            }
            msgReceiverRegistered = false
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
                // The warm spare's outbound carries the traffic while the primary is down.
                val up = colituOutboundTags.sumOf { tag -> runCatching { coreController.queryStats(tag, AppConfig.UPLINK) }.getOrDefault(0L) }
                val down = colituOutboundTags.sumOf { tag -> runCatching { coreController.queryStats(tag, AppConfig.DOWNLINK) }.getOrDefault(0L) }
                synchronized(colituRecentBytes) {
                    colituRecentBytes.addLast(up + down)
                    while (colituRecentBytes.size > 10) colituRecentBytes.removeFirst()
                }
                MessageUtil.sendMsg2UI(service, AppConfig.MSG_COLITU_TRAFFIC, "${(up / seconds).toLong()},${(down / seconds).toLong()}")
            }
        }
    }

    private val colituOutboundTags = listOf(AppConfig.TAG_PROXY, com.v2ray.ang.colitu.data.ColituWarmSpare.SPARE_TAG)

    private fun stopColituTraffic() {
        colituTrafficJob?.cancel()
        colituTrafficJob = null
        synchronized(colituRecentBytes) { colituRecentBytes.clear() }
    }

    /**
     * Real request through the running tunnel for the Colitu connect flow:
     * every [COLITU_CHECK_URLS] at once, replies with the first delay in ms,
     * or -1 when none came back. "<id>,primary" asks through the check
     * inbound, which reaches the primary outbound only (a warm spare must not
     * pass a dead primary's connect check); "<id>" goes the normal way.
     */
    private fun verifyColituTunnel(content: String) {
        val id = content.substringBefore(',')
        val mode = content.substringAfter(',', "")
        CoroutineScope(Dispatchers.IO).launch {
            val service = getService() ?: return@launch
            val time = when {
                !coreController.isRunning -> -1L
                // The warm spare alone, through its own check inbound: one small request.
                mode == "spare" -> com.v2ray.ang.colitu.api.ColituLocalProxy.verifySpareProxy()
                    ?.let { primaryDelay(verifyClient(it), COLITU_SPARE_CHECK_URL) } ?: -1L
                mode == "primary" -> firstColituAnswer(com.v2ray.ang.colitu.api.ColituLocalProxy.verifyProxy())
                else -> firstColituAnswer(null)
            }
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_COLITU_VERIFY_RESULT, "$id,$time")
        }
    }

    /**
     * Bytes the tunnel carried in the last 10 s, for the warm spare swap:
     * from the once-a-second speed samples while the screen is on, else
     * measured over the next 10 s (counters read and reset, like the speed
     * samples do).
     */
    private fun answerColituIdle(id: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val service = getService() ?: return@launch
            val bytes = when {
                !coreController.isRunning -> -1L
                colituTrafficJob != null && colituRecentBytes.size >= 10 -> synchronized(colituRecentBytes) { colituRecentBytes.sum() }
                else -> {
                    colituOutboundBytes()
                    delay(10_000L)
                    colituOutboundBytes()
                }
            }
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_COLITU_IDLE_RESULT, "$id,$bytes")
        }
    }

    /** Up plus down of the primary and spare outbounds since the last read (the read resets them). */
    private fun colituOutboundBytes(): Long = colituOutboundTags.sumOf { tag ->
        runCatching { coreController.queryStats(tag, AppConfig.UPLINK) }.getOrDefault(0L) +
            runCatching { coreController.queryStats(tag, AppConfig.DOWNLINK) }.getOrDefault(0L)
    }

    /** The last ten one-second byte totals of the speed samples. */
    private val colituRecentBytes = ArrayDeque<Long>()

    /** OkHttp through the check inbound (HTTP proxy with its own account). */
    private fun verifyClient(verify: com.v2ray.ang.colitu.api.LocalProxy): okhttp3.OkHttpClient =
        okhttp3.OkHttpClient.Builder()
            .proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", verify.port)))
            .proxyAuthenticator { _, response ->
                // Asked once (OkHttp also asks before a CONNECT); give up on a second refusal.
                if (response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder()
                    .header("Proxy-Authorization", okhttp3.Credentials.basic(verify.user, verify.password))
                    .build()
            }
            .callTimeout(COLITU_CHECK_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

    private fun primaryDelay(client: okhttp3.OkHttpClient, url: String): Long = runCatching {
        val started = SystemClock.elapsedRealtime()
        client.newCall(okhttp3.Request.Builder().url(url).head().build()).execute().use { response ->
            if (response.code in 200..299) SystemClock.elapsedRealtime() - started else -1L
        }
    }.getOrDefault(-1L)

    private suspend fun firstColituAnswer(verify: com.v2ray.ang.colitu.api.LocalProxy?): Long {
        val answer = CompletableDeferred<Long>()
        val client = verify?.let(::verifyClient)
        // Own scopes, not children: the native call cannot be cancelled, so a
        // slow endpoint must not hold back the first answer.
        val probes = COLITU_CHECK_URLS.map { url ->
            CoroutineScope(Dispatchers.IO).async {
                val time = if (client != null) primaryDelay(client, url)
                else runCatching { coreController.measureDelay(url) }.getOrDefault(-1L)
                if (time >= 0) answer.complete(time)
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            probes.awaitAll()
            answer.complete(-1L)
        }
        return answer.await()
    }

    private fun getService(): Service? = serviceControl?.get()?.getService()

    private const val CORE_STOP_WAIT_MS = 3_000L

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
                    colituStopReason = STOP_REQUESTED
                    serviceControl.stopService()
                }

                AppConfig.MSG_STATE_RESTART -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                    val app = serviceControl.getService().applicationContext
                    // A restart is a quick start: only while the profile is still
                    // valid. The interface is held meanwhile, nothing leaks.
                    if (ColituQuickStart.startableGuid() == null) {
                        serviceControl.stopService()
                    } else {
                        serviceControl.holdService()
                        CoroutineScope(Dispatchers.Main).launch {
                            delay(500L)
                            if (!ColituQuickStart.start(app)) serviceControl.stopService()
                        }
                    }
                }

                AppConfig.MSG_COLITU_HOLD -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Hold service")
                    serviceControl.holdService()
                }

                AppConfig.MSG_COLITU_VERIFY -> verifyColituTunnel(intent.getSerializableExtra("content")?.toString().orEmpty())

                AppConfig.MSG_COLITU_IDLE -> answerColituIdle(intent.getSerializableExtra("content")?.toString().orEmpty())
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
