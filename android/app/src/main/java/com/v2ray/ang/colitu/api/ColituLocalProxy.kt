package com.v2ray.ang.colitu.api

import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import java.net.ServerSocket
import java.security.SecureRandom

/** Xray's loopback SOCKS inbound: port and the account hev-socks5-tunnel and the API client use. */
data class LocalProxy(val port: Int, val user: String, val password: String)

/**
 * Every connection gets a fresh SOCKS port and account. Before this the
 * inbound listened on 127.0.0.1:10808 without a password, so any app on the
 * phone could send traffic through the tunnel, detect the VPN or read the
 * exit address. The values live in the multi-process settings store because
 * hev-socks5-tunnel (VPN process) and ColituApiClient (UI process) both need
 * them.
 */
object ColituLocalProxy {
    private val random = SecureRandom()

    fun newSession(): LocalProxy {
        val proxy = LocalProxy(port = freePort(), user = token(12), password = token(24))
        MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_PORT, proxy.port.toString())
        MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_USERNAME, protect(AppConfig.PREF_SOCKS_USERNAME, proxy.user))
        MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_PASSWORD, protect(AppConfig.PREF_SOCKS_PASSWORD, proxy.password))
        return proxy
    }

    /**
     * The loopback HTTP inbound of the connect-time traffic check (tag
     * `colitu-verify`, routed straight to the primary outbound, never to the
     * warm spare's balancer). Fresh port and account at every core start,
     * like the SOCKS inbound; read by the VPN process for the check.
     */
    fun newVerifySession(): LocalProxy {
        val proxy = LocalProxy(port = freePort(), user = token(12), password = token(24))
        colituStore().apply {
            encode(KEY_VERIFY_PORT, proxy.port)
            encode(KEY_VERIFY_USER, protect(KEY_VERIFY_USER, proxy.user))
            encode(KEY_VERIFY_PASSWORD, protect(KEY_VERIFY_PASSWORD, proxy.password))
        }
        return proxy
    }

    /** The warm spare's own check inbound (`colitu-verify-spare`); fresh at every core start like the primary's. */
    fun newVerifySpareSession(): LocalProxy {
        val proxy = LocalProxy(port = freePort(), user = token(12), password = token(24))
        colituStore().apply {
            encode(KEY_VERIFY_SPARE_PORT, proxy.port)
            encode(KEY_VERIFY_SPARE_USER, protect(KEY_VERIFY_SPARE_USER, proxy.user))
            encode(KEY_VERIFY_SPARE_PASSWORD, protect(KEY_VERIFY_SPARE_PASSWORD, proxy.password))
        }
        return proxy
    }

    fun verifySpareProxy(): LocalProxy? = runCatching {
        val store = colituStore()
        val port = store.decodeInt(KEY_VERIFY_SPARE_PORT, 0).takeIf { it in 1..65535 } ?: return null
        val user = readStored(store, KEY_VERIFY_SPARE_USER) ?: return null
        val password = readStored(store, KEY_VERIFY_SPARE_PASSWORD) ?: return null
        LocalProxy(port, user, password)
    }.getOrNull()

    private const val KEY_VERIFY_SPARE_PORT = "colitu_verify_spare_port"
    private const val KEY_VERIFY_SPARE_USER = "colitu_verify_spare_user"
    private const val KEY_VERIFY_SPARE_PASSWORD = "colitu_verify_spare_password"

    /** The current check inbound, or null when the running profile has none (made before it existed). */
    fun verifyProxy(): LocalProxy? = runCatching {
        val store = colituStore()
        val port = store.decodeInt(KEY_VERIFY_PORT, 0).takeIf { it in 1..65535 } ?: return null
        val user = readStored(store, KEY_VERIFY_USER) ?: return null
        val password = readStored(store, KEY_VERIFY_PASSWORD) ?: return null
        LocalProxy(port, user, password)
    }.getOrNull()

    private const val KEY_VERIFY_PORT = "colitu_verify_port"
    private const val KEY_VERIFY_USER = "colitu_verify_user"
    private const val KEY_VERIFY_PASSWORD = "colitu_verify_password"

    private fun colituStore() = MMKV.mmkvWithID("COLITU_SETTINGS", MMKV.MULTI_PROCESS_MODE)

    /** The session's proxy while the tunnel is up, else null. */
    fun activeTunnel(): LocalProxy? {
        val connectedAt = runCatching {
            MMKV.mmkvWithID("COLITU_SETTINGS", MMKV.MULTI_PROCESS_MODE).decodeLong(AppConfig.PREF_COLITU_CONNECTED_AT, 0L)
        }.getOrDefault(0L)
        if (connectedAt <= 0L) return null
        val port = MmkvManager.decodeSettingsString(AppConfig.PREF_SOCKS_PORT)?.toIntOrNull() ?: return null
        val user = socksUsername()?.takeIf { it.isNotBlank() } ?: return null
        val password = socksPassword()?.takeIf { it.isNotBlank() } ?: return null
        return LocalProxy(port, user, password)
    }

    /** The loopback SOCKS account (opened from the Keystore-sealed value; an old plain value is read once and sealed). */
    fun socksUsername(): String? = readSettingSecret(AppConfig.PREF_SOCKS_USERNAME)

    fun socksPassword(): String? = readSettingSecret(AppConfig.PREF_SOCKS_PASSWORD)

    private fun readSettingSecret(key: String): String? =
        reveal(key, MmkvManager.decodeSettingsString(key)) { MmkvManager.encodeSettings(key, protect(key, it)) }

    private fun readStored(store: MMKV, key: String): String? =
        reveal(key, store.decodeString(key)) { store.encode(key, protect(key, it)) }

    /**
     * The local proxy accounts used to sit in plain MMKV files. They are sealed
     * with [ColituSecureStore] (Keystore key shared by the app and VPN processes,
     * context-bound to the setting name) and carry [SEALED] in front. A device
     * whose sealing fails keeps the plain value: the tunnel must still start.
     */
    private const val SEALED = "colitu-sealed-v1:"

    internal fun protect(key: String, value: String): String =
        runCatching { SEALED + ColituSecureStore.sealConfig(value, "local-proxy:$key") }.getOrDefault(value)

    /**
     * [stored] in clear: a sealed value is opened with [open]; a legacy plain
     * value is returned as it is and handed to [migrate] once so it gets sealed.
     */
    internal fun reveal(
        key: String,
        stored: String?,
        open: (sealed: String, context: String) -> String = ColituSecureStore::openConfig,
        migrate: (String) -> Unit,
    ): String? {
        if (stored.isNullOrBlank()) return null
        if (stored.startsWith(SEALED)) return runCatching { open(stored.removePrefix(SEALED), "local-proxy:$key") }.getOrNull()?.takeIf { it.isNotBlank() }
        runCatching { migrate(stored) }
        return stored
    }

    /** A random unused high port; never the well-known 10808 that scanners look for. */
    private fun freePort(): Int {
        repeat(8) {
            val port = runCatching { ServerSocket(0).use { it.localPort } }.getOrDefault(0)
            if (port in 20000..65000) return port
        }
        return 20000 + random.nextInt(40000)
    }

    /** Hex only, so the YAML and JSON configs need no escaping. */
    internal fun token(bytes: Int): String =
        ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }
}
