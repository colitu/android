package com.v2ray.ang.service

import android.content.Context
import android.os.ParcelFileDescriptor
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Manages the tun2socks process that handles VPN traffic
 */
class TProxyService(
    private val context: Context,
    private val vpnInterface: ParcelFileDescriptor,
    private val isRunningProvider: () -> Boolean,
    private val restartCallback: () -> Unit
) : Tun2SocksControl {
    companion object {
        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStartService(configPath: String, fd: Int)

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStopService()

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyGetStats(): LongArray?

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }

        /**
         * hev is started and stopped on this one thread, in order. Its stop
         * joins the tunnel thread and can take seconds when a session hangs;
         * on the main thread that was every ANR in Play (service start,
         * startForeground and broadcast timeouts).
         */
        private val control = Executors.newSingleThreadExecutor { r -> Thread(r, "hev-control").apply { isDaemon = true } }

        /** How long a stop keeps its caller waiting; the rest finishes on [control]. */
        private const val STOP_WAIT_MS = 1_500L
    }

    /** hev's own copy of the tun fd: closed only after hev has stopped, so its number is never reused under it. */
    @Volatile private var tunFd: ParcelFileDescriptor? = null

    /**
     * Starts the tun2socks process with the appropriate parameters.
     */
    override fun startTun2Socks() {
        val configContent = buildConfig()
        val fd = try {
            vpnInterface.dup()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "HevSocks5Tunnel: no tun fd (${e.message})")
            return
        }
        tunFd = fd
        // After any stop still running: hev is one instance in native code.
        control.execute {
            // Written here, not by the caller: a stop queued before this start deletes the file.
            // The config is not logged: it holds the SOCKS password.
            val configFile = configFile().apply { writeText(configContent) }
            try {
                TProxyStartService(configFile.absolutePath, fd.fd)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "HevSocks5Tunnel exception: ${e.message}")
            }
        }
    }

    /**
     * The hev config holds the local SOCKS password. hev reads it on its own
     * thread after TProxyStartService returns, so it cannot be deleted right
     * away; it is deleted when the tunnel stops and written again at every start.
     */
    private fun configFile() = File(context.filesDir, "hev-socks5-tunnel.yaml")

    private fun buildConfig(): String {
        val socksPort = SettingsManager.getSocksPort()
        val socksUsername = SettingsManager.getSocksUsername()
        val socksPassword = SettingsManager.getSocksPassword()
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val escapedSocksUsername = socksUsername?.replace("'", "''")
        val escapedSocksPassword = socksPassword?.replace("'", "''")
        return buildString {
            appendLine("tunnel:")
            appendLine("  mtu: ${SettingsManager.getVpnMtu()}")
            appendLine("  ipv4: ${vpnConfig.ipv4Client}")

            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED)) {
                appendLine("  ipv6: '${vpnConfig.ipv6Client}'")
            }

            appendLine("socks5:")
            appendLine("  port: ${socksPort}")
            appendLine("  address: ${AppConfig.LOOPBACK}")
            appendLine("  udp: 'udp'")
            if (escapedSocksUsername != null && escapedSocksPassword != null) {
                appendLine("  username: '${escapedSocksUsername}'")
                appendLine("  password: '${escapedSocksPassword}'")
            }

            // Read-write timeout settings
            val timeoutSetting = MmkvManager.decodeSettingsString(AppConfig.PREF_HEV_TUNNEL_RW_TIMEOUT) ?: AppConfig.HEVTUN_RW_TIMEOUT
            val parts = timeoutSetting.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val tcpTimeout = parts.getOrNull(0)?.toIntOrNull() ?: 300
            val udpTimeout = parts.getOrNull(1)?.toIntOrNull() ?: 60

            appendLine("misc:")
            appendLine("  tcp-read-write-timeout: ${tcpTimeout * 1000}")
            appendLine("  udp-read-write-timeout: ${udpTimeout * 1000}")
            appendLine("  log-level: ${MmkvManager.decodeSettingsString(AppConfig.PREF_HEV_TUNNEL_LOGLEVEL) ?: "warn"}")
        }
    }

    /**
     * Stops the tun2socks process
     */
    override fun stopTun2Socks() {
        val fd = tunFd
        tunFd = null
        val done = control.submit {
            val begin = System.nanoTime()
            try {
                TProxyStopService()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to stop hev-socks5-tunnel", e)
            }
            runCatching { fd?.close() }
            runCatching { configFile().delete() }
            val ms = (System.nanoTime() - begin) / 1_000_000
            if (ms > STOP_WAIT_MS) LogUtil.w(AppConfig.TAG, "hev-socks5-tunnel took $ms ms to stop (off the main thread)")
        }
        // Usually done at once, so the caller keeps its old order; a hanging stop finishes in the background.
        try {
            done.get(STOP_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            LogUtil.w(AppConfig.TAG, "hev-socks5-tunnel still stopping after $STOP_WAIT_MS ms; not waiting on this thread")
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "hev-socks5-tunnel stop: ${e.javaClass.simpleName}")
        }
    }
}
