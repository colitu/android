package com.v2ray.ang.colitu.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Network hints from the panel (`network_hints` of /servers, only with the
 * VPN off): transports that fail for most users on this ISP network. They go
 * last unless this phone's own memory says they carried traffic here in the
 * last 24 h (local experience beats the hint).
 */
data class ColituNetworkHints(val blocked: Set<String>, val scope: String?, val updatedAt: String?) {
    companion object {
        fun fromJson(json: JsonObject?): ColituNetworkHints? {
            json ?: return null
            val blocked = json.get("blocked")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } }
                ?.toSet().orEmpty()
            return ColituNetworkHints(
                blocked,
                json.get("scope")?.takeIf { it.isJsonPrimitive }?.asString,
                json.get("updated_at")?.takeIf { it.isJsonPrimitive }?.asString,
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
data class ColituNetworkRecord(val clientNetwork: String, val token: String?, val blocked: Set<String>, val at: Long) {
    private fun valid(network: String?, now: Long) = network == clientNetwork && now - at in 0 until TTL_MS

    fun tokenFor(network: String?, now: Long): String? = token?.takeIf { valid(network, now) }

    fun blockedFor(network: String?, now: Long): Set<String> = if (valid(network, now)) blocked else emptySet()

    fun toJson(): String = JsonObject().apply {
        addProperty("network", clientNetwork)
        token?.let { addProperty("token", it) }
        add("blocked", JsonArray().apply { blocked.forEach(::add) })
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
            )
        }.getOrNull()
    }
}

/** One transport tried during a connect (or a mid-session stall: unreachable). */
data class ColituObservation(val protocol: String, val reachable: Boolean, val latencyMs: Long?)

object ColituNetworkHintsPolicy {
    /** Hinted transports that go last: those this phone did not see carry traffic here in the last 24 h. */
    fun demoted(blocked: Set<String>, workedHere: (String) -> Boolean): Set<String> = blocked.filterNot(workedHere).toSet()

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
