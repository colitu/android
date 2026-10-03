package com.v2ray.ang.colitu.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Russian sites and apps go out directly instead of through the tunnel, as on
 * iOS. Banks, Gosuslugi, marketplaces and other Russian services refuse
 * connections that arrive from a foreign IP ("turn off your VPN"); with this
 * they see the device's own Russian address while everything else stays
 * behind the VPN.
 */
object ColituRuBypass {
    internal const val RULE_TAG = "colitu-ru-direct"

    /**
     * Through a server in Russia the Russian sites already see a Russian
     * address, and someone abroad picks that server for exactly those sites:
     * they stay in the tunnel.
     */
    fun appliesTo(serverCountry: String?): Boolean = !serverCountry.equals("RU", ignoreCase = true)

    /**
     * [raw] with Russian domains (`geosite:category-ru`) and Russian IPs
     * (`geoip:ru`) routed to the `direct` outbound. The local SOCKS inbound
     * sniffs TLS/HTTP/QUIC host names for routing only, so a Russian service
     * matches by name even when its address resolved outside Russia; the
     * connection itself still goes to the original IP.
     */
    fun apply(raw: String): String {
        val json = JsonParser.parseString(raw).asJsonObject

        val outbounds = json.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        val hasDirect = outbounds.any {
            it.isJsonObject && it.asJsonObject.get("tag")?.asString == "direct" &&
                it.asJsonObject.get("protocol")?.asString == "freedom"
        }
        if (!hasDirect) {
            outbounds.add(JsonObject().apply {
                addProperty("tag", "direct")
                addProperty("protocol", "freedom")
            })
        }
        json.add("outbounds", outbounds)

        json.get("inbounds")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { element ->
            val inbound = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            if (inbound.get("protocol")?.asString != "socks" || inbound.has("sniffing")) return@forEach
            inbound.add("sniffing", JsonObject().apply {
                addProperty("enabled", true)
                add("destOverride", JsonArray().apply { add("http"); add("tls"); add("quic") })
                addProperty("routeOnly", true)
            })
        }

        val routing = json.get("routing")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val existing = routing.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.filterNot { it.isJsonObject && it.asJsonObject.get("ruleTag")?.asString == RULE_TAG }
            .orEmpty()
        // DNS hijack rules (ad blocking) stay in front, like on iOS.
        val dnsPrefix = existing.takeWhile { it.isJsonObject && it.asJsonObject.get("outboundTag")?.asString == "dns-out" }
        val rules = JsonArray().apply {
            dnsPrefix.forEach(::add)
            add(directRule { add("domain", JsonArray().apply { add("geosite:category-ru") }) })
            add(directRule { add("ip", JsonArray().apply { add("geoip:ru") }) })
            existing.drop(dnsPrefix.size).forEach(::add)
        }
        routing.add("rules", rules)
        json.add("routing", routing)
        return json.toString()
    }

    private fun directRule(match: JsonObject.() -> Unit) = JsonObject().apply {
        addProperty("type", "field")
        match()
        addProperty("outboundTag", "direct")
        addProperty("ruleTag", RULE_TAG)
    }
}
