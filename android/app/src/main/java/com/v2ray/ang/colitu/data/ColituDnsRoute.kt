package com.v2ray.ang.colitu.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The core's own DNS lookups (the panel's dns_policy servers, the ad-blocking
 * DoH resolvers) always go through the tunnel. Without this they follow the
 * ordinary routing rules, so a plain resolver whose address a `direct` rule
 * matches (a Russian resolver while the Russian bypass is on, say) would be
 * asked outside the tunnel, in clear text, with every name the core resolves.
 *
 * A config without resolvers (the panel sent none, or none survived
 * [XrayPolicySanitizer]) gets [DEFAULT_SERVERS]: without any, Xray falls back
 * to the system resolver, and the app's own process is outside the VPN, so
 * those lookups would leave in clear text through the local network.
 *
 * The `dns` block gets a tag (its own, or [ColituWarmSpare.DNS_TAG]) and one
 * rule in front of all others (behind the traffic-check rules) sends that tag
 * to `proxy`, or with a warm spare to its balancer (the rule
 * [ColituWarmSpare.reroute] writes). Idempotent.
 */
object ColituDnsRoute {
    const val RULE_TAG = "colitu-dns-proxy"

    /** DNS-over-HTTPS by address (no lookup needed to reach them), asked through the tunnel. */
    val DEFAULT_SERVERS = listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query")

    private val checkRuleTags = setOf(XrayMobileAdapter.VERIFY_TAG, XrayMobileAdapter.VERIFY_SPARE_TAG)

    fun throughTunnel(raw: String): String = runCatching {
        val json = JsonParser.parseString(raw).asJsonObject
        // Only a core config (one with outbounds) is touched.
        if (json.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray?.size()?.let { it > 0 } != true) return raw
        val dns = json.get("dns")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also { json.add("dns", it) }
        if (dns.get("servers")?.takeIf { it.isJsonArray }?.asJsonArray?.size()?.let { it > 0 } != true) {
            dns.add("servers", JsonArray().apply { DEFAULT_SERVERS.forEach(::add) })
        }
        val routing = json.get("routing")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val existing = routing.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
        fun tagOf(rule: com.google.gson.JsonElement): String? =
            rule.takeIf { it.isJsonObject }?.asJsonObject?.get("ruleTag")?.takeIf { it.isJsonPrimitive }?.asString
        val spare = ColituWarmSpare.hasSpare(raw)
        val tag = dns.get("tag")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            ?: ColituWarmSpare.DNS_TAG.also { dns.addProperty("tag", it) }
        val ours = setOf(RULE_TAG, ColituWarmSpare.DNS_RULE_TAG)
        val rules = JsonArray()
        existing.filter { tagOf(it) in checkRuleTags }.forEach(rules::add)
        rules.add(JsonObject().apply {
            addProperty("type", "field")
            add("inboundTag", JsonArray().apply { add(tag) })
            if (spare) {
                // The same rule ColituWarmSpare.reroute writes: the balancer, so the spare carries DNS too.
                addProperty("balancerTag", ColituWarmSpare.BALANCER_TAG)
                addProperty("ruleTag", ColituWarmSpare.DNS_RULE_TAG)
            } else {
                addProperty("outboundTag", ColituWarmSpare.PRIMARY_TAG)
                addProperty("ruleTag", RULE_TAG)
            }
        })
        existing.filterNot { tagOf(it) in checkRuleTags || tagOf(it) in ours }.forEach(rules::add)
        routing.add("rules", rules)
        json.add("routing", routing)
        json.toString()
    }.getOrDefault(raw)
}
