package com.v2ray.ang.colitu.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * DNS sessions end soon after their last query. hev-socks5-tunnel opens a
 * SOCKS UDP association per DNS query (a new source port each time), the
 * port-53 rule sends it to the `dns` outbound, which answers A/AAAA itself
 * and never dials upstream; the session (six goroutines, three 8 KB buffers)
 * only ended when hev's UDP timeout (60 s) closed its TCP control
 * connection. The `dns-out` outbound now runs at its own policy level
 * [LEVEL], whose `connIdle` outlasts the slowest serial lookup (the answer
 * to an IP query does not refresh the idle timer). Level 0, TCP and hev's
 * settings stay as they are. Same rule as iOS (PacketTunnelProvider
 * dnsSessionLevel).
 */
object ColituDnsSessions {
    const val LEVEL = 53
    private const val DNS_OUT = "dns-out"
    /** Xray's default per-server timeout when a server sets none (or 0). */
    private const val DEFAULT_SERVER_TIMEOUT_MS = 4_000L
    private const val MARGIN_MS = 1_000L
    private const val MIN_IDLE_S = 5L
    /** Xray's default connIdle of level 0. */
    private const val DEFAULT_LEVEL0_IDLE_S = 300L

    /**
     * Seconds: every server's timeout in turn plus [MARGIN_MS], rounded up,
     * at least [MIN_IDLE_S] and at most level 0's connIdle ([level0IdleS]).
     */
    fun idleSeconds(dns: JsonObject?, level0IdleS: Long?): Long {
        val total = dns?.get("servers")?.takeIf { it.isJsonArray }?.asJsonArray?.sumOf { server ->
            server.takeIf { it.isJsonObject }?.asJsonObject?.get("timeoutMs")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
                ?.takeIf { it > 0 } ?: DEFAULT_SERVER_TIMEOUT_MS
        } ?: 0L
        val seconds = (total + MARGIN_MS + 999) / 1000
        val max = (level0IdleS ?: DEFAULT_LEVEL0_IDLE_S).coerceAtLeast(MIN_IDLE_S)
        return seconds.coerceIn(MIN_IDLE_S, max)
    }

    /**
     * [raw] with `dns-out` at [LEVEL] (unless it names a level of its own)
     * and that level's `connIdle` (unless set). Unchanged without a
     * `dns-out` outbound. Idempotent.
     */
    fun apply(raw: String): String = runCatching {
        val json = JsonParser.parseString(raw).asJsonObject
        val dnsOut = json.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { o -> o.isJsonObject }?.asJsonObject }
            ?.firstOrNull { it.get("tag")?.takeIf { t -> t.isJsonPrimitive }?.asString == DNS_OUT && it.get("protocol")?.asString == "dns" }
            ?: return raw
        val settings = dnsOut.get("settings")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject().also { dnsOut.add("settings", it) }
        if (!settings.has("userLevel")) settings.addProperty("userLevel", LEVEL)
        // A level the config chose itself is left alone (level 0 above all).
        if (settings.get("userLevel")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt != LEVEL) return json.toString()
        val policy = json.get("policy")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also { json.add("policy", it) }
        val levels = policy.get("levels")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also { policy.add("levels", it) }
        val level = levels.get(LEVEL.toString())?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject().also { levels.add(LEVEL.toString(), it) }
        if (!level.has("connIdle")) {
            val level0Idle = levels.get("0")?.takeIf { it.isJsonObject }?.asJsonObject?.get("connIdle")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
            level.addProperty("connIdle", idleSeconds(json.get("dns")?.takeIf { it.isJsonObject }?.asJsonObject, level0Idle))
        }
        json.toString()
    }.getOrDefault(raw)
}
