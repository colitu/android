package com.v2ray.ang.colitu.data

import android.content.pm.PackageManager
import android.net.VpnService
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tencent.mmkv.MMKV
import java.net.IDN

/**
 * User split tunneling. Two layers:
 *
 * - Apps: Android's per-app VPN ([VpnService.Builder.addDisallowedApplication]
 *   / [VpnService.Builder.addAllowedApplication]); an app outside the VPN never
 *   enters the TUN interface.
 * - Sites (domain suffixes) and IP addresses/CIDR ranges: Xray routing rules
 *   (tag [RULE_TAG]) inside the tunnel; "outside the VPN" means Xray's
 *   `direct` outbound, which leaves from Colitu's own (protected) socket.
 *
 * Modes:
 * - [Mode.Off]: everything through the VPN (Colitu itself always stays out,
 *   its server connections must not loop).
 * - [Mode.Bypass]: the selected apps, sites and addresses go outside the VPN.
 * - [Mode.Only]: only the selected apps use the VPN (all of their traffic).
 *   With no app selected, every app is in the tunnel but only the selected
 *   sites and addresses go through the VPN; everything else leaves directly.
 *   (Android cannot route "these apps plus these sites in any app" at once:
 *   an app outside the VPN never reaches the routing rules.)
 *
 * Privacy mode (ColituRuBypass) is independent: its Russian direct rules are
 * added or removed on their own.
 */
object ColituSplitTunnel {
    internal const val RULE_TAG = "colitu-split"

    enum class Mode(val key: String) {
        Off("off"), Bypass("bypass"), Only("only");

        companion object {
            fun of(value: String?): Mode = entries.firstOrNull { it.key == value } ?: Off
        }
    }

    data class Settings(
        val mode: Mode = Mode.Off,
        val apps: Set<String> = emptySet(),
        val domains: List<String> = emptyList(),
        val ips: List<String> = emptyList(),
    ) {
        val active: Boolean get() = mode != Mode.Off && (apps.isNotEmpty() || domains.isNotEmpty() || ips.isNotEmpty())
        val count: Int get() = apps.size + domains.size + ips.size
        /** In Only mode the site rules only work when no app is selected (see the object doc). */
        val sitesApply: Boolean get() = mode == Mode.Bypass || (mode == Mode.Only && apps.isEmpty())
    }

    private const val KEY_MODE = "split_mode"
    private const val KEY_APPS = "split_apps"
    private const val KEY_DOMAINS = "split_domains"
    private const val KEY_IPS = "split_ips"
    const val MAX_ENTRIES = 200

    // Same store as ColituController; the VPN service reads it in its own process.
    private val store by lazy { MMKV.mmkvWithID("COLITU_SETTINGS", MMKV.MULTI_PROCESS_MODE) }

    var settings: Settings
        get() = Settings(
            mode = Mode.of(store.decodeString(KEY_MODE)),
            apps = store.decodeStringSet(KEY_APPS).orEmpty().toSet(),
            domains = lines(store.decodeString(KEY_DOMAINS)),
            ips = lines(store.decodeString(KEY_IPS)),
        )
        set(value) {
            store.encode(KEY_MODE, value.mode.key)
            store.encode(KEY_APPS, value.apps.toMutableSet())
            store.encode(KEY_DOMAINS, value.domains.joinToString("\n"))
            store.encode(KEY_IPS, value.ips.joinToString("\n"))
        }

    private fun lines(value: String?) = value.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    // ── Input validation ───────────────────────────────────────────────────

    private val labelPattern = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

    /**
     * A domain suffix as typed or pasted ("https://www.Example.com/path",
     * "*.example.com", "пример.рф") in Xray's `domain:` form without the
     * prefix: lower case, punycode, no scheme, path, port or wildcard. Null
     * when it is not a host name with at least two labels.
     */
    fun normalizeDomain(input: String): String? {
        var v = input.trim().lowercase()
        v = v.substringAfter("://")
        v = v.substringBefore('/').substringBefore('?').substringBefore('#')
        v = v.substringAfterLast('@')
        if (v.count { it == ':' } == 1) v = v.substringBefore(':')
        // "www.example.com" means the site: match the whole domain.
        v = v.removePrefix("*.").removePrefix(".").trimEnd('.').removePrefix("www.")
        if (v.isEmpty() || v.length > 253) return null
        val ascii = runCatching { IDN.toASCII(v, IDN.USE_STD3_ASCII_RULES) }.getOrNull()?.lowercase() ?: return null
        val labels = ascii.split('.')
        if (labels.size < 2 || labels.any { !labelPattern.matches(it) }) return null
        // A bare IP address belongs in the address list.
        if (labels.all { l -> l.all(Char::isDigit) }) return null
        return ascii
    }

    /**
     * An IPv4/IPv6 address or CIDR range in canonical text, or null. Parsed
     * without DNS: only literals are accepted.
     */
    fun normalizeIp(input: String): String? {
        val v = input.trim()
        if (v.isEmpty() || v.length > 64) return null
        val address = v.substringBefore('/')
        val prefix = if ('/' in v) v.substringAfter('/').toIntOrNull() ?: return null else null
        val v4 = parseIpv4(address)
        if (v4 != null) {
            if (prefix != null && prefix !in 0..32) return null
            return if (prefix == null) v4 else "$v4/$prefix"
        }
        if (':' !in address || !address.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return null
        val v6 = runCatching { java.net.InetAddress.getByName(address) }.getOrNull() as? java.net.Inet6Address ?: return null
        if (prefix != null && prefix !in 0..128) return null
        val text = v6.hostAddress?.substringBefore('%')?.lowercase() ?: return null
        return if (prefix == null) text else "$text/$prefix"
    }

    private fun parseIpv4(value: String): String? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        val numbers = parts.map { p -> if (p.isEmpty() || p.length > 3 || !p.all(Char::isDigit)) return null else p.toInt() }
        if (numbers.any { it > 255 }) return null
        return numbers.joinToString(".")
    }

