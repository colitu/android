package com.v2ray.ang.colitu.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.colitu.data.ColituSplitTunnel.Mode
import com.v2ray.ang.colitu.data.ColituSplitTunnel.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituSplitTunnelTest {
    private val raw = """
        {"inbounds":[{"tag":"socks","protocol":"socks","port":10808}],
         "outbounds":[{"tag":"proxy","protocol":"vless"}],
         "routing":{"rules":[{"type":"field","port":"53","outboundTag":"dns-out"},
                             {"type":"field","domain":["geosite:category-ru"],"outboundTag":"direct","ruleTag":"colitu-ru-direct"}]}}
    """.trimIndent()

    private fun rules(json: String) = JsonParser.parseString(json).asJsonObject.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
    private fun split(rules: List<JsonObject>) = rules.filter { it.get("ruleTag")?.asString == ColituSplitTunnel.RULE_TAG }

    @Test
    fun domainsAreNormalizedToSuffixes() {
        assertEquals("example.com", ColituSplitTunnel.normalizeDomain("https://www.Example.com/path?q=1"))
        assertEquals("api.example.com", ColituSplitTunnel.normalizeDomain("api.example.com"))
        assertEquals("example.com", ColituSplitTunnel.normalizeDomain("*.example.com"))
        assertEquals("example.com", ColituSplitTunnel.normalizeDomain(".example.com."))
        assertEquals("example.com", ColituSplitTunnel.normalizeDomain("example.com:443"))
        assertEquals("xn--e1afmkfd.xn--p1ai", ColituSplitTunnel.normalizeDomain("пример.рф"))
        assertNull(ColituSplitTunnel.normalizeDomain("localhost"))
        assertNull(ColituSplitTunnel.normalizeDomain("exa mple.com"))
        assertNull(ColituSplitTunnel.normalizeDomain("-bad.com"))
        assertNull(ColituSplitTunnel.normalizeDomain("1.2.3.4"))
        assertNull(ColituSplitTunnel.normalizeDomain("geosite:category-ru"))
        assertNull(ColituSplitTunnel.normalizeDomain(""))
    }

    @Test
    fun addressesAndRangesAreValidatedWithoutDns() {
        assertEquals("10.0.0.0/8", ColituSplitTunnel.normalizeIp("10.0.0.0/8"))
        assertEquals("1.2.3.4", ColituSplitTunnel.normalizeIp(" 1.2.3.4 "))
        assertEquals("2001:db8:0:0:0:0:0:1", ColituSplitTunnel.normalizeIp("2001:db8::1"))
        assertEquals("2001:db8:0:0:0:0:0:0/32", ColituSplitTunnel.normalizeIp("2001:db8::/32"))
        assertNull(ColituSplitTunnel.normalizeIp("256.1.1.1"))
        assertNull(ColituSplitTunnel.normalizeIp("1.2.3.4/33"))
        assertNull(ColituSplitTunnel.normalizeIp("1.2.3"))
        assertNull(ColituSplitTunnel.normalizeIp("example.com"))
        assertNull(ColituSplitTunnel.normalizeIp("2001:db8::1/129"))
    }

    @Test
    fun offLeavesTheConfigAlone() {
        assertEquals(raw, ColituSplitTunnel.configure(raw, Settings()))
        assertEquals(raw, ColituSplitTunnel.configure(raw, Settings(Mode.Off, domains = listOf("example.com"))))
    }

    @Test
    fun bypassSendsSitesDirectAfterTheDnsRule() {
        val out = ColituSplitTunnel.configure(raw, Settings(Mode.Bypass, domains = listOf("example.com"), ips = listOf("10.0.0.0/8")))
        val rules = rules(out)
        assertEquals("dns-out", rules.first().get("outboundTag").asString)
        val mine = split(rules)
        assertEquals(2, mine.size)
        assertTrue(mine.all { it.get("outboundTag").asString == "direct" })
        assertEquals("domain:example.com", mine[0].getAsJsonArray("domain")[0].asString)
        assertEquals("10.0.0.0/8", mine[1].getAsJsonArray("ip")[0].asString)
        // The direct outbound exists and the SOCKS inbound sniffs host names.
        val json = JsonParser.parseString(out).asJsonObject
        assertTrue(json.getAsJsonArray("outbounds").any { it.asJsonObject.get("tag").asString == "direct" })
        assertTrue(json.getAsJsonArray("inbounds")[0].asJsonObject.getAsJsonObject("sniffing").get("routeOnly").asBoolean)
        // Privacy mode's rule is untouched.
        assertTrue(rules.any { it.get("ruleTag")?.asString == "colitu-ru-direct" })
    }

    @Test
    fun onlySitesProxiesTheListAndSendsTheRestDirect() {
        val rules = rules(ColituSplitTunnel.configure(raw, Settings(Mode.Only, domains = listOf("example.com"))))
        val mine = split(rules)
        assertEquals("proxy", mine.first().get("outboundTag").asString)
        val last = rules.last()
        assertEquals(ColituSplitTunnel.RULE_TAG, last.get("ruleTag").asString)
        assertEquals("direct", last.get("outboundTag").asString)
        assertEquals("tcp,udp", last.get("network").asString)
    }

    @Test
    fun onlyWithAppsUsesNoSiteRules() {
        val settings = Settings(Mode.Only, apps = setOf("org.example.browser"), domains = listOf("example.com"))
        assertFalse(settings.sitesApply)
        assertTrue(split(rules(ColituSplitTunnel.configure(raw, settings))).isEmpty())
    }

    @Test
    fun configureIsIdempotentAndReversible() {
        val settings = Settings(Mode.Bypass, domains = listOf("example.com"))
        val once = ColituSplitTunnel.configure(raw, settings)
        assertEquals(once, ColituSplitTunnel.configure(once, settings))
        val off = ColituSplitTunnel.configure(once, Settings())
        assertTrue(split(rules(off)).isEmpty())
    }

    @Test
    fun vpnInterfaceScopeKeepsColituOutside() {
        val self = "com.colitulu"
        val installed = { pkg: String -> pkg != "gone.app" }
        // Off: only Colitu outside.
        assertEquals(ColituSplitTunnel.AppScope(setOf(self), null), ColituSplitTunnel.appScope(Settings(), self, installed))
        // Bypass: the chosen apps and Colitu outside; uninstalled apps skipped.
        assertEquals(
            ColituSplitTunnel.AppScope(setOf("a.app", self), null),
            ColituSplitTunnel.appScope(Settings(Mode.Bypass, apps = setOf("a.app", "gone.app")), self, installed),
        )
        // Only: an allow-list without Colitu, even if it was picked.
        assertEquals(
            ColituSplitTunnel.AppScope(null, setOf("a.app")),
            ColituSplitTunnel.appScope(Settings(Mode.Only, apps = setOf("a.app", self)), self, installed),
        )
        // Only without (installed) apps: everything but Colitu in the tunnel, sites decide.
        assertEquals(
            ColituSplitTunnel.AppScope(setOf(self), null),
            ColituSplitTunnel.appScope(Settings(Mode.Only, apps = setOf("gone.app"), domains = listOf("example.com")), self, installed),
        )
    }

    @Test
    fun activeAndCount() {
        assertFalse(Settings(Mode.Bypass).active)
        assertTrue(Settings(Mode.Bypass, apps = setOf("a")).active)
        assertEquals(3, Settings(Mode.Only, apps = setOf("a"), domains = listOf("b.com"), ips = listOf("1.1.1.1")).count)
        assertEquals(Mode.Off, Mode.of("nonsense"))
    }
}
