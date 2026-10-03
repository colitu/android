package com.v2ray.ang.colitu.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
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
        assertTrue(!ColituRuBypass.appliesTo("RU"))
        assertTrue(!ColituRuBypass.appliesTo("ru"))
        assertTrue(ColituRuBypass.appliesTo("DE"))
        assertTrue(ColituRuBypass.appliesTo(null))
    }
}
