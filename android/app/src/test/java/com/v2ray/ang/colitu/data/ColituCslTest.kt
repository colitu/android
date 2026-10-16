package com.v2ray.ang.colitu.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CSL alpha: the core config changes and csl-client's command line (same rules as Windows). */
class ColituCslTest {
    private val credential = "0123456789abcdef".repeat(4)

    private fun accounts(): () -> ColituCslAccount {
        var next = 41000
        return { ColituCslAccount(next, "u$next", "p$next-0123456789abcdef").also { next++ } }
    }

    private fun config(spareHost: String? = null) = """
        { "dns": { "tag": "colitu-dns", "servers": ["1.1.1.1"] },
          "inbounds": [ { "tag": "socks", "protocol": "socks", "port": 10808 } ],
          "outbounds": [
            { "tag": "proxy", "protocol": "vless", "settings": { "vnext": [ { "address": "nl.example", "port": 443 } ] } },
            ${spareHost?.let { """{ "tag": "warm-spare", "protocol": "hysteria", "settings": { "address": "$it", "port": 443 } },""" } ?: ""}
            { "tag": "direct", "protocol": "freedom" } ],
          "routing": { "rules": [
            { "type": "field", "inboundTag": ["colitu-verify"], "outboundTag": "proxy" },
            { "type": "field", "inboundTag": ["colitu-dns"], "outboundTag": "proxy" },
            { "type": "field", "domain": ["ru"], "outboundTag": "direct" },
            { "type": "field", "network": "udp", "outboundTag": "proxy" },
            { "type": "field", "network": "tcp,udp", "outboundTag": "proxy" } ] } }
    """.trimIndent()

    private fun rules(config: String) = JsonParser.parseString(config).asJsonObject.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }

    @Test
    fun envelopeCsl_onlyWhenUsable() {
        val ok = JsonObject().apply { addProperty("version", 1); addProperty("port", 9440); addProperty("credential", credential) }
        assertEquals(9440, ColituCslConfig.fromJson(ok)?.port)
        assertNull(ColituCslConfig.fromJson(ok.deepCopy().apply { addProperty("credential", "short") }))
        assertNull(ColituCslConfig.fromJson(ok.deepCopy().apply { addProperty("version", 2) }))
        assertNull(ColituCslConfig.fromJson(null))
        assertFalse(ColituCslConfig.fromJson(ok).toString().contains(credential))
    }

    @Test
    fun apply_sendsTunnelTcpToCslClient_andKeepsInternalTraffic() {
        val (out, launch) = ColituCslRules.apply(config(), 9440, accounts())!!
        val rules = rules(out)
        // Carrier inbound first, straight to the primary.
        assertEquals(ColituCslRules.PRIMARY_INBOUND, rules[0].getAsJsonArray("inboundTag")[0].asString)
        assertEquals("proxy", rules[0].get("outboundTag").asString)
        // Checks and the core's own DNS keep going to the tunnel directly.
        assertTrue(rules.none { it.get("outboundTag")?.asString == ColituCslRules.OUTBOUND_TAG && it.has("inboundTag") })
        // The tcp,udp rule got a TCP copy to csl-client right in front of it; the UDP-only rule none.
        val copyIndex = rules.indexOfFirst { it.get("outboundTag")?.asString == ColituCslRules.OUTBOUND_TAG }
        assertEquals("tcp", rules[copyIndex].get("network").asString)
        assertEquals("tcp,udp", rules[copyIndex + 1].get("network").asString)
        // ...and what no rule matched goes to the first outbound (proxy): a catch-all TCP rule to csl-client.
        assertEquals(2, rules.count { it.get("outboundTag")?.asString == ColituCslRules.OUTBOUND_TAG })
        assertEquals(ColituCslRules.OUTBOUND_TAG, rules.last().get("outboundTag").asString)
        // csl-client's SOCKS port takes only the per-run account.
        val outbound = JsonParser.parseString(out).asJsonObject.getAsJsonArray("outbounds").map { it.asJsonObject }
            .first { it.get("tag").asString == ColituCslRules.OUTBOUND_TAG }
        val server = outbound.getAsJsonObject("settings").getAsJsonArray("servers")[0].asJsonObject
        assertEquals(launch.socks.port, server.get("port").asInt)
        assertEquals(launch.socks.user, server.getAsJsonArray("users")[0].asJsonObject.get("user").asString)
        assertEquals(1, launch.carriers.size)
        assertEquals("tcp", launch.carriers[0].family)
    }

    @Test
    fun apply_spareCarrier_onlyOnTheSameNode() {
        assertEquals(2, ColituCslRules.apply(config(spareHost = "nl.example"), 9440, accounts())!!.second.carriers.size)
        val other = ColituCslRules.apply(config(spareHost = "de.example"), 9440, accounts())!!
        assertEquals(1, other.second.carriers.size)
        assertTrue(rules(other.first).none { it.getAsJsonArray("inboundTag")?.get(0)?.asString == ColituCslRules.SPARE_INBOUND })
        val udp = ColituCslRules.apply(config(spareHost = "nl.example"), 9440, accounts())!!.second.carriers[1]
        assertEquals("udp", udp.family)
    }

    @Test
    fun apply_refusesAnUnexpectedConfig() {
        assertNull(ColituCslRules.apply("""{ "outbounds": [ { "tag": "direct" } ] }""", 9440, accounts()))
        assertNull(ColituCslRules.apply("not json", 9440, accounts()))
        val once = ColituCslRules.apply(config(), 9440, accounts())!!.first
        assertNull("CSL is never added twice", ColituCslRules.apply(once, 9440, accounts()))
    }

    @Test
    fun commandLine_neverHoldsSecrets_andStdinCarriesThem() {
        val launch = ColituCslRules.apply(config(spareHost = "nl.example"), 9440, accounts())!!.second
        val args = ColituCslRules.arguments(launch)
        assertEquals(listOf("-socks", "127.0.0.1:${launch.socks.port}", "-secrets-stdin"), args.take(3))
        assertFalse(args.joinToString(" ").contains(credential))
        assertFalse(args.joinToString(" ").contains(launch.socks.password))
        assertTrue(args.any { it.startsWith("socks5://") && it.endsWith("/127.0.0.1:9440?family=tcp#primary") })
        val secrets = ColituCslRules.secrets(launch, credential)
        assertTrue(secrets.endsWith("\n"))
        val json = JsonParser.parseString(secrets).asJsonObject
        assertEquals(credential, json.get("credential").asString)
        assertEquals(launch.socks.password, json.get("socks_password").asString)
        assertTrue(ColituCslRules.isReadyLine("2026/10/16 09:20:58 csl-client: SOCKS5 on 127.0.0.1:19080 (authentication true)"))
        assertNotNull(ColituCslRules.newAccount().takeIf { it.port in 1..65535 && it.password.length == 32 })
    }

    @Test
    fun alphaServer_neverAutomatic() {
        val alpha = ColituServer("nl", "NL test", "NL", null, isRecommended = false, isAvailable = true, alpha = true)
        val de = ColituServer("de", "DE", "DE", null, isRecommended = false, isAvailable = true)
        assertTrue(ColituServerRanking.autoExcluded(alpha))
        assertFalse(ColituServerRanking.autoExcluded(de))
    }
}
