package com.v2ray.ang.colitu.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ColituDnsSessionsTest {
    private fun parse(raw: String): JsonObject = JsonParser.parseString(raw).asJsonObject
    private fun dnsOut(json: JsonObject) = json.getAsJsonArray("outbounds").map { it.asJsonObject }.first { it.get("tag").asString == "dns-out" }
    private fun level53(json: JsonObject) = json.getAsJsonObject("policy").getAsJsonObject("levels").getAsJsonObject("53")

    @Test
    fun adBlockConfigGetsTheDnsSessionLevel() {
        val base = """{"outbounds":[{"tag":"proxy","protocol":"hysteria"}],"policy":{"system":{"statsOutboundUplink":true}}}"""
        val raw = ColituAdBlock.apply(base, on = true, servers = listOf("https://a.example/dns-query", "https://b.example/dns-query"))
        val json = parse(ColituDnsSessions.apply(raw))
        assertEquals(53, dnsOut(json).getAsJsonObject("settings").get("userLevel").asInt)
        // Two servers without a timeout: 2 × 4 s + 1 s = 9 s.
        assertEquals(9, level53(json).get("connIdle").asInt)
        // Level 0 and the rest of the policy untouched; idempotent.
        assertFalse(json.getAsJsonObject("policy").getAsJsonObject("levels").has("0"))
        assertEquals(true, json.getAsJsonObject("policy").getAsJsonObject("system").get("statsOutboundUplink").asBoolean)
        assertEquals(json.toString(), ColituDnsSessions.apply(json.toString()))
    }

    @Test
    fun idleIsBoundedAndFollowsServerTimeouts() {
        fun dns(servers: String) = parse("""{"servers":$servers}""")
        assertEquals(5L, ColituDnsSessions.idleSeconds(dns("""["1.1.1.1"]"""), null))
        assertEquals(5L, ColituDnsSessions.idleSeconds(null, null))
        // 2.5 s + 4 s (0 = default) + 1 s, rounded up.
        assertEquals(8L, ColituDnsSessions.idleSeconds(dns("""[{"address":"1.1.1.1","timeoutMs":2500},{"address":"8.8.8.8","timeoutMs":0}]"""), null))
        assertEquals(30L, ColituDnsSessions.idleSeconds(dns("""[{"address":"a","timeoutMs":60000}]"""), 30L))
        assertEquals(61L, ColituDnsSessions.idleSeconds(dns("""[{"address":"a","timeoutMs":60000}]"""), null))
    }

    @Test
    fun ownLevelsAndConfigsWithoutDnsOutStayAsTheyAre() {
        val none = """{"outbounds":[{"tag":"proxy","protocol":"vless"}]}"""
        assertEquals(none, ColituDnsSessions.apply(none))
        val own = """{"outbounds":[{"tag":"dns-out","protocol":"dns","settings":{"userLevel":0}}]}"""
        assertFalse(parse(ColituDnsSessions.apply(own)).has("policy"))
        val preset = """{"outbounds":[{"tag":"dns-out","protocol":"dns"}],"policy":{"levels":{"53":{"connIdle":12}}}}"""
        assertEquals(12, level53(parse(ColituDnsSessions.apply(preset))).get("connIdle").asInt)
    }
}
