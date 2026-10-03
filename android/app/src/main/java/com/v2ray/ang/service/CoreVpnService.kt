package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import androidx.annotation.RequiresApi
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.colitu.app.ColituQuickStart
import com.v2ray.ang.colitu.data.ColituAdBlock
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MyContextWrapper
import com.v2ray.ang.util.Utils
import java.lang.ref.SoftReference

@SuppressLint("VpnServicePolicy")
class CoreVpnService : VpnService(), ServiceControl {
    /**
     * Starts run on [startExecutor], stops on the main thread. Every start and
     * every stop takes a new [generation] under [lock]; a start that finds
     * its generation outdated (the user stopped while the interface or the
     * core was still coming up) tears down what it built instead of leaving
     * a tunnel running behind a "disconnected" app.
     */
    private val lock = Any()
    @Volatile private var generation = 0L
    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var isRunning = false
    @Volatile private var tun2SocksService: Tun2SocksControl? = null

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface: https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     */
    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }

    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onLost(network: Network) {
                setUnderlyingNetworks(null)
            }
        }
    }

    private val startExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "colitu-vpn-start")
    }

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service created")
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        CoreServiceManager.serviceControl = SoftReference(this)
    }

    override fun onRevoke() {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: Permission revoked")
        stopAllService()
    }

    override fun onDestroy() {
        super.onDestroy()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service destroyed")
        synchronized(lock) {
            generation++
            closeInterfaceLocked()
        }
        startExecutor.shutdownNow()
        NotificationManager.cancelNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service command received")
        NotificationManager.showNotification(null)

        // Android starts the service by itself for Always-on VPN and after the
        // process was killed (sticky restart). Those starts follow the same
        // rule as the tile: only a Colitu profile that is still valid.
        val systemStart = intent == null || intent.action == SERVICE_INTERFACE
        if (systemStart) {
            val guid = ColituQuickStart.startableGuid()
            if (guid == null) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: no valid Colitu profile for a system start")
                stopAllService()
                return START_NOT_STICKY
            }
            MmkvManager.setSelectServer(guid)
        }

        val gen = synchronized(lock) { ++generation }
        // Building the interface and starting Xray + hev can take seconds on a
        // slow or busy phone. On the main thread that tripped Android's
        // service-start watchdog (ANR), so the work runs on one background
        // thread; starts stay in order.
        startExecutor.execute { startGeneration(gen) }
        return START_STICKY
    }

    private fun startGeneration(gen: Long) {
        if (gen != generation) return
        if (prepare(this) != null) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Permission not granted")
            stopAllService()
            return
        }
        val iface = configureVpnService(gen) ?: return
        runTun2socks(iface)
        if (gen != generation) {
            teardownStale()
            return
        }
        if (!CoreServiceManager.startCoreLoop(iface)) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to start core loop")
            stopAllService()
            return
        }
        // A stop that arrived while the core was starting found nothing to
        // stop yet; finish it now.
        if (gen != generation) teardownStale()
    }

    private fun teardownStale() {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: stopped while starting, tearing down")
        tun2SocksService?.stopTun2Socks()
        tun2SocksService = null
        CoreServiceManager.stopCoreLoop()
        synchronized(lock) { closeInterfaceLocked() }
    }

    override fun getService(): Service = this

    override fun startService() {
        val iface = synchronized(lock) { vpnInterface } ?: run {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Interface not initialized")
            return
        }
        if (!CoreServiceManager.startCoreLoop(iface)) stopAllService()
    }

    override fun stopService() {
        stopAllService(true)
    }

    override fun vpnProtect(socket: Int): Boolean = protect(socket)

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let { MyContextWrapper.wrap(newBase, SettingsManager.getLocale()) }
        super.attachBaseContext(context)
    }

    /** Builds the TUN interface; null when it failed or a stop superseded [gen]. */
    private fun configureVpnService(gen: Long): ParcelFileDescriptor? {
        val builder = Builder()
        configureNetworkSettings(builder)
        configurePerAppProxy(builder)
        configurePlatformFeatures(builder)

        val established = try {
            builder.establish()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish VPN interface", e)
            null
        }
        if (established == null) {
            stopAllService()
            return null
        }
        synchronized(lock) {
            if (gen != generation) {
                runCatching { established.close() }
                return null
            }
            closeInterfaceLocked()
            vpnInterface = established
            isRunning = true
        }
        return established
    }

    private fun configureNetworkSettings(builder: Builder) {
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val bypassLan = SettingsManager.routingRulesetsBypassLan()

        builder.setMtu(SettingsManager.getVpnMtu())
        builder.addAddress(vpnConfig.ipv4Client, 30)

        if (bypassLan) {
            AppConfig.ROUTED_IP_LIST.forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED)) {
            builder.addAddress(vpnConfig.ipv6Client, 126)
            if (bypassLan) {
                builder.addRoute("2000::", 3) // Currently only 1/8 of total IPv6 is in use
                builder.addRoute("fc00::", 18) // Xray-core default FakeIPv6 Pool
            } else {
                builder.addRoute("::", 0)
            }
        }

        if (ColituAdBlock.enabled) {
            // Xray answers port 53 itself (ColituAdBlock). The tunnel's own
            // peer address has no DoT/DoH, so Android's automatic Private DNS
            // and Chrome's secure DNS cannot upgrade around the blocking.
            builder.addDnsServer(vpnConfig.ipv4Router)
        } else {
            SettingsManager.getVpnDnsServers().forEach {
                if (Utils.isPureIpAddress(it)) builder.addDnsServer(it)
            }
        }
    }

    private fun configurePlatformFeatures(builder: Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to request network", e)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
    }

    /**
     * The app itself stays outside the tunnel: Xray's own connections to the
     * VPN server must not loop back into it. Colitu's API calls go through
     * the tunnel anyway, via the authenticated local SOCKS inbound
     * (ColituApiClient).
     */
    private fun configurePerAppProxy(builder: Builder) {
        val selfPackageName = BuildConfig.APPLICATION_ID
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY)) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }
        val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
        if (apps.isNullOrEmpty()) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }
        val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
        if (bypassApps) apps.add(selfPackageName) else apps.remove(selfPackageName)
        apps.forEach {
            try {
                if (bypassApps) builder.addDisallowedApplication(it) else builder.addAllowedApplication(it)
            } catch (e: PackageManager.NameNotFoundException) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: app not installed, skipped")
            }
        }
    }

    private fun runTun2socks(iface: ParcelFileDescriptor) {
        tun2SocksService = if (SettingsManager.isUsingHevTun()) {
            TProxyService(
                context = applicationContext,
                vpnInterface = iface,
                isRunningProvider = { isRunning },
                restartCallback = { runTun2socks(iface) },
            )
        } else {
            null
        }
        tun2SocksService?.startTun2Socks()
    }

    private fun closeInterfaceLocked() {
        val iface = vpnInterface ?: return
        vpnInterface = null
        try {
            iface.close()
            LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface", e)
        }
    }

    private fun stopAllService(isForced: Boolean = true) {
        synchronized(lock) {
            generation++
            isRunning = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.unregisterNetworkCallback(defaultNetworkCallback)
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: network callback was not registered")
            }
        }

        tun2SocksService?.stopTun2Socks()
        tun2SocksService = null

        CoreServiceManager.stopCoreLoop()

        if (isForced) {
            // stopSelf has to come before closing the interface, otherwise the
            // core does not release its port (seen upstream in v2rayNG).
            stopSelf()
            // Give the asynchronous core stop a moment before the interface
            // closes, so the VPN key icon does not linger in the status bar.
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: Sleep interrupted")
            }
            synchronized(lock) { closeInterfaceLocked() }
        }
    }
}
