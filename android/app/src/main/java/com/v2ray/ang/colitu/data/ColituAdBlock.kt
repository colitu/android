package com.v2ray.ang.colitu.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tencent.mmkv.MMKV

/**
 * Optional ad and tracker blocking. When it is on, every DNS lookup of the
 * device is answered by Colitu's own AdGuard Home servers over DNS-over-HTTPS
 * through the tunnel; ad and tracker domains resolve to 0.0.0.0. The servers
 * keep no query log.
 */
object ColituAdBlock {
    private const val KEY_ENABLED = "ad_block"

    private const val KEY_SERVERS = "ad_block_doh"

    /** Tag of the `dns` block [apply] writes; a config carrying it has ad blocking on. */
    const val DNS_TAG = "colitu-adblock-dns"

    /** At most this many resolvers are kept from the panel. */
    internal const val MAX_SERVERS = 8

    /**
     * Tried in order; the next one answers when one is down. They come from
     * the panel with the signed-in bootstrap ([remember]) and are kept here:
     * Colitu's own nodes, not compiled into the public app, and replaceable
     * without a release.
     */
    val dohServers: List<String> get() = cached().first

    /** False until the panel has named servers; the switch is then not shown. */
    val available: Boolean get() = dohServers.isNotEmpty()

    /** Nodes that run one of the [dohServers]; the server list tags them. */
    val dnsHosts: Set<String> get() = cached().second

    /**
     * Bumped whenever [remember] stores a different list (in this process), so
     * screens can refresh the switch and the server tags.
     */
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0)

    // The stored list parsed once per distinct value: the server list asks for
    // [dnsHosts] on every row and every redraw.
    @Volatile private var parsed: Triple<String, List<String>, Set<String>>? = null

    private fun cached(): Pair<List<String>, Set<String>> {
        val raw = store.decodeString(KEY_SERVERS).orEmpty()
        parsed?.takeIf { it.first == raw }?.let { return it.second to it.third }
        val servers = parseServers(raw)
        val hosts = hostsOf(servers)
        parsed = Triple(raw, servers, hosts)
        return servers to hosts
    }

    /**
     * Keeps the resolvers of a bootstrap answer (`ad_block.doh`). Only a
     * non-empty list replaces the stored one: an answer without the field (an
     * older panel) or with an empty list (a panel whose list is not set) never
     * turns ad blocking off, like on Windows and Linux.
     */
    fun remember(bootstrap: JsonObject) {
        val servers = serversOf(bootstrap)?.takeIf { it.isNotEmpty() } ?: return
        val value = servers.joinToString(",")
        if (store.decodeString(KEY_SERVERS) == value) return
        store.encode(KEY_SERVERS, value)
        changes.value++
    }

    /** `ad_block.doh` of a bootstrap answer (valid resolvers only), null when the answer has no such field. */
    internal fun serversOf(bootstrap: JsonObject): List<String>? {
        val list = bootstrap.get("ad_block")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("doh")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        return parseServers(list.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.joinToString(","))
    }

    /**
     * DoH URLs only (https://host/...), at most [MAX_SERVERS]. A resolver on a
     * private, loopback, link-local or other non-public IP literal is dropped:
     * the config sends local networks direct, so such a resolver would take
     * every lookup out of the tunnel to the local network (as for the panel's
     * dns_policy, [XrayPolicySanitizer.validDnsAddress]).
     */
    internal fun parseServers(value: String): List<String> =
        value.split(',').map { it.trim() }
            .filter { it.startsWith("https://") && XrayPolicySanitizer.validDnsAddress(it) }
            .distinct()
            .take(MAX_SERVERS)

    internal fun hostsOf(servers: List<String>): Set<String> =
        servers.mapNotNullTo(mutableSetOf()) { runCatching { java.net.URI(it).host?.lowercase() }.getOrNull() }

    /**
     * True when [raw] is a config built with ad blocking ([apply] ran with
     * servers). The VPN service decides the tunnel's DNS address by this, so
     * the address always matches the config the core runs, even when the
     * setting or the stored list changed after the config was built.
     */
    fun activeIn(raw: String?): Boolean = runCatching {
        if (raw.isNullOrBlank() || !raw.contains(DNS_TAG)) return false
        val dns = JsonParser.parseString(raw).asJsonObject.get("dns")?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        dns.get("tag")?.takeIf { it.isJsonPrimitive }?.asString == DNS_TAG &&
            dns.get("servers")?.takeIf { it.isJsonArray }?.asJsonArray?.size()?.let { it > 0 } == true
    }.getOrDefault(false)

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
            addProperty("tag", DNS_TAG)
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
