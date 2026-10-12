package com.v2ray.ang.colitu.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Ping of a location: the TCP connect time to the node's latency probe
 * address (the panel's latency_host / latency_port), best of two tries, like
 * the Windows app. Measured outside the tunnel only; through a running VPN
 * the number would describe the path between two nodes, not the phone.
 *
 * The probe address comes from the server list, so it is bounded: at most
 * [MAX_SERVERS] servers, [PARALLEL] connects at a time, only [ALLOWED_PORTS],
 * and never a loopback, private, link-local or CGNAT address (no LAN or local
 * port scan from the phone).
 */
object ColituLatency {
    private const val TIMEOUT_MS = 2_500
    internal const val MAX_SERVERS = 200
    private const val PARALLEL = 8

    /**
     * Ports a node's probe may use: the transports' TLS-style ports
     * (VLESS/Hysteria 8443, XHTTP 2053, Trojan 4433, Shadowsocks 8388, 443
     * and the usual alternative TLS ports).
     */
    internal val ALLOWED_PORTS = setOf(443, 2053, 2083, 2087, 2096, 3443, 4433, 7443, 8388, 8443, 9443)

    suspend fun measure(host: String, port: Int): Int? = withContext(Dispatchers.IO) {
        if (!probeAllowed(host, port)) return@withContext null
        val tries = (0 until 2).map { connectMs(host, port) }
        tries.filterNotNull().minOrNull()
    }

    /**
     * Pings of every server with a probe address, by server id; null when the
     * probe timed out or was refused (automatic mode tries that server last).
     */
    suspend fun measureAll(servers: List<ColituServer>): Map<String, Int?> = coroutineScope {
        val gate = Semaphore(PARALLEL)
        servers
            .filter { !it.latencyHost.isNullOrBlank() && (it.latencyPort ?: 0) in 1..65535 }
            .take(MAX_SERVERS)
            .map { server -> async { server.id to gate.withPermit { measure(server.latencyHost!!, server.latencyPort!!) } } }
            .awaitAll()
            .toMap()
    }

    /** [port] is in [ALLOWED_PORTS] and [host] is a name or a literal that is not a non-public address. */
    internal fun probeAllowed(host: String, port: Int): Boolean {
        if (port !in ALLOWED_PORTS) return false
        val h = host.trim()
        if (h.isEmpty() || h.length > 253 || h.equals("localhost", ignoreCase = true) || h.endsWith(".localhost", ignoreCase = true)) return false
        val literal = ColituAddressRanges.parseLiteral(h.removePrefix("[").removeSuffix("]"))
        return literal == null || !ColituAddressRanges.isNonPublic(literal)
    }

    private fun connectMs(host: String, port: Int): Int? = try {
        // Resolve first so the DNS lookup is not counted as ping; a name that
        // resolves to a local address is not probed either.
        val resolved = InetAddress.getByName(host)
        if (ColituAddressRanges.isNonPublic(resolved)) {
            null
        } else {
            val address = InetSocketAddress(resolved, port)
            Socket().use { socket ->
                val started = System.nanoTime()
                socket.connect(address, TIMEOUT_MS)
                ((System.nanoTime() - started) / 1_000_000).toInt().coerceAtLeast(1)
            }
        }
    } catch (_: Exception) {
        null
    }
}