    // ── VPN interface ──────────────────────────────────────────────────────

    /**
     * Which packages the VPN interface leaves out ([disallowed]) or is limited
     * to ([allowed]); exactly one is non-null. Colitu itself is always outside:
     * disallowed in Off/Bypass, simply not allowed in Only.
     */
    data class AppScope(val disallowed: Set<String>?, val allowed: Set<String>?)

    fun appScope(settings: Settings, self: String, installed: (String) -> Boolean): AppScope {
        val apps = settings.apps.filter { it != self && installed(it) }.toSet()
        return when {
            settings.mode == Mode.Bypass -> AppScope(disallowed = apps + self, allowed = null)
            settings.mode == Mode.Only && apps.isNotEmpty() -> AppScope(disallowed = null, allowed = apps)
            else -> AppScope(disallowed = setOf(self), allowed = null)
        }
    }

    /**
     * Applies the app part to [builder]. Returns false when split tunneling
     * is off, so the caller keeps its default (only Colitu outside).
     */
    fun applyTo(builder: VpnService.Builder, self: String, pm: PackageManager? = null): Boolean {
        val current = settings
        if (!current.active) return false
        val scope = appScope(current, self) { pkg ->
            pm == null || runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess
        }
        scope.allowed?.forEach { runCatching { builder.addAllowedApplication(it) } }
        scope.disallowed?.forEach { runCatching { builder.addDisallowedApplication(it) } }
        return true
    }

    // ── Xray routing ───────────────────────────────────────────────────────

    /**
     * [raw] with the site and address rules for [settings]; every rule this
     * object added before is removed first, so the result is the same however
     * often it runs. Off, or no sites in effect: no rule at all.
     */
    fun configure(raw: String, settings: Settings = this.settings): String {
        val json = JsonParser.parseString(raw).asJsonObject
        val routing = json.get("routing")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val existing = routing.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.filterNot { it.isJsonObject && it.asJsonObject.get("ruleTag")?.takeIf { t -> t.isJsonPrimitive }?.asString == RULE_TAG }
            .orEmpty()
        val sites = settings.active && settings.sitesApply && (settings.domains.isNotEmpty() || settings.ips.isNotEmpty())
        if (!sites) {
            if (routing.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray?.size() == existing.size) return raw
            routing.add("rules", JsonArray().apply { existing.forEach(::add) })
            json.add("routing", routing)
            return json.toString()
        }
        ensureDirect(json)
        ensureSniffing(json)
        val target = if (settings.mode == Mode.Bypass) "direct" else "proxy"
        // DNS hijack rules (ad blocking) stay in front, like the Russian rules.
        val dnsPrefix = existing.takeWhile { it.isJsonObject && it.asJsonObject.get("outboundTag")?.asString == "dns-out" }
        val rules = JsonArray().apply {
            dnsPrefix.forEach(::add)
            if (settings.domains.isNotEmpty()) add(rule(target) { add("domain", JsonArray().apply { settings.domains.forEach { add("domain:$it") } }) })
            if (settings.ips.isNotEmpty()) add(rule(target) { add("ip", JsonArray().apply { settings.ips.forEach(::add) }) })
            existing.drop(dnsPrefix.size).forEach(::add)
            // Only mode: what no rule sent through the VPN leaves directly.
            if (settings.mode == Mode.Only) add(rule("direct") { addProperty("network", "tcp,udp") })
        }
        routing.add("rules", rules)
        json.add("routing", routing)
        return json.toString()
    }

    private fun ensureDirect(json: JsonObject) {
        val outbounds = json.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        val hasDirect = outbounds.any {
            it.isJsonObject && it.asJsonObject.get("tag")?.asString == "direct" && it.asJsonObject.get("protocol")?.asString == "freedom"
        }
        if (!hasDirect) {
            outbounds.add(JsonObject().apply {
                addProperty("tag", "direct")
                addProperty("protocol", "freedom")
            })
        }
        json.add("outbounds", outbounds)
    }

    /** Domain rules need the host name: sniffed for routing only, like ColituRuBypass. */
    private fun ensureSniffing(json: JsonObject) {
        json.get("inbounds")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { element ->
            val inbound = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            if (inbound.get("protocol")?.asString != "socks" || inbound.has("sniffing")) return@forEach
            inbound.add("sniffing", JsonObject().apply {
                addProperty("enabled", true)
                add("destOverride", JsonArray().apply { add("http"); add("tls"); add("quic") })
                addProperty("routeOnly", true)
            })
        }
    }

    private fun rule(outbound: String, match: JsonObject.() -> Unit) = JsonObject().apply {
        addProperty("type", "field")
        match()
        addProperty("outboundTag", outbound)
        addProperty("ruleTag", RULE_TAG)
    }
}
