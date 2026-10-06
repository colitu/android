package com.v2ray.ang.colitu.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** One end of a multihop route, or the exit a rotation currently uses. */
data class ColituRouteEndpoint(
    val nodeId: String?,
    val name: String?,
    /** Upper-case ISO country code; null when the panel sent none. */
    val country: String?,
    val city: String?,
) {
    /** City, else node name, else country code: how the end is named in the UI. */
    val label: String get() = city ?: name ?: country.orEmpty()

    companion object {
        /** An endpoint object; a bare string is read as the node name, anything else is nothing. */
        fun fromJson(element: JsonElement?): ColituRouteEndpoint? {
            if (element == null || element.isJsonNull) return null
            if (element.isJsonPrimitive) {
                val name = element.asString.trim().takeIf { it.isNotEmpty() } ?: return null
                return ColituRouteEndpoint(null, name, null, null)
            }
            if (!element.isJsonObject) return null
            val json = element.asJsonObject
            return ColituRouteEndpoint(
                nodeId = json.text("node_id"),
                name = json.text("name"),
                country = json.text("country")?.uppercase()?.takeIf { it.length == 2 },
                city = json.text("city"),
            )
        }
    }
}

/** The two ends of a multihop (double VPN) route: traffic enters at [entry] and leaves at [exit]. */
data class ColituRoute(
    val slug: String?,
    val entry: ColituRouteEndpoint,
    val exit: ColituRouteEndpoint,
)

/** Multihop routes: list parsing and the VLESS-only rule (panel contract 2026-10-06, section 3). */
object ColituMultihop {
    fun isVless(protocol: String?): Boolean = protocol == "vless-reality" || protocol == "vless-xhttp"

    /**
     * A multihop route and a rotating exit run on VLESS only: the other
     * transports cannot be carried through the mesh. The result may be empty,
     * which the caller reports as "needs VLESS".
     */
    fun restrictToVless(configs: List<ColituVpnConfig>): List<ColituVpnConfig> =
        configs.filter { isVless(it.protocolType) }

    /**
     * The `multihop` array of GET /servers (or `servers` of GET /multihop/servers),
     * one list item per usable route. An item without an id, without both ends,
     * offline, or without a VLESS transport is left out.
     */
    fun parseRoutes(json: JsonObject, key: String = "multihop"): List<ColituServer> =
        json.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.let(::parseRoute) }
            .orEmpty()

    fun parseRoute(json: JsonObject): ColituServer? {
        val id = json.text("id") ?: return null
        if (json.get("multihop")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } == false) return null
        val entry = json.get("entry")?.takeIf { it.isJsonObject }?.let { ColituRouteEndpoint.fromJson(it) } ?: return null
        val exit = json.get("exit")?.takeIf { it.isJsonObject }?.let { ColituRouteEndpoint.fromJson(it) } ?: return null
        val status = json.text("status")
        if (status != null && !status.equals("online", true)) return null
        val offered = json.get("protocols")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { v -> v.takeIf { it.isJsonPrimitive }?.asString }
            .orEmpty()
        val protocols = offered.filter { isVless(it) }
        if (offered.isNotEmpty() && protocols.isEmpty()) return null
        val country = exit.country ?: json.text("country")?.uppercase()?.takeIf { it.length == 2 }
        return ColituServer(
            id = id,
            displayName = json.text("name") ?: "${entry.label} → ${exit.label}",
            countryCode = country,
            city = json.text("city") ?: exit.city,
            isRecommended = false,
            isAvailable = true,
            load = json.text("load")?.lowercase()?.takeIf { it in setOf("low", "medium", "high") },
            protocols = protocols,
            // The ping is measured to the entry node only (latency_note "entry_only_estimate").
            latencyHost = json.text("latency_host"),
            latencyPort = json.text("latency_port")?.toIntOrNull(),
            route = ColituRoute(json.text("route_slug"), entry, exit),
        )
    }
}

internal fun JsonObject.text(key: String): String? =
    if (has(key) && !get(key).isJsonNull && get(key).isJsonPrimitive)
        runCatching { get(key).asString.trim().takeIf { it.isNotEmpty() } }.getOrNull()
    else null
