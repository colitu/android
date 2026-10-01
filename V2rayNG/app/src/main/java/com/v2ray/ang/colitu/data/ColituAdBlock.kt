package com.v2ray.ang.colitu.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tencent.mmkv.MMKV
import com.v2ray.ang.BuildConfig

/**
 * Optional ad and tracker blocking. When it is on, every DNS lookup of the
 * device is answered by Colitu's own AdGuard Home servers over DNS-over-HTTPS
 * through the tunnel; ad and tracker domains resolve to 0.0.0.0. The servers
 * keep no query log.
 */
object ColituAdBlock {
    private const val KEY_ENABLED = "ad_block"

    /**
     * Tried in order; the next one answers when one is down. Given at build
     * time (ADBLOCK_DOH in signing.properties): they are Colitu's own nodes
     * and stay out of the public source.
     */
    val dohServers: List<String> = parseServers(BuildConfig.COLITU_ADBLOCK_DOH)

    /** False in builds without servers; the switch is then not shown. */
    val available: Boolean get() = dohServers.isNotEmpty()

    /** Nodes that run one of the [dohServers]; the server list tags them. */
    val dnsHosts: Set<String> = hostsOf(dohServers)

    internal fun parseServers(value: String): List<String> =
        value.split(',').map { it.trim() }.filter { it.startsWith("https://") }

    internal fun hostsOf(servers: List<String>): Set<String> =
        servers.mapNotNullTo(mutableSetOf()) { runCatching { java.net.URI(it).host?.lowercase() }.getOrNull() }

    // Same store as ColituController; the VPN service reads it in its own process.
    private val store by lazy { MMKV.mmkvWithID("COLITU_SETTINGS", MMKV.MULTI_PROCESS_MODE) }

    var enabled: Boolean
        get() = available && store.decodeBool(KEY_ENABLED, false)
        set(value) {
            store.encode(KEY_ENABLED, value)
        }

    /**
     * [raw] with Xray answering DNS itself: port-53 traffic from the tunnel
     * goes to a `dns` outbound, and Xray resolves through [dohServers] over the
     * proxy. Returns [raw] unchanged when blocking is off.
     */
    fun apply(raw: String, on: Boolean = enabled, servers: List<String> = dohServers): String {
        if (!on || servers.isEmpty()) return raw
        val json = JsonParser.parseString(raw).asJsonObject
        json.add("dns", JsonObject().apply {
            add("servers", JsonArray().apply { servers.forEach(::add) })
            addProperty("queryStrategy", "UseIP")
            addProperty("tag", "colitu-adblock-dns")
        })
        val outbounds = json.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        val hasDnsOut = outbounds.any { it.isJsonObject && it.asJsonObject.get("tag")?.asString == "dns-out" }
        if (!hasDnsOut) {
            outbounds.add(JsonObject().apply {
                addProperty("tag", "dns-out")
                addProperty("protocol", "dns")
            })
        }
        json.add("outbounds", outbounds)
        val routing = json.get("routing")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val rules = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "field")
                addProperty("port", "53")
                addProperty("outboundTag", "dns-out")
            })
            routing.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.filterNot { it.isJsonObject && it.asJsonObject.get("outboundTag")?.asString == "dns-out" }
                ?.forEach(::add)
        }
        routing.add("rules", rules)
        json.add("routing", routing)
        return json.toString()
    }
}
