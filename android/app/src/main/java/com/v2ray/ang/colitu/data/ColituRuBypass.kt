package com.v2ray.ang.colitu.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tencent.mmkv.MMKV
import com.v2ray.ang.colitu.api.ColituSecureStore

/**
 * Russian destinations ALWAYS leave directly from the device, never through
 * the tunnel (owner decision 2026-11-20). Banks, Gosuslugi, marketplaces and
 * other Russian services refuse connections that arrive from a foreign IP, and
 * the nodes refuse Russian destinations anyway.
 *
 * This is independent of privacy mode, of the chosen server (also a server in
 * Russia) and of every other setting: the generated config always carries the
 * Russian direct rules, ahead of every proxy rule. Privacy mode only governs
 * the rest of the traffic.
 */
object ColituRuBypass {
    internal const val RULE_TAG = "colitu-ru-direct"

    private const val KEY_PRIVACY_MODE = "privacy_mode"
    private const val KEY_NOTICE_SHOWN = "ru_direct_notice_shown"
    private const val KEY_PROFILE_COUNTRY = "ru_direct_profile_country"
    private const val KEY_LANGUAGE = "language"
    private const val DEFAULT_PRIVACY_MODE_NEW_INSTALL = true

    // Same store as ColituController; the VPN service reads it in its own process.
    private val store by lazy { MMKV.mmkvWithID("COLITU_SETTINGS", MMKV.MULTI_PROCESS_MODE) }

    /**
     * Privacy mode: all traffic through the VPN. New installs start with it on;
     * installs that already had Colitu state before this default existed keep
     * it off (their previous behaviour). Decided once, see [resolveInitialPrivacyMode].
     */
    var privacyMode: Boolean
        get() {
            if (!store.containsKey(KEY_PRIVACY_MODE)) resolveInitialPrivacyMode()
            return store.decodeBool(KEY_PRIVACY_MODE, DEFAULT_PRIVACY_MODE_NEW_INSTALL)
        }
        set(value) {
            store.encode(KEY_PRIVACY_MODE, value)
        }

    /**
     * Stores the initial privacy mode when it was never stored (idempotent):
     * off when the install already has Colitu state, on for a fresh install.
     * Runs on the first read, which the app does at start, before anything
     * else writes to the store.
     */
    private fun resolveInitialPrivacyMode() {
        if (store.containsKey(KEY_PRIVACY_MODE)) return
        val loggedIn = runCatching { !ColituSecureStore.get("access_token").isNullOrBlank() || !ColituSecureStore.get("refresh_token").isNullOrBlank() }
            .getOrDefault(false)
        val prior = hasPriorState(store.allKeys(), loggedIn)
        store.encode(KEY_PRIVACY_MODE, initialPrivacyMode(prior))
    }

    /** New installs get privacy mode on, installs with earlier Colitu state keep it off. */
    internal fun initialPrivacyMode(hasPriorState: Boolean): Boolean = !hasPriorState

    /**
     * Whether the install predates the privacy default: a stored session, or
     * any COLITU_SETTINGS key other than the language choice and privacy mode itself.
     */
    internal fun hasPriorState(settingsKeys: Array<String>?, loggedIn: Boolean): Boolean =
        loggedIn || settingsKeys.orEmpty().any { it != KEY_PRIVACY_MODE && it != KEY_LANGUAGE }

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
     * [raw] as it should run: always with the Russian direct rules. The
     * arguments of earlier versions (server country, privacy mode) no longer
     * matter.
     */
    fun configure(raw: String): String = apply(raw)

    /** Domain matchers sent direct: the geosite list plus the suffixes the nodes refuse too. */
    internal val DIRECT_DOMAINS = listOf(
        "geosite:category-ru",
        "domain:ru", "domain:su", "domain:xn--p1ai", "domain:xn--d1acj3b", "domain:xn--80asehdb",
        "domain:moscow", "domain:tatar",
        "domain:vk.com", "domain:vk.me", "domain:vkontakte.com", "domain:userapi.com", "domain:vk-cdn.net",
        "domain:vkuser.net", "domain:mycdn.me",
        "domain:yandex.com", "domain:yandex.net", "domain:yastatic.net", "domain:yandex.by", "domain:yandex.kz",
        "domain:ya.cc", "domain:ok.me", "domain:odnoklassniki.com", "domain:tinkoff.com", "domain:tbank.com",
    )

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
            add(directRule { add("domain", JsonArray().apply { DIRECT_DOMAINS.forEach(::add) }) })
            add(directRule { add("ip", JsonArray().apply { add("geoip:ru") }) })
            existing.drop(dnsPrefix.size).forEach(::add)
        }
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
