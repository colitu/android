package com.v2ray.ang.colitu.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Ping of a location: the TCP connect time to the node's latency probe
 * address (the panel's latency_host / latency_port), best of two tries, like
 * the Windows app. Measured outside the tunnel only; through a running VPN
 * the number would describe the path between two nodes, not the phone.
 */
object ColituLatency {
    private const val TIMEOUT_MS = 2_500

    suspend fun measure(host: String, port: Int): Int? = withContext(Dispatchers.IO) {
        val tries = (0 until 2).map { connectMs(host, port) }
        tries.filterNotNull().minOrNull()
    }

    /**
     * Pings of every server with a probe address, by server id; null when the
     * probe timed out or was refused (automatic mode tries that server last).
     */
    suspend fun measureAll(servers: List<ColituServer>): Map<String, Int?> = coroutineScope {
        servers
            .filter { !it.latencyHost.isNullOrBlank() && (it.latencyPort ?: 0) in 1..65535 }
            .map { server -> async { server.id to measure(server.latencyHost!!, server.latencyPort!!) } }
            .awaitAll()
            .toMap()
    }

    private fun connectMs(host: String, port: Int): Int? = try {
        // Resolve first so the DNS lookup is not counted as ping.
        val address = InetSocketAddress(host, port)
        Socket().use { socket ->
            val started = System.nanoTime()
            socket.connect(address, TIMEOUT_MS)
            ((System.nanoTime() - started) / 1_000_000).toInt().coerceAtLeast(1)
        }
    } catch (_: Exception) {
        null
    }
}
