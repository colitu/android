package com.v2ray.ang.colitu.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Network hints from the panel (`network_hints` of /servers, only with the
 * VPN off): transports that fail for most users on this ISP network. They go
 * last unless this phone's own memory says they carried traffic here in the
 * last 24 h (local experience beats the hint). `preferred` (Adaptive Connect
 * 3.0): transports that worked for most users here, best first; a phone
 * without its own memory of the network starts with them. `blocked_servers`:
 * servers most users in this country could not reach in the last hours (an
 * address block); automatic mode treats them like servers that failed here.
 */
data class ColituNetworkHints(
    val blocked: Set<String>,
    val scope: String?,
    val updatedAt: String?,
    val preferred: List<String> = emptyList(),
    val blockedServers: Set<String> = emptySet(),
) {
    companion object {
        private fun protocols(json: JsonObject, name: String): List<String> =
            json.get(name)?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } }
                ?.distinct().orEmpty()

        fun fromJson(json: JsonObject?): ColituNetworkHints? {
            json ?: return null
            val blocked = protocols(json, "blocked").toSet()
            return ColituNetworkHints(
                blocked,
                json.get("scope")?.takeIf { it.isJsonPrimitive }?.asString,
                json.get("updated_at")?.takeIf { it.isJsonPrimitive }?.asString,
                protocols(json, "preferred").filterNot { it in blocked },
                protocols(json, "blocked_servers").toSet(),
            )
        }
    }
}

/**
 * What the panel said about one ISP network ([clientNetwork]) at [at]
 * (wall-clock ms): its opaque `network_token` (sent with protocol
 * observations so the panel counts them for that network) and the hinted
 * transports. Both only count on the same network and for [TTL_MS].
 */
data class ColituNetworkRecord(
    val clientNetwork: String,
    val token: String?,
    val blocked: Set<String>,
    val at: Long,
    val preferred: List<String> = emptyList(),
    val blockedServers: Set<String> = emptySet(),
) {
    private fun valid(network: String?, now: Long) = network == clientNetwork && now - at in 0 until TTL_MS

    fun tokenFor(network: String?, now: Long): String? = token?.takeIf { valid(network, now) }

    fun blockedFor(network: String?, now: Long): Set<String> = if (valid(network, now)) blocked else emptySet()

    fun preferredFor(network: String?, now: Long): List<String> = if (valid(network, now)) preferred else emptyList()

    fun blockedServersFor(network: String?, now: Long): Set<String> = if (valid(network, now)) blockedServers else emptySet()

    fun toJson(): String = JsonObject().apply {
        addProperty("network", clientNetwork)
        token?.let { addProperty("token", it) }
        add("blocked", JsonArray().apply { blocked.forEach(::add) })
        add("preferred", JsonArray().apply { preferred.forEach(::add) })
        add("blocked_servers", JsonArray().apply { blockedServers.forEach(::add) })
        addProperty("at", at)
    }.toString()

    companion object {
        /** The panel's token is valid 48 h; the hints are as old as the fetch that brought them. */
        const val TTL_MS = 48 * 3_600_000L

        fun fromJson(raw: String?): ColituNetworkRecord? = runCatching {
            val o = JsonParser.parseString(raw ?: return null).asJsonObject
            ColituNetworkRecord(
                o.get("network").asString,
                o.get("token")?.takeIf { it.isJsonPrimitive }?.asString,
                o.get("blocked")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString }?.toSet().orEmpty(),
                o.get("at").asLong,
                o.get("preferred")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString }.orEmpty(),
                o.get("blocked_servers")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString }?.toSet().orEmpty(),
            )
        }.getOrNull()
    }
}

/** One transport tried during a connect (or a mid-session stall: unreachable). */
data class ColituObservation(val protocol: String, val reachable: Boolean, val latencyMs: Long?)

object ColituNetworkHintsPolicy {
    /** Transports that run over UDP (QUIC): the ones networks throttle as a family. */
    val UDP_TRANSPORTS = setOf("hysteria2")

    /**
     * Transports that go last on this network: hinted ones this phone did
     * not see carry traffic here in the last 24 h, and every UDP transport
     * once UDP kept stalling here mid-session ([udpDemoted], see
     * ColituConnectMemory.recordUdpStall): there "worked here" only means it
     * connected, not that it lasted.
     */
    fun demoted(blocked: Set<String>, udpDemoted: Boolean = false, workedHere: (String) -> Boolean): Set<String> =
        blocked.filterNot(workedHere).toSet() + if (udpDemoted) UDP_TRANSPORTS else emptySet()

    /**
     * Start order from the hint when the phone has no memory of this network
     * (no last good transport here): the preferred transports that are on
     * offer and not stalled, in the hint's order, then the rest by [rank].
     * Null: no usable hint, the usual order (probe) decides.
     */
    fun <T> hintedStart(configs: List<T>, protocolOf: (T) -> String?, preferred: List<String>, stalled: Set<String>, lastGood: String?, rank: (List<T>) -> List<T>): List<T>? {
        if (lastGood != null || preferred.isEmpty()) return null
        val first = preferred.distinct().mapNotNull { p -> configs.firstOrNull { protocolOf(it) == p && p !in stalled } }
        if (first.isEmpty()) return null
        return first + rank(configs.filter { it !in first })
    }

    /**
     * `POST /client/protocol-observations` body, or null when there is
     * nothing to send. The panel rejects a whole batch with a repeated
     * protocol, more than eight entries or a latency outside 0..60000 ms, so
     * the last result per transport is kept and the latency is sent only
     * for a reachable one. `network_token` only when known for the network
     * the attempt was made on.
     */
    fun observationBody(nodeId: String, observations: List<ColituObservation>, token: String?): JsonObject? {
        if (nodeId.isBlank()) return null
        val last = linkedMapOf<String, ColituObservation>()
        observations.filter { it.protocol.isNotBlank() && it.protocol != "auto" }.forEach {
            val key = it.protocol.lowercase()
            last.remove(key)
            last[key] = it
        }
        if (last.isEmpty()) return null
        return JsonObject().apply {
            addProperty("node_id", nodeId)
            add("observations", JsonArray().apply {
                last.entries.toList().takeLast(8).forEach { (protocol, o) ->
                    add(JsonObject().apply {
                        addProperty("protocol", protocol)
                        addProperty("reachable", o.reachable)
                        if (o.reachable && o.latencyMs != null) addProperty("latency_ms", o.latencyMs.coerceIn(0L, 60_000L))
                    })
                }
            })
            if (!token.isNullOrBlank()) addProperty("network_token", token)
        }
    }
}
