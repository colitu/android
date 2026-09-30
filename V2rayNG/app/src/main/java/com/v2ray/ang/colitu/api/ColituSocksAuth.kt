package com.v2ray.ang.colitu.api

import java.net.Authenticator
import java.net.PasswordAuthentication
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Answers the JDK's SOCKS5 username/password prompt for the tunnel's local
 * inbound and nothing else (no HTTP, no other host or port), so API calls
 * can use the authenticated proxy.
 */
internal object ColituSocksAuth {
    @Volatile private var current: LocalProxy? = null
    private val installed = AtomicBoolean(false)

    fun install(proxy: LocalProxy) {
        current = proxy
        if (!installed.compareAndSet(false, true)) return
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                val p = current ?: return null
                if (!requestingProtocol.equals("SOCKS5", ignoreCase = true)) return null
                if (requestingPort != p.port) return null
                val host = requestingHost
                if (host != null && host != "127.0.0.1" && host != "localhost") return null
                return PasswordAuthentication(p.user, p.password.toCharArray())
            }
        })
    }
}
