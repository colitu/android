package com.v2ray.ang.colitu.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.colitu.api.LocalProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole runtime config as the app builds it (ColituVpnRepository: local
 * proxy, privacy rules, split tunneling, ad blocking, warm spare, check
 * inbound; then the renewal before every core start). Every rule must name
 * an outbound or balancer that exists, and traffic of the tunnel's SOCKS
 * inbound must reach `proxy` (directly or through the balancer).
 * COLITU_DUMP_CONFIGS=<dir> writes the configs there for `xray run -test`.
 */
class ColituConfigPipelineTest {
    private val now = ColituWarmSpareFixtures.now
    private val doh = listOf("https://dns.example.test/dns-query")

    private fun panelProfile(protocol: String): String {
        val envelope = ColituWarmSpareFixtures.envelope(protocol)
        val payload = envelope.getAsJsonObject("profile").getAsJsonObject("payload")
        payload.add("dns_policy", JsonParser.parseString("""{"servers":["https://dns.example.test/dns-query"]}"""))
        payload.add("routing_policy", JsonParser.parseString("""{"domainStrategy":"AsIs","rules":[{"type":"field","ip":["10.0.0.0/8"],"outboundTag":"direct"}]}"""))
        return requireNotNull(XrayMobileAdapter.render(envelope, now).rawConfig)
    }

    /** importRuntimeConfig with ad block on, privacy mode off, split tunneling off. */
    private fun import(spare: String?): String {
        val proxied = XrayMobileAdapter.withLocalProxy(panelProfile("vless-reality"), LocalProxy(20001, "u", "p"))
        val layered = ColituAdBlock.apply(
            ColituSplitTunnel.configure(ColituRuBypass.configure(proxied, "LT", privacyMode = false), ColituSplitTunnel.Settings()),
            on = true,
            servers = doh,
        )
        return XrayMobileAdapter.withVerifyInbound(ColituWarmSpare.attach(layered, spare), LocalProxy(20002, "v", "w"), LocalProxy(20005, "s", "t"))
    }

    /** renewLocalProxy before every core start. */
    private fun renew(raw: String) =
        XrayMobileAdapter.withVerifyInbound(XrayMobileAdapter.withLocalProxy(raw, LocalProxy(20003, "u2", "p2")), LocalProxy(20004, "v2", "w2"), LocalProxy(20006, "s2", "t2"))

    private fun spareProfile() = panelProfile("trojan")

    private fun check(name: String, raw: String, spare: Boolean) {
        System.getenv("COLITU_DUMP_CONFIGS")?.takeIf { it.isNotBlank() }?.let { dir ->
            java.io.File(dir).mkdirs()
            java.io.File(dir, "$name.json").writeText(raw)
        }
        val json = JsonParser.parseString(raw).asJsonObject
        val outbounds = json.getAsJsonArray("outbounds").map { it.asJsonObject.get("tag").asString }
        val routing = json.getAsJsonObject("routing")
        val balancers = routing.getAsJsonArray("balancers")?.map { it.asJsonObject } .orEmpty()
        val rules = routing.getAsJsonArray("rules").map { it.asJsonObject }
        assertEquals("$name: primary first", "proxy", outbounds.first())
        rules.forEach { rule ->
            rule.get("outboundTag")?.asString?.let { assertTrue("$name: outbound $it exists", it in outbounds) }
            rule.get("balancerTag")?.asString?.let { tag -> assertTrue("$name: balancer $tag exists", balancers.any { it.get("tag").asString == tag }) }
            assertTrue("$name: rule has a target", rule.has("outboundTag") || rule.has("balancerTag"))
        }
        balancers.forEach { b ->
            b.get("fallbackTag")?.asString?.let { assertTrue("$name: fallback $it exists", it in outbounds) }
            assertTrue("$name: selector matches only proxy", b.getAsJsonArray("selector").map { it.asString } == listOf("proxy"))
        }
        assertEquals("$name: spare", spare, "warm-spare" in outbounds)
        assertEquals("$name: observatory", spare, json.has("burstObservatory"))
        assertEquals("$name: balancer", spare, balancers.isNotEmpty())
        // The first rule is the traffic check's, straight to the primary.
        assertEquals("$name: check rule first", XrayMobileAdapter.VERIFY_TAG, rules.first().get("ruleTag").asString)
        assertEquals("proxy", rules.first().get("outboundTag").asString)
        // A tunnel TCP connection to an address no rule names reaches proxy (via the balancer when there is a spare).
        val target = firstMatch(rules, inbound = "socks", port = 80, ip = "1.1.1.1")
        if (spare) assertEquals("$name: tunnel traffic", "balancer:proxy-auto", target) else assertEquals("$name: tunnel traffic", "outbound:proxy", target)
        // DNS port 53 from the tunnel goes to the dns outbound.
        assertEquals("$name: tunnel DNS", "outbound:dns-out", firstMatch(rules, inbound = "socks", port = 53, ip = "1.1.1.1"))
        // The DNS module's DoH queries reach the primary path too.
        val dnsTag = json.getAsJsonObject("dns").get("tag").asString
        val dohTarget = firstMatch(rules, inbound = dnsTag, port = 443, ip = "203.0.113.1")
        if (spare) assertEquals("$name: DoH", "balancer:proxy-auto", dohTarget) else assertEquals("$name: DoH", "outbound:proxy", dohTarget)
        // The core-start log line names tags and targets, never a host or credential.
        val line = ColituWarmSpare.describe(raw)
        assertTrue(line.contains("colitu-verify") && line.contains("->proxy"))
        assertFalse(line.contains("example.test") || line.contains("secret") || line.contains("XfVFQ"))
        // The spare's own check inbound (only with a spare) reaches only the spare, right after the primary's rule.
        val inbounds = json.getAsJsonArray("inbounds").map { it.asJsonObject.get("tag").asString }
        assertEquals("$name: spare check inbound", spare, XrayMobileAdapter.VERIFY_SPARE_TAG in inbounds)
        if (spare) {
            assertEquals(XrayMobileAdapter.VERIFY_SPARE_TAG, rules[1].get("ruleTag").asString)
            assertEquals("outbound:warm-spare", firstMatch(rules, inbound = XrayMobileAdapter.VERIFY_SPARE_TAG, port = 80, ip = "203.0.113.1"))
        }
        // The check inbound reaches only the primary.
        assertEquals("$name: check", "outbound:proxy", firstMatch(rules, inbound = XrayMobileAdapter.VERIFY_TAG, port = 443, ip = "203.0.113.1"))
    }

