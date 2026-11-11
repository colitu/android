package com.v2ray.ang.colitu.data

/**
 * A transport that keeps dropping right after it connects (a DPI freeze: the handshake passes, the
 * stream stops 10-60 s later) is demoted on this network for [DEMOTED_FOR_MS]: it goes last, and a
 * transport picked by the user or by Adaptive Connect 4 no longer goes first while it freezes
 * there. A good connect does not lift it: the next connect always passes the short check, that is
 * the pattern (and [ColituServerRanking.ServerMemory.recordSuccess] clears the stall marks).
 * In memory only; same rules as the Windows and Linux apps (ColituFlapGuard). Pure, unit tested.
 */
class ColituFlapGuard {
    private val drops = HashMap<String, MutableList<Long>>()
    private val demoted = HashMap<String, Long>()

    private fun key(network: String, server: String, transport: String) = "$network|$server|$transport"

    /** Records a mid-session drop; true when it demotes the transport (newly or again). */
    @Synchronized
    fun drop(network: String, server: String?, transport: String?, connectedForMs: Long, now: Long): Boolean {
        if (server.isNullOrBlank() || transport.isNullOrBlank()) return false
        val key = key(network, server, transport.lowercase())
        val list = drops.getOrPut(key) { mutableListOf() }
        list.removeAll { now - it > WINDOW_MS }
        list += now
        if (connectedForMs >= SHORT_SESSION_MS && list.size < 2) return false
        demoted[key] = now + DEMOTED_FOR_MS
        return true
    }

    @Synchronized
    fun isDemoted(network: String, server: String?, transport: String?, now: Long): Boolean {
        if (server.isNullOrBlank() || transport.isNullOrBlank()) return false
        val key = key(network, server, transport.lowercase())
        val until = demoted[key] ?: return false
        if (now < until) return true
        demoted.remove(key)
        return false
    }

    companion object {
        /** A drop this soon after the connect counts as a flap by itself. */
        const val SHORT_SESSION_MS = 2 * 60_000L
        /** Two drops of the same transport within this window are a flap too. */
        const val WINDOW_MS = 15 * 60_000L
        const val DEMOTED_FOR_MS = 30 * 60_000L

        /** [items] with the demoted ones moved last (stable); unchanged when none or all are demoted. */
        fun <T> demotedLast(items: List<T>, demoted: (T) -> Boolean): List<T> {
            val bad = items.filter(demoted)
            return if (bad.isEmpty() || bad.size == items.size) items else items.filterNot(demoted) + bad
        }

        /** Servers penalized on this network go last in an automatic order; the same list when none or all are. */
        fun <T> penalizedLast(order: List<T>, penalized: (T) -> Boolean): List<T> = demotedLast(order, penalized)

        /**
         * A server outage round where every offered transport is a TCP one whose port did not answer
         * ([latency] value < 0): none can connect, so the server is given up at once.
         */
        fun <T> nothingCanConnect(items: List<T>, protocol: (T) -> String?, latency: Map<String, Long>): Boolean =
            items.isNotEmpty() && items.all { item -> latency[protocol(item)]?.let { it < 0 } == true }
    }
}
