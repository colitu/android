package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituAdBlockTest {
    // The real servers come from the panel's bootstrap (ad_block.doh); these are stand-ins.
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

    @Test
    fun serversComeFromTheBootstrapAnswer() {
        val answer = JsonParser.parseString(
            """{"ad_block":{"doh":["${servers[0]}","http://plain.example.test/dns-query",7,"${servers[1]}"]}}""",
        ).asJsonObject
        assertEquals(servers, ColituAdBlock.serversOf(answer))
        assertEquals(emptyList<String>(), ColituAdBlock.serversOf(JsonParser.parseString("""{"ad_block":{"doh":[]}}""").asJsonObject))
        // An older panel without the field leaves the stored list alone.
        assertNull(ColituAdBlock.serversOf(JsonParser.parseString("""{"maintenance":{"active":false}}""").asJsonObject))
    }

    @Test
    fun dropsResolversOnNonPublicAddresses() {
        // The config sends local networks direct, so such a resolver would see every lookup outside the tunnel.
        val parsed = ColituAdBlock.parseServers(
            listOf(
                "https://192.168.1.1/dns-query", "https://127.0.0.1/dns-query", "https://[::1]/dns-query",
                "https://10.10.14.2/dns-query", "https://user@dns-c.example.test/dns-query", servers[0],
                "https://203.0.113.5/dns-query",
            ).joinToString(","),
        )
        assertEquals(listOf(servers[0], "https://203.0.113.5/dns-query"), parsed)
        assertEquals(ColituAdBlock.MAX_SERVERS, ColituAdBlock.parseServers((1..20).joinToString(",") { "https://dns$it.example.test/q" }).size)
    }

    @Test
    fun storedProfileFollowsTheSwitchWithoutAReconnect() {
        // Off -> on: ad-blocking DNS in, its resolvers asked through the tunnel.
        val on = ColituAdBlock.storedProfile(ColituDnsRoute.throughTunnel(runtime), enabled = true, servers = servers)
        assertTrue(ColituAdBlock.activeIn(on))
        val onJson = JsonParser.parseString(on).asJsonObject
        assertEquals(servers, onJson.getAsJsonObject("dns").getAsJsonArray("servers").map { it.asString })
        val onRules = onJson.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        assertEquals(listOf(ColituAdBlock.DNS_TAG), onRules.first().getAsJsonArray("inboundTag").map { it.asString })
        assertEquals(1, onRules.count { it.get("outboundTag")?.asString == "dns-out" })
        // On -> off: no dns-out, no port-53 rule, default resolvers through the tunnel again.
        val off = ColituAdBlock.storedProfile(on, enabled = false, servers = servers)
        assertFalse(ColituAdBlock.activeIn(off))
        val offJson = JsonParser.parseString(off).asJsonObject
        assertTrue(offJson.getAsJsonArray("outbounds").none { it.asJsonObject.get("tag").asString == "dns-out" })
        val offRules = offJson.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        assertTrue(offRules.none { it.get("outboundTag")?.asString == "dns-out" })
        assertEquals(ColituDnsRoute.DEFAULT_SERVERS, offJson.getAsJsonObject("dns").getAsJsonArray("servers").map { it.asString })
        assertEquals(ColituDnsRoute.RULE_TAG, offRules.first().get("ruleTag").asString)
        // The local-network rule of the panel stays.
        assertTrue(offRules.any { it.get("outboundTag")?.asString == "direct" })
        // On without servers (list not named yet): stays off.
        assertFalse(ColituAdBlock.activeIn(ColituAdBlock.storedProfile(off, enabled = true, servers = emptyList())))
        // Idempotent (same JSON; key order may differ).
        assertEquals(JsonParser.parseString(on), JsonParser.parseString(ColituAdBlock.storedProfile(on, enabled = true, servers = servers)))
    }

    @Test
    fun activeInReadsTheBuiltConfig() {
        assertTrue(ColituAdBlock.activeIn(ColituAdBlock.apply(runtime, on = true, servers = servers)))
        assertFalse(ColituAdBlock.activeIn(runtime))
        assertFalse(ColituAdBlock.activeIn(null))
        assertFalse(ColituAdBlock.activeIn("not json colitu-adblock-dns"))
        // Kept through the warm spare and the DNS route, which keep an existing dns tag.
        assertTrue(ColituAdBlock.activeIn(ColituDnsRoute.throughTunnel(ColituAdBlock.apply(runtime, on = true, servers = servers))))
    }
}
