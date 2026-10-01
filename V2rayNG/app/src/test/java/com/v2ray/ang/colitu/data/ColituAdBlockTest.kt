package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituAdBlockTest {
    // The real servers come from the build (ADBLOCK_DOH); these are stand-ins.
    private val servers = listOf("https://dns-a.example.test:3443/dns-query", "https://dns-b.example.test:3443/dns-query")

    private val runtime = """
        {"inbounds":[{"tag":"socks","protocol":"socks","port":10808}],
         "outbounds":[{"tag":"proxy","protocol":"vless"},{"tag":"direct","protocol":"freedom"}],
         "dns":{},
         "routing":{"domainStrategy":"AsIs","rules":[{"type":"field","ip":["geoip:private"],"outboundTag":"direct"}]}}
    """.trimIndent()

    @Test
    fun leavesConfigAloneWhenOffOrWithoutServers() {
        assertEquals(runtime, ColituAdBlock.apply(runtime, on = false, servers = servers))
        assertEquals(runtime, ColituAdBlock.apply(runtime, on = true, servers = emptyList()))
    }

    @Test
    fun answersDnsThroughColituServersWhenOn() {
        val json = JsonParser.parseString(ColituAdBlock.apply(runtime, on = true, servers = servers)).asJsonObject
        assertEquals(servers, json.getAsJsonObject("dns").getAsJsonArray("servers").map { it.asString })

        val outbounds = json.getAsJsonArray("outbounds").map { it.asJsonObject }
        // The proxy stays first: DoH lookups and everything unmatched leave through it.
        assertEquals("proxy", outbounds.first().get("tag").asString)
        assertEquals(1, outbounds.count { it.get("tag").asString == "dns-out" && it.get("protocol").asString == "dns" })

        val rules = json.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        assertEquals("53", rules.first().get("port").asString)
        assertEquals("dns-out", rules.first().get("outboundTag").asString)
        assertEquals("direct", rules[1].get("outboundTag").asString)
        assertEquals("AsIs", json.getAsJsonObject("routing").get("domainStrategy").asString)
    }

    @Test
    fun parsesTheBuildListAndItsHosts() {
        val parsed = ColituAdBlock.parseServers(" ${servers[0]} ,, ${servers[1]},http://plain.example.test/dns-query")
        assertEquals(servers, parsed)
        assertEquals(setOf("dns-a.example.test", "dns-b.example.test"), ColituAdBlock.hostsOf(parsed))
        assertTrue(ColituAdBlock.parseServers("").isEmpty())
    }

    @Test
    fun isIdempotent() {
        val once = ColituAdBlock.apply(runtime, on = true, servers = servers)
        val twice = JsonParser.parseString(ColituAdBlock.apply(once, on = true, servers = servers)).asJsonObject
        assertEquals(1, twice.getAsJsonArray("outbounds").count { it.asJsonObject.get("tag").asString == "dns-out" })
        assertEquals(1, twice.getAsJsonObject("routing").getAsJsonArray("rules").count { it.asJsonObject.get("outboundTag")?.asString == "dns-out" })
    }
}
