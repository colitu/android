package com.v2ray.ang.colitu.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituRuBypassTest {
    private val runtime = """
        {"inbounds":[{"tag":"socks","protocol":"socks","port":10808}],
         "outbounds":[{"tag":"proxy","protocol":"vless"},{"tag":"direct","protocol":"freedom"}],
         "dns":{},
         "routing":{}}
    """.trimIndent()

    private fun rules(json: JsonObject) = json.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }

    @Test
    fun sendsRussianDomainsAndIpsDirect() {
        val json = JsonParser.parseString(ColituRuBypass.apply(runtime)).asJsonObject
        val rules = rules(json)
        assertEquals(2, rules.size)
        assertEquals("geosite:category-ru", rules[0].getAsJsonArray("domain").single().asString)
        assertEquals("geoip:ru", rules[1].getAsJsonArray("ip").single().asString)
        assertTrue(rules.all { it.get("outboundTag").asString == "direct" })

        // Everything else still leaves through the proxy, which stays first.
        assertEquals("proxy", json.getAsJsonArray("outbounds").first().asJsonObject.get("tag").asString)

        val sniffing = json.getAsJsonArray("inbounds").single().asJsonObject.getAsJsonObject("sniffing")
        assertTrue(sniffing.get("enabled").asBoolean)
        assertTrue(sniffing.get("routeOnly").asBoolean)
    }

    @Test
    fun addsDirectOutboundWhenMissing() {
        val noDirect = """{"inbounds":[],"outbounds":[{"tag":"proxy","protocol":"vless"}]}"""
        val json = JsonParser.parseString(ColituRuBypass.apply(noDirect)).asJsonObject
        val outbounds = json.getAsJsonArray("outbounds").map { it.asJsonObject }
        assertEquals("proxy", outbounds.first().get("tag").asString)
        assertEquals(1, outbounds.count { it.get("tag").asString == "direct" && it.get("protocol").asString == "freedom" })
    }

    @Test
    fun keepsAdBlockDnsRuleFirstAndIsIdempotent() {
        val servers = listOf("https://dns-a.example.test:3443/dns-query")
        val withAdBlock = ColituAdBlock.apply(ColituRuBypass.apply(runtime), on = true, servers = servers)
        val again = ColituRuBypass.apply(withAdBlock)
        val rules = rules(JsonParser.parseString(again).asJsonObject)
        assertEquals("dns-out", rules.first().get("outboundTag").asString)
        assertEquals(2, rules.count { it.get("ruleTag")?.asString == ColituRuBypass.RULE_TAG })
        assertEquals(3, rules.size)
    }

    @Test
    fun russianServerKeepsRussianSitesInTheTunnel() {
        assertTrue(!ColituRuBypass.applies("RU", privacyMode = false))
        assertTrue(!ColituRuBypass.applies("ru", privacyMode = false))
        assertTrue(ColituRuBypass.applies("DE", privacyMode = false))
        assertTrue(ColituRuBypass.applies(null, privacyMode = false))
    }

    @Test
    fun privacyModeNeverApplies() {
        for (country in listOf("DE", "RU", "ru", null)) {
            assertFalse(ColituRuBypass.applies(country, privacyMode = true))
        }
    }

    /** Any rule that would send Russian traffic outside the tunnel. */
    private fun ruDirectRules(config: String): List<JsonObject> {
        val routing = JsonParser.parseString(config).asJsonObject.get("routing")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return emptyList()
        val rules = routing.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return rules.map { it.asJsonObject }.filter { rule ->
            rule.get("outboundTag")?.asString == "direct" &&
                (listOf("domain", "ip").any { field ->
                    rule.get(field)?.asJsonArray?.any { it.asString in setOf("geosite:category-ru", "geoip:ru") } == true
                } || rule.get("ruleTag")?.asString == ColituRuBypass.RULE_TAG)
        }
    }

    @Test
    fun privacyModeOffSendsRussianSitesDirect() {
        val config = ColituRuBypass.configure(runtime, "DE", privacyMode = false)
        assertEquals(2, ruDirectRules(config).size)
    }

    @Test
    fun privacyModeOnLeavesNoRussianDirectRule() {
        val config = ColituRuBypass.configure(runtime, "DE", privacyMode = true)
        assertTrue(ruDirectRules(config).isEmpty())
        // With ad blocking on top, only its DNS rule is there.
        val servers = listOf("https://dns-a.example.test:3443/dns-query")
        val withAdBlock = ColituAdBlock.apply(config, on = true, servers = servers)
        assertTrue(ruDirectRules(withAdBlock).isEmpty())
        assertEquals(listOf("dns-out"), rules(JsonParser.parseString(withAdBlock).asJsonObject).map { it.get("outboundTag").asString })
    }

    @Test
    fun russianServerGetsNoRussianDirectRule() {
        assertTrue(ruDirectRules(ColituRuBypass.configure(runtime, "RU", privacyMode = false)).isEmpty())
        assertTrue(ruDirectRules(ColituRuBypass.configure(runtime, "RU", privacyMode = true)).isEmpty())
    }

    @Test
    fun privacyModeRemovesRulesFromAnEarlierProfile() {
        // A stored profile built while privacy mode was off, with ad blocking.
        val servers = listOf("https://dns-a.example.test:3443/dns-query")
        val stored = ColituAdBlock.apply(ColituRuBypass.configure(runtime, "DE", privacyMode = false), on = true, servers = servers)
        assertEquals(2, ruDirectRules(stored).size)
        val updated = ColituRuBypass.configure(stored, "DE", privacyMode = true)
        assertTrue(ruDirectRules(updated).isEmpty())
        assertEquals("dns-out", rules(JsonParser.parseString(updated).asJsonObject).single().get("outboundTag").asString)
        // And back: off again restores them, once.
        assertEquals(2, ruDirectRules(ColituRuBypass.configure(updated, "DE", privacyMode = false)).size)
    }

    @Test
    fun privacyModeStripsRussianEntriesFromPanelRules() {
        val panel = """
            {"inbounds":[],"outbounds":[{"tag":"proxy","protocol":"vless"},{"tag":"direct","protocol":"freedom"}],
             "routing":{"rules":[
               {"type":"field","ip":["geoip:private","geoip:ru"],"outboundTag":"direct"},
               {"type":"field","domain":["geosite:category-gov-ru@ads"],"port":"443","outboundTag":"direct"},
               {"type":"field","domain":["domain:yandex.ru","full:example.com"],"outboundTag":"direct"},
               {"type":"field","domain":["geosite:category-ru"],"outboundTag":"proxy"}
             ]}}
        """.trimIndent()
        val rules = rules(JsonParser.parseString(ColituRuBypass.configure(panel, "DE", privacyMode = true)).asJsonObject)
        assertEquals(3, rules.size)
        assertEquals(listOf("geoip:private"), rules[0].getAsJsonArray("ip").map { it.asString })
        // The port-only remainder would have sent all of 443 direct: dropped whole.
        assertEquals(listOf("full:example.com"), rules[1].getAsJsonArray("domain").map { it.asString })
        // Rules to the proxy are left alone.
        assertEquals("proxy", rules[2].get("outboundTag").asString)
    }

    @Test
    fun recognisesRussianMatchers() {
        assertTrue(ColituRuBypass.isRussian("ip", "geoip:ru"))
        assertTrue(ColituRuBypass.isRussian("ip", "ext:geoip.dat:ru"))
        assertFalse(ColituRuBypass.isRussian("ip", "geoip:private"))
        assertTrue(ColituRuBypass.isRussian("domain", "geosite:category-ru"))
        assertTrue(ColituRuBypass.isRussian("domain", "geosite:tld-ru"))
        assertFalse(ColituRuBypass.isRussian("domain", "geosite:category-media-ru-blocked"))
        assertFalse(ColituRuBypass.isRussian("domain", "geosite:google"))
        assertTrue(ColituRuBypass.isRussian("domain", "domain:ru"))
        assertTrue(ColituRuBypass.isRussian("domain", "domain:xn--p1ai"))
        assertFalse(ColituRuBypass.isRussian("domain", "domain:example.com"))
    }
}
