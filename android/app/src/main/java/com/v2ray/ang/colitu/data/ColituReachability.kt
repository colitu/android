package com.v2ray.ang.colitu.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Honest server list (Adaptive Connect 4, phase 1), without Android state so
 * it is unit tested.
 *
 * - A round is one ping of every server measured with the VPN off; each round
 *   already has one retry (ColituLatency tries twice).
 * - A server is unreachable after [FAILED_ROUNDS_TO_HIDE] failed rounds in a
 *   row on the same network, and reachable again after one success (a success
 *   measured outside the tunnel while connected counts too, a failure then
 *   does not: [afterSuccesses]).
 * - Unreachable servers are hidden from the list and the automatic choice,
 *   except: nothing is hidden when every server or [NETWORK_PROBLEM_SHARE] or
 *   more of them failed (the network is the problem; such a round does not
 *   count against any server either), the servers in `keep` (connected,
 *   connecting, chosen by hand) never are, and the state of another network or
 *   older than [STATE_TTL_MS] hides nothing.
 */
data class ColituReachability(
    val network: String = "",
    /** Failed rounds in a row per server id (absent: reachable or never failed). */
    val failedRounds: Map<String, Int> = emptyMap(),
    /** The last round failed for [NETWORK_PROBLEM_SHARE] or more of the servers. */
    val networkProblem: Boolean = false,
    val roundAt: Long = 0L,
) {
    fun unreachable(id: String): Boolean = (failedRounds[id] ?: 0) >= FAILED_ROUNDS_TO_HIDE

    /** A VPN-off round: [results] by server id, null = timed out or refused. */
    fun afterRound(network: String, results: Map<String, Int?>, now: Long): ColituReachability {
        if (results.isEmpty()) return this
        val base = if (network == this.network) failedRounds else emptyMap()
        val problem = networkFailure(results.values)
        val next = base.toMutableMap()
        results.forEach { (id, ms) ->
            when {
                ms != null -> next.remove(id)
                // A round in which the network failed says nothing about one server.
                !problem -> next[id] = ((next[id] ?: 0) + 1).coerceAtMost(FAILED_ROUNDS_TO_HIDE)
            }
        }
        return ColituReachability(network, next, problem, now)
    }

    /** Successful pings measured outside the tunnel while connected: those servers are back. */
    fun afterSuccesses(network: String, ids: Collection<String>): ColituReachability {
        if (network != this.network || ids.none { it in failedRounds }) return this
        return copy(failedRounds = failedRounds - ids.toSet())
    }

    /** Ids of [ids] (the listed servers) to hide on [network] now; see the class comment. */
    fun hidden(ids: Collection<String>, keep: Set<String>, network: String, now: Long): Set<String> {
        if (network != this.network || networkProblem || now - roundAt !in 0 until STATE_TTL_MS) return emptySet()
        val all = ids.toSet()
        val down = all.filter { unreachable(it) }
        if (down.isEmpty() || down.size * 2 >= all.size) return emptySet()
        return down.filterNot { it in keep }.toSet()
    }

    fun toJson(): String = JsonObject().apply {
        addProperty("net", network)
        addProperty("at", roundAt)
        addProperty("np", networkProblem)
        add("f", JsonObject().apply { failedRounds.forEach { (id, n) -> addProperty(id, n) } })
    }.toString()

    companion object {
        const val FAILED_ROUNDS_TO_HIDE = 2

        /** This share of failed servers in a round or more: the network is the problem. */
        const val NETWORK_PROBLEM_SHARE = 0.5

        /** With the VPN on, a ping measured (outside the tunnel) at most this long ago is still shown. */
        const val CACHE_WHILE_CONNECTED_MS = 30 * 60_000L

        /** Hiding state older than this is not applied (the next round decides again). */
        const val STATE_TTL_MS = 6 * 3_600_000L

        fun networkFailure(results: Collection<Int?>): Boolean =
            results.isNotEmpty() && results.count { it == null } >= results.size * NETWORK_PROBLEM_SHARE

        /**
         * The ping a row shows: with the VPN off the last one; with the VPN on only one taken in the
         * last [CACHE_WHILE_CONNECTED_MS], else null ("— ms", and the server is not hidden for it).
         */
        fun displayMs(ping: ColituPing?, vpnOn: Boolean, now: Long): Int? {
            val ms = ping?.ms ?: return null
            if (!vpnOn) return ms
            return ms.takeIf { now - ping.measuredAt in 0..CACHE_WHILE_CONNECTED_MS }
        }

        fun fromJson(raw: String?): ColituReachability = runCatching {
            if (raw.isNullOrBlank()) return ColituReachability()
            val o = JsonParser.parseString(raw).asJsonObject
            val failed = o.getAsJsonObject("f")?.entrySet()?.mapNotNull { (id, v) ->
                v.takeIf { it.isJsonPrimitive }?.asInt?.let { id to it }
            }?.toMap().orEmpty()
            ColituReachability(
                network = o.get("net")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                failedRounds = failed,
                networkProblem = o.get("np")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
                roundAt = o.get("at")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
            )
        }.getOrDefault(ColituReachability())
    }
}
