package com.v2ray.ang.colitu.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tencent.mmkv.MMKV

/**
 * Russian sites and apps go out directly instead of through the tunnel, as on
 * iOS. Banks, Gosuslugi, marketplaces and other Russian services refuse
 * connections that arrive from a foreign IP ("turn off your VPN"); with this
 * they see the device's own Russian address while everything else stays
 * behind the VPN.
 *
 * Privacy mode turns this off: then the generated config has no Russian
 * direct rule at all and every connection goes through the VPN.
 */
object ColituRuBypass {
    internal const val RULE_TAG = "colitu-ru-direct"

    private const val KEY_PRIVACY_MODE = "privacy_mode"
    private const val KEY_NOTICE_SHOWN = "ru_direct_notice_shown"
    private const val KEY_PROFILE_COUNTRY = "ru_direct_profile_country"

    // Same store as ColituController; the VPN service reads it in its own process.
    private val store by lazy { MMKV.mmkvWithID("COLITU_SETTINGS", MMKV.MULTI_PROCESS_MODE) }

    /** Privacy mode: all traffic through the VPN. Off by default (today's behaviour). */
    var privacyMode: Boolean
        get() = store.decodeBool(KEY_PRIVACY_MODE, false)
        set(value) {
            store.encode(KEY_PRIVACY_MODE, value)
        }

    /** The one-time notice about the direct rule was answered (absent = not yet). */
    var noticeShown: Boolean
        get() = store.decodeBool(KEY_NOTICE_SHOWN, false)
        set(value) {
            store.encode(KEY_NOTICE_SHOWN, value)
        }

    /**
     * Server country the stored Colitu profile was built for: null when it was
     * imported before this was recorded, "" when the panel sent no country.
     */
    var profileCountry: String?
        get() = store.decodeString(KEY_PROFILE_COUNTRY)
        set(value) {
            if (value == null) store.removeValueForKey(KEY_PROFILE_COUNTRY) else store.encode(KEY_PROFILE_COUNTRY, value)
        }

    /**
     * The one rule for the config and the screens alike: Russian sites go
     * direct only while privacy mode is off and the server is outside Russia.
     * Through a server in Russia the Russian sites already see a Russian
     * address, and someone abroad picks that server for exactly those sites:
     * they stay in the tunnel.
     */
    fun applies(serverCountry: String?, privacyMode: Boolean): Boolean =
        !privacyMode && !serverCountry.equals("RU", ignoreCase = true)

    /**
     * [raw] as it should run for [serverCountry]: with the Russian direct
     * rules when they [applies], otherwise with none left ([strip]).
     */
    fun configure(raw: String, serverCountry: String?, privacyMode: Boolean): String =
        if (applies(serverCountry, privacyMode)) apply(raw) else strip(raw)

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

    /**
     * [raw] without any rule that sends Russian traffic to the `direct`
     * outbound: the rules [apply] added and, should the panel's routing
     * policy ever carry one, Russian geosite/geoip/TLD entries of its direct
     * rules. A rule whose only domains or IPs were Russian is dropped whole,
     * since keeping it without them would widen it to all traffic.
     */
    fun strip(raw: String): String {
        val json = JsonParser.parseString(raw).asJsonObject
        val routing = json.get("routing")?.takeIf { it.isJsonObject }?.asJsonObject ?: return raw
        val existing = routing.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray ?: return raw
        var changed = false
        val rules = JsonArray()
        for (element in existing) {
            val rule = element.takeIf { it.isJsonObject }?.asJsonObject
            if (rule == null) {
                rules.add(element)
                continue
            }
            if (rule.get("ruleTag")?.takeIf { it.isJsonPrimitive }?.asString == RULE_TAG) {
                changed = true
                continue
            }
            if (rule.get("outboundTag")?.takeIf { it.isJsonPrimitive }?.asString != "direct") {
                rules.add(rule)
                continue
            }
            var emptied = false
            for (field in listOf("domain", "ip")) {
                val values = rule.get(field)?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                val kept = values.filterNot { it.isJsonPrimitive && isRussian(field, it.asString) }
                if (kept.size == values.size()) continue
                changed = true
                if (kept.isEmpty()) emptied = true
                rule.add(field, JsonArray().apply { kept.forEach(::add) })
            }
            if (!emptied) rules.add(rule)
        }
        if (!changed) return raw
        routing.add("rules", rules)
        json.add("routing", routing)
        return json.toString()
    }

    /** Russian TLDs that a domain rule may name directly. */
    private val russianTlds = setOf("ru", "su", "xn--p1ai", "рф")

    /**
     * A Russian matcher: `geoip:ru`, a geosite list named `ru` or `*-ru`
     * (category-ru, tld-ru, category-gov-ru, ...; also with `@attr` or from an
     * `ext:` file) or a domain under a Russian TLD (`domain:ru`,
     * `domain:example.ru`, `full:...`).
     */
    internal fun isRussian(field: String, value: String): Boolean {
        val v = value.trim().lowercase()
        fun listName(prefix: String): String? = when {
            v.startsWith("$prefix:") -> v.removePrefix("$prefix:")
            v.startsWith("ext:") -> v.substringAfterLast(':')
            else -> null
        }?.substringBefore('@')
        if (field == "ip") return listName("geoip") == "ru"
        listName("geosite")?.let { return it == "ru" || it.endsWith("-ru") }
        val host = when {
            v.startsWith("domain:") -> v.removePrefix("domain:")
            v.startsWith("full:") -> v.removePrefix("full:")
            v.startsWith("regexp:") || v.startsWith("keyword:") -> return false
            else -> v
        }
        return host.trimEnd('.').substringAfterLast('.') in russianTlds
    }

    private fun directRule(match: JsonObject.() -> Unit) = JsonObject().apply {
        addProperty("type", "field")
        match()
        addProperty("outboundTag", "direct")
        addProperty("ruleTag", RULE_TAG)
    }
}
