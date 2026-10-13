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
 * The `dns` block gets a tag (its own, or [ColituWarmSpare.DNS_TAG]) and one
 * rule in front of all others (behind the traffic-check rules) sends that tag
 * to `proxy`. With a warm spare [ColituWarmSpare.reroute] already sends it to
 * the balancer, so nothing is added then. Idempotent.
 */
object ColituDnsRoute {
    const val RULE_TAG = "colitu-dns-proxy"

    private val checkRuleTags = setOf(XrayMobileAdapter.VERIFY_TAG, XrayMobileAdapter.VERIFY_SPARE_TAG)

    fun throughTunnel(raw: String): String = runCatching {
        val json = JsonParser.parseString(raw).asJsonObject
        val dns = json.get("dns")?.takeIf { it.isJsonObject }?.asJsonObject ?: return raw
        if (dns.get("servers")?.takeIf { it.isJsonArray }?.asJsonArray?.size()?.let { it > 0 } != true) return raw
        val routing = json.get("routing")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val existing = routing.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
        fun tagOf(rule: com.google.gson.JsonElement): String? =
            rule.takeIf { it.isJsonObject }?.asJsonObject?.get("ruleTag")?.takeIf { it.isJsonPrimitive }?.asString
        val spareRuled = existing.any { tagOf(it) == ColituWarmSpare.DNS_RULE_TAG }
        if (spareRuled && ColituWarmSpare.hasSpare(raw)) {
            // The balancer rule covers it; a leftover of ours would only duplicate it.
            if (existing.none { tagOf(it) == RULE_TAG }) return raw
        }
        val tag = dns.get("tag")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            ?: ColituWarmSpare.DNS_TAG.also { dns.addProperty("tag", it) }
        val rules = JsonArray()
        existing.filter { tagOf(it) in checkRuleTags }.forEach(rules::add)
        if (!(spareRuled && ColituWarmSpare.hasSpare(raw))) {
            rules.add(JsonObject().apply {
                addProperty("type", "field")
                add("inboundTag", JsonArray().apply { add(tag) })
                addProperty("outboundTag", ColituWarmSpare.PRIMARY_TAG)
                addProperty("ruleTag", RULE_TAG)
            })
        }
        existing.filterNot { tagOf(it) in checkRuleTags || tagOf(it) == RULE_TAG }.forEach(rules::add)
        routing.add("rules", rules)
        json.add("routing", routing)
        json.toString()
    }.getOrDefault(raw)
}
