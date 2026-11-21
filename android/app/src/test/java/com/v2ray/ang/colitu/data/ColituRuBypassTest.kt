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
        assertEquals(ColituRuBypass.DIRECT_DOMAINS, rules[0].getAsJsonArray("domain").map { it.asString })
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
    fun russianDestinationsAreAlwaysDirect() {
        val config = ColituRuBypass.configure(runtime)
        assertEquals(2, ruDirectRules(config).size)
        val domains = ruDirectRules(config).first { it.has("domain") }.getAsJsonArray("domain").map { it.asString }
        for (d in listOf("geosite:category-ru", "domain:ru", "domain:su", "domain:xn--p1ai", "domain:vk.com", "domain:yandex.net", "domain:tbank.com", "domain:moscow", "domain:tatar")) {
            assertTrue(d, d in domains)
        }
        assertTrue(ruDirectRules(config).any { it.has("ip") })
    }

    @Test
    fun ruleIsPresentWithPrivacyModeOnAndAheadOfProxyRules() {
        // Privacy mode is stored separately and does not reach the config generator at all.
        val panel = """
            {"inbounds":[],"outbounds":[{"tag":"proxy","protocol":"vless"},{"tag":"direct","protocol":"freedom"}],
             "routing":{"rules":[{"type":"field","domain":["geosite:category-ru"],"outboundTag":"proxy"},
                                 {"type":"field","network":"tcp,udp","outboundTag":"proxy"}]}}
        """.trimIndent()
        val servers = listOf("https://dns-a.example.test:3443/dns-query")
        val config = ColituAdBlock.apply(ColituRuBypass.configure(panel), on = true, servers = servers)
        val all = rules(JsonParser.parseString(config).asJsonObject)
        val ruIndexes = all.indices.filter { all[it].get("ruleTag")?.asString == ColituRuBypass.RULE_TAG }
        assertEquals(2, ruIndexes.size)
        // Behind only the DNS hijack, ahead of every rule to the proxy.
        assertTrue(ruIndexes.all { i -> all.take(i).all { it.get("outboundTag").asString == "dns-out" || it.get("ruleTag")?.asString == ColituRuBypass.RULE_TAG } })
    }

    @Test
    fun applyingTwiceKeepsTwoRules() {
        val once = ColituRuBypass.configure(runtime)
        assertEquals(2, ruDirectRules(ColituRuBypass.configure(once)).size)
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

    @Test
    fun freshInstallStartsWithPrivacyModeOn() {
        val fresh = ColituRuBypass.hasPriorState(emptyArray(), loggedIn = false)
        assertFalse(fresh)
        assertTrue(ColituRuBypass.initialPrivacyMode(fresh))
        // Only the language choice does not make an install "existing".
        assertFalse(ColituRuBypass.hasPriorState(arrayOf("language"), loggedIn = false))
        assertFalse(ColituRuBypass.hasPriorState(null, loggedIn = false))
    }

    @Test
    fun existingInstallKeepsPrivacyModeOff() {
        assertFalse(ColituRuBypass.initialPrivacyMode(ColituRuBypass.hasPriorState(emptyArray(), loggedIn = true)))
        assertFalse(ColituRuBypass.initialPrivacyMode(ColituRuBypass.hasPriorState(arrayOf("ru_direct_notice_shown"), loggedIn = false)))
        assertFalse(ColituRuBypass.initialPrivacyMode(ColituRuBypass.hasPriorState(arrayOf("language", "synced_server"), loggedIn = false)))
    }
}