    /**
     * Tiny model of Xray's rule matching for what these configs use
     * (inboundTag, port, network, ip CIDR; domain and geo rules never match
     * an IP-only request here). Unmatched -> the first outbound.
     */
    private fun firstMatch(rules: List<JsonObject>, inbound: String, port: Int, ip: String): String {
        for (rule in rules) {
            val inboundTags = rule.getAsJsonArray("inboundTag")
            if (inboundTags != null && inboundTags.none { it.asString == inbound }) continue
            rule.get("port")?.asString?.let { p -> if (p.split(',').none { portMatches(it.trim(), port) }) continue }
            rule.get("network")?.asString?.let { n -> if ("tcp" !in n.split(',')) continue }
            if (rule.has("domain")) continue
            rule.getAsJsonArray("ip")?.let { ips -> if (ips.none { cidrMatches(it.asString, ip) }) continue }
            return rule.get("balancerTag")?.let { "balancer:${it.asString}" } ?: "outbound:${rule.get("outboundTag").asString}"
        }
        return "outbound:proxy"
    }

    private fun portMatches(spec: String, port: Int): Boolean =
        if ('-' in spec) spec.substringBefore('-').toInt() <= port && port <= spec.substringAfter('-').toInt() else spec.toInt() == port

    private fun cidrMatches(cidr: String, ip: String): Boolean {
        if (cidr.startsWith("geoip:") || cidr.startsWith("ext:")) return false
        val (net, bits) = if ('/' in cidr) cidr.substringBefore('/') to cidr.substringAfter('/').toInt() else cidr to 32
        if (':' in net || ':' in ip) return false
        fun v(a: String) = a.split('.').fold(0L) { acc, part -> (acc shl 8) or part.toLong() }
        val mask = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
        return (v(net) and mask) == (v(ip) and mask)
    }

    @Test
    fun spareOn() {
        val raw = import(spareProfile())
        check("spare-on", raw, spare = true)
        check("spare-on-renewed", renew(raw), spare = true)
    }

    @Test
    fun spareOffFresh() {
        val raw = import(null)
        check("spare-off", raw, spare = false)
        check("spare-off-renewed", renew(raw), spare = false)
    }

    @Test
    fun spareOffAfterOnStoredProfile() {
        // Switched off while disconnected: the stored profile is detached, then renewed at the next start.
        val stored = ColituWarmSpare.detach(import(spareProfile()))
        check("spare-off-after-on", renew(stored), spare = false)
        assertFalse(renew(stored).contains("colitu-spare\""))
    }
}
