package com.v2ray.ang.colitu.data

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URI

/**
 * The panel's `routing_policy` / `dns_policy` reduced to what the app accepts
 * from it, so a compromised panel cannot take traffic out of the tunnel or pin
 * names to its own addresses while the app shows "connected":
 *
 * - routing: `domainStrategy` (known values) and `rules`. A rule goes to
 *   `proxy`, or to `direct` only when all it matches is local networks
 *   (`geoip:private`, `geosite:private`, private/loopback/link-local/CGNAT
 *   CIDRs) or Russian lists/domains (removed again by [ColituRuBypass.strip]
 *   when the user's Russian bypass does not apply). Any other target
 *   (`block`, unknown tags, `balancerTag`) drops the rule. `balancers` and every
 *   other routing key are dropped.
 * - dns: `servers` that are DoH (`https://`) URLs or IP literals, and
 *   `queryStrategy`. `hosts` and every other key are dropped.
 *
 * The app's own layers (RU bypass, split tunnel, ad block, warm spare) add
 * their rules after this, on the app's side.
 */
internal object XrayPolicySanitizer {
    private const val MAX_RULES = 256
    private const val MAX_DNS_SERVERS = 16
    private const val MAX_URL_LENGTH = 512

    private val DOMAIN_STRATEGIES = setOf("AsIs", "IPIfNonMatch", "IPOnDemand")
    private val QUERY_STRATEGIES = setOf("UseIP", "UseIPv4", "UseIPv6")
    private val RULE_KEYS = listOf(
        "type", "domain", "ip", "port", "sourcePort", "network", "source", "user",
        "inboundTag", "protocol", "attrs", "outboundTag", "ruleTag",
    )
    private val LIST_KEYS = setOf("domain", "ip", "source", "user", "inboundTag", "protocol")

    fun routing(policy: JsonObject?): JsonObject {
        val out = JsonObject()
        if (policy == null) return out
        policy.str("domainStrategy")?.takeIf { it in DOMAIN_STRATEGIES }?.let { out.addProperty("domainStrategy", it) }
        policy.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray?.let { rules ->
            val kept = JsonArray()
            rules.take(MAX_RULES).forEach { element -> rule(element)?.let(kept::add) }
            out.add("rules", kept)
        }
        return out
    }

    fun dns(policy: JsonObject?): JsonObject {
        val out = JsonObject()
        if (policy == null) return out
        policy.get("servers")?.takeIf { it.isJsonArray }?.asJsonArray?.let { servers ->
            val kept = JsonArray()
            servers.take(MAX_DNS_SERVERS).forEach { element -> dnsServer(element)?.let(kept::add) }
            out.add("servers", kept)
        }
        policy.str("queryStrategy")?.takeIf { it in QUERY_STRATEGIES }?.let { out.addProperty("queryStrategy", it) }
        return out
    }

    private fun rule(element: JsonElement): JsonObject? {
        val rule = element.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val type = rule.get("type")
        if (type != null && rule.str("type") != "field") return null
        if (rule.has("balancerTag")) return null
        for (key in LIST_KEYS) {
            val value = rule.get(key) ?: continue
            if (!value.isJsonArray || value.asJsonArray.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isString }) return null
        }
        when (rule.str("outboundTag")) {
            "proxy" -> Unit
            "direct" -> if (!onlyLocalOrRussian(rule)) return null
            else -> return null
        }
        return JsonObject().apply { RULE_KEYS.forEach { key -> rule.get(key)?.let { add(key, it.deepCopy()) } } }
    }

    /** True when the rule matches by domain and/or IP and every entry is a local network or Russian. */
    private fun onlyLocalOrRussian(rule: JsonObject): Boolean {
        val domains = rule.strings("domain")
        val ips = rule.strings("ip")
        if (domains.isEmpty() && ips.isEmpty()) return false
        val domainsOk = domains.all { d ->
            val v = d.trim().lowercase()
            // Only an explicitly scoped matcher may send a domain past the
            // tunnel. A bare value ("ru", ".ru") is a substring/keyword match in
            // Xray (".ru" would also match "rust-lang.org"), so prefix-less,
            // "keyword:" and "regexp:" entries are refused even when they look
            // Russian; the matcher must be geosite:, domain: or full:.
            (v == "geosite:private" || v.startsWith("geosite:") || v.startsWith("domain:") || v.startsWith("full:")) &&
                (v == "geosite:private" || ColituRuBypass.isRussian("domain", v))
        }
        val ipsOk = ips.all { ip ->
            ip.trim().lowercase() == "geoip:private" || ColituAddressRanges.isNonPublicCidr(ip) || ColituRuBypass.isRussian("ip", ip)
        }
        return domainsOk && ipsOk
    }

    private fun dnsServer(element: JsonElement): JsonElement? {
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            return element.asString.takeIf(::validDnsAddress)?.let { element.deepCopy() }
        }
        val server = element.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val address = server.str("address")?.takeIf(::validDnsAddress) ?: return null
        return JsonObject().apply {
            addProperty("address", address)
            server.get("port")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.let { runCatching { it.asInt }.getOrNull() }?.takeIf { it in 1..65535 }
                ?.let { addProperty("port", it) }
            server.get("domains")?.takeIf { d -> d.isJsonArray && d.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString } }
                ?.let { add("domains", it.deepCopy()) }
            server.get("skipFallback")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                ?.let { add("skipFallback", it.deepCopy()) }
        }
    }

    /** A DoH URL (https://host/...) or an IP literal; "localhost", "https+local://", tcp/udp URLs and names are refused. */
    internal fun validDnsAddress(value: String): Boolean {
        if (value.isBlank() || value.length > MAX_URL_LENGTH || value.any { it.isWhitespace() }) return false
        // A resolver on a private, loopback, link-local or other non-public
        // address would send the tunnel's name lookups out to the local
        // network instead of through the proxy, so such literals are refused.
        ColituAddressRanges.parseLiteral(value)?.let { return !ColituAddressRanges.isNonPublic(it) }
        if (!value.startsWith("https://")) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return false
        // A DoH endpoint on a non-public IP literal is the same local-network leak.
        ColituAddressRanges.parseLiteral(uri.host.trim('[', ']'))?.let { if (ColituAddressRanges.isNonPublic(it)) return false }
        return true
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.strings(key: String): List<String> =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()
}
