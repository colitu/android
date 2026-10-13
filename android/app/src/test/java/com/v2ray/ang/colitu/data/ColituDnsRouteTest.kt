package com.v2ray.ang.colitu.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituDnsRouteTest {
    // A plain resolver on a Russian address under the Russian bypass: geoip:ru goes direct.
    private val config = """
        {"inbounds":[{"tag":"socks","protocol":"socks","port":10808}],
         "outbounds":[{"tag":"proxy","protocol":"vless"},{"tag":"direct","protocol":"freedom"}],
         "dns":{"servers":["198.51.100.53"]},
         "routing":{"domainStrategy":"IPIfNonMatch","rules":[
           {"type":"field","ip":["geoip:ru"],"outboundTag":"direct","ruleTag":"colitu-ru-direct"},
           {"type":"field","ip":["geoip:private"],"outboundTag":"direct"}]}}
    """.trimIndent()

    private fun rules(raw: String): List<JsonObject> =
        JsonParser.parseString(raw).asJsonObject.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }

    @Test
    fun dnsModuleGoesToTheProxyAheadOfDirectRules() {
        val out = ColituDnsRoute.throughTunnel(config)
        val json = JsonParser.parseString(out).asJsonObject
        assertEquals(ColituWarmSpare.DNS_TAG, json.getAsJsonObject("dns").get("tag").asString)
        val first = rules(out).first()
        assertEquals(ColituDnsRoute.RULE_TAG, first.get("ruleTag").asString)
        assertEquals("proxy", first.get("outboundTag").asString)
        assertEquals(listOf(ColituWarmSpare.DNS_TAG), first.getAsJsonArray("inboundTag").map { it.asString })
        assertEquals(3, rules(out).size)
        // Idempotent: one rule, same place.
        assertEquals(out, ColituDnsRoute.throughTunnel(out))
    }

    @Test
    fun keepsAnExistingDnsTagAndTheCheckRulesFirst() {
        val adBlock = ColituAdBlock.apply(config, on = true, servers = listOf("https://dns.example.test/dns-query"))
        val verified = XrayMobileAdapter.withVerifyInbound(
            ColituDnsRoute.throughTunnel(adBlock),
            com.v2ray.ang.colitu.api.LocalProxy(23456, "u1", "p1"),
            com.v2ray.ang.colitu.api.LocalProxy(23457, "u2", "p2"),
        )
        val all = rules(verified)
        val dnsIndex = all.indexOfFirst { it.get("ruleTag")?.asString == ColituDnsRoute.RULE_TAG }
        assertEquals(listOf(ColituAdBlock.DNS_TAG), all[dnsIndex].getAsJsonArray("inboundTag").map { it.asString })
        // Only the traffic-check rules are in front of it; the port-53 rule follows.
        assertTrue(all.take(dnsIndex).all { it.get("ruleTag")?.asString in setOf(XrayMobileAdapter.VERIFY_TAG, XrayMobileAdapter.VERIFY_SPARE_TAG) })
        assertEquals("dns-out", all[dnsIndex + 1].get("outboundTag").asString)
    }

    @Test
    fun leavesConfigsWithoutDnsServersAlone() {
        val noDns = config.replace(""""dns":{"servers":["198.51.100.53"]},""", "")
        assertEquals(noDns, ColituDnsRoute.throughTunnel(noDns))
        assertEquals("not json", ColituDnsRoute.throughTunnel("not json"))
    }

    @Test
    fun withASpareTheBalancerRuleIsTheOnlyOne() {
        val spare = """{"outbounds":[{"tag":"proxy","protocol":"trojan","settings":{"servers":[{"address":"spare.example.test","port":443,"password":"p"}]}},{"tag":"direct","protocol":"freedom"}]}"""
        val attached = ColituWarmSpare.attach(ColituDnsRoute.throughTunnel(config), spare)
        val out = ColituDnsRoute.throughTunnel(attached)
        val dnsRules = rules(out).filter { r -> r.get("inboundTag")?.asJsonArray?.any { it.asString == ColituWarmSpare.DNS_TAG } == true }
        assertEquals(1, dnsRules.size)
        assertEquals(ColituWarmSpare.BALANCER_TAG, dnsRules.single().get("balancerTag").asString)
        // Spare switched off again: back to the proxy rule.
        val detached = ColituDnsRoute.throughTunnel(ColituWarmSpare.detach(out))
        assertEquals(ColituDnsRoute.RULE_TAG, rules(detached).first().get("ruleTag").asString)
        assertEquals("proxy", rules(detached).first().get("outboundTag").asString)
    }
}
