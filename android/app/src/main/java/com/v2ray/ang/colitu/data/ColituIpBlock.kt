package com.v2ray.ang.colitu.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * ip_blocked classifier and measurement schedule (spec F:/DEV/wt/TLS-PROBE-SPEC.md §2-§3), pure
 * decisions; the controller does the sockets. Cases: shared fixtures tls-rst-fixtures.json.
 */
object ColituIpBlock {
    /** TLS TCP transports whose endpoint the classifier probes (never Hysteria2 / Shadowsocks). */
    val TLS_PROTOCOLS = listOf("vless-reality", "vless-xhttp", "trojan")
    const val MAX_ENDPOINTS = 2
    /** Whole extra cost of the classifier in the round probe. */
    const val CLASSIFIER_BUDGET_MS = 3_000L
    /** Whole measurement run. */
    const val MEASURE_BUDGET_MS = 8_000L
    const val MAX_TARGETS = 12

    /** An endpoint of a server's transport: where its TLS answer comes from. */
    data class Endpoint(val protocol: String, val host: String, val port: Int, val sni: String)

    /** ip_blocked <=> at least two [ColituTlsProbe.Result.TlsRst] on distinct ports; timeouts and TCP failures never count. */
    fun ipBlocked(results: List<Pair<Int, ColituTlsProbe.Result>>): Boolean =
        results.filter { it.second == ColituTlsProbe.Result.TlsRst }.map { it.first }.toSet().size >= 2

    /**
     * Up to [MAX_ENDPOINTS] endpoints of DISTINCT ports among the TLS TCP transports, those of
     * [answeredTcp] only (null: no filter). Order of [TLS_PROTOCOLS].
     */
    fun pickEndpoints(endpoints: List<Endpoint>, answeredTcp: (Endpoint) -> Boolean = { true }): List<Endpoint> {
        val picked = LinkedHashMap<Int, Endpoint>()
        for (protocol in TLS_PROTOCOLS) {
            for (e in endpoints) {
                if (e.protocol != protocol || e.port in picked || !answeredTcp(e)) continue
                picked[e.port] = e
                if (picked.size >= MAX_ENDPOINTS) return picked.values.toList()
            }
        }
        return picked.values.toList()
    }

    /**
     * Endpoint of one rendered Xray config (outbound "proxy"): host and port from vnext / servers,
     * the SNI from the Reality or TLS settings. Null for a transport without TLS or a config that
     * cannot be read.
     */
    fun endpointOf(protocol: String?, rawConfig: String?): Endpoint? {
        if (protocol == null || protocol !in TLS_PROTOCOLS || rawConfig.isNullOrBlank()) return null
        return runCatching {
            val root = JsonParser.parseString(rawConfig).asJsonObject
            val outbound = root.getAsJsonArray("outbounds").map { it.asJsonObject }
                .first { it.get("tag")?.asString == "proxy" }
            val settings = outbound.getAsJsonObject("settings")
            val node = (settings.getAsJsonArray("vnext") ?: settings.getAsJsonArray("servers")).get(0).asJsonObject
            val stream = outbound.getAsJsonObject("streamSettings")
            val tls = stream.getAsJsonObject("realitySettings") ?: stream.getAsJsonObject("tlsSettings")
            val sni = tls?.get("serverName")?.asString.orEmpty()
            val host = node.get("address").asString.trim()
            val port = node.get("port").asInt
            if (host.isEmpty() || sni.isEmpty()) null else Endpoint(protocol, host, port, sni)
        }.getOrNull()
    }

    // ── Measurement schedule (RU only; list from GET /servers `measure_targets`) ──

    data class MeasureTarget(
        val nodeId: String,
        val endpointId: String,
        val protocol: String,
        val host: String,
        val port: Int,
        val sni: String,
    )

    data class MeasureTargets(val intervalH: Int, val targets: List<MeasureTarget>)

    /** Parses `measure_targets`; null when absent or malformed, an empty list when the panel sent none. */
    fun parseTargets(json: JsonObject?): MeasureTargets? {
        json ?: return null
        val interval = json.get("interval_h")?.takeIf { it.isJsonPrimitive }?.asDouble?.toInt()?.coerceIn(1, 168) ?: 6
        val list = (json.get("targets")?.takeIf { it.isJsonArray }?.asJsonArray?.toList() ?: emptyList()).mapNotNull { e ->
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            fun text(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            val port = o.get("port")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
            val target = MeasureTarget(text("node_id"), text("endpoint_id"), text("protocol"), text("host").lowercase(), port, text("sni"))
            target.takeIf { it.nodeId.isNotEmpty() && it.host.isNotEmpty() && ColituTlsProbe.hostAllowed(it.host, it.port) }
        }.take(MAX_TARGETS)
        return MeasureTargets(interval, list)
    }

    fun targetsToJson(t: MeasureTargets): String = JsonObject().apply {
        addProperty("interval_h", t.intervalH)
        add("targets", JsonArray().apply {
            t.targets.forEach { x ->
                add(JsonObject().apply {
                    addProperty("node_id", x.nodeId); addProperty("endpoint_id", x.endpointId)
                    addProperty("protocol", x.protocol); addProperty("host", x.host)
                    addProperty("port", x.port); addProperty("sni", x.sni)
                })
            }
        })
    }.toString()

    fun targetsFromJson(raw: String?): MeasureTargets? =
        runCatching { raw?.takeIf { it.isNotBlank() }?.let { parseTargets(JsonParser.parseString(it).asJsonObject) } }.getOrNull()

    /**
     * A measurement run is due: VPN off (not connected, not connecting), targets present, and no run
     * on this network yet or the last one at least [intervalH] hours ago ([lastRunAgeH] null: never).
     */
    fun measurementDue(vpnOn: Boolean, targets: Int, lastRunAgeH: Double?, intervalH: Int): Boolean =
        !vpnOn && targets > 0 && (lastRunAgeH == null || lastRunAgeH >= intervalH)
}
