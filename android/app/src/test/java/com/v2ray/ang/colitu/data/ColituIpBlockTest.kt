package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituIpBlockTest {
    private val fixture = JsonParser.parseReader(
        javaClass.classLoader!!.getResourceAsStream("tls-rst-fixtures.json")!!.reader(),
    ).asJsonObject

    @Test
    fun classifyCasesOfTheFixture() {
        val cases = fixture.getAsJsonArray("classify")
        assertTrue(cases.size() >= 9)
        cases.forEach { c ->
            val o = c.asJsonObject
            val results = o.getAsJsonArray("results").map {
                val r = it.asJsonObject
                r.get("port").asInt to ColituTlsProbe.Result.of(r.get("tls").asString)!!
            }
            assertEquals(o.get("name").asString, o.get("ip_blocked").asBoolean, ColituIpBlock.ipBlocked(results))
        }
    }

    @Test
    fun scheduleCasesOfTheFixture() {
        val cases = fixture.getAsJsonArray("schedule")
        assertTrue(cases.size() >= 6)
        cases.forEach { c ->
            val o = c.asJsonObject
            val age = o.get("last_run_age_h").takeIf { !it.isJsonNull }?.asDouble
            assertEquals(
                o.get("name").asString, o.get("due").asBoolean,
                ColituIpBlock.measurementDue(o.get("vpn_on").asBoolean, o.get("targets").asInt, age, o.get("interval_h").asInt),
            )
        }
    }

    private fun e(protocol: String, port: Int) = ColituIpBlock.Endpoint(protocol, "h.example.com", port, "sni.example.com")

    @Test
    fun pickEndpointsTakesTwoDistinctPortsOfTheTlsTransports() {
        val all = listOf(e("vless-xhttp", 2053), e("vless-reality", 443), e("trojan", 443), e("trojan", 4433))
        assertEquals(listOf(443 to "vless-reality", 2053 to "vless-xhttp"), ColituIpBlock.pickEndpoints(all).map { it.port to it.protocol })
        assertEquals(listOf(2053, 443), ColituIpBlock.pickEndpoints(all.filter { it.protocol != "vless-reality" }).map { it.port })
        assertEquals(emptyList<ColituIpBlock.Endpoint>(), ColituIpBlock.pickEndpoints(listOf(e("hysteria2", 8443), e("shadowsocks", 8388))))
        assertEquals(1, ColituIpBlock.pickEndpoints(listOf(e("trojan", 443), e("vless-reality", 443))).size)
    }

    @Test
    fun endpointOfReadsTheRenderedXrayConfig() {
        val reality = """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"de6.example.com","port":443,"users":[]}]},
            "streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"www.cloudflare.com"}}}]}"""
        val trojan = """{"outbounds":[{"tag":"proxy","protocol":"trojan","settings":{"servers":[{"address":"de6.example.com","port":4433}]},
            "streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"serverName":"de6.example.com"}}}]}"""
        assertEquals(ColituIpBlock.Endpoint("vless-reality", "de6.example.com", 443, "www.cloudflare.com"), ColituIpBlock.endpointOf("vless-reality", reality))
        assertEquals(ColituIpBlock.Endpoint("trojan", "de6.example.com", 4433, "de6.example.com"), ColituIpBlock.endpointOf("trojan", trojan))
        assertNull(ColituIpBlock.endpointOf("hysteria2", reality))
        assertNull(ColituIpBlock.endpointOf("trojan", "not json"))
        assertNull(ColituIpBlock.endpointOf("trojan", null))
    }

    @Test
    fun measureTargetsParseAndRoundTrip() {
        val json = JsonParser.parseString(
            """{"interval_h":6,"targets":[
              {"node_id":"n1","endpoint_id":"e1","protocol":"vless-xhttp","host":"De6.Example","port":4443,"sni":"www.cloudflare.com"},
              {"node_id":"n2","endpoint_id":"e2","protocol":"trojan","host":"10.0.0.1","port":443,"sni":"x"},
              {"node_id":"","host":"a.example","port":443}]}""",
        ).asJsonObject
        val parsed = ColituIpBlock.parseTargets(json)!!
        assertEquals(6, parsed.intervalH)
        assertEquals(1, parsed.targets.size)
        assertEquals("de6.example", parsed.targets[0].host)
        assertEquals(4443, parsed.targets[0].port)
        assertEquals(parsed, ColituIpBlock.targetsFromJson(ColituIpBlock.targetsToJson(parsed)))
        assertNull(ColituIpBlock.parseTargets(null))
        assertEquals(0, ColituIpBlock.parseTargets(JsonParser.parseString("""{"interval_h":6,"targets":[]}""").asJsonObject)!!.targets.size)
        assertNull(ColituIpBlock.targetsFromJson("garbage"))
    }

    @Test
    fun serverListParsesMeasureTargetsAndLeavesThemOutWhenAbsent() {
        val with = ColituServerListResponse.fromJson(
            JsonParser.parseString("""{"servers":[],"measure_targets":{"interval_h":12,"targets":[{"node_id":"n1","protocol":"trojan","host":"a.example","port":443,"sni":"a.example"}]}}""").asJsonObject,
        )
        assertEquals(12, with.measureTargets?.intervalH)
        assertFalse(with.measureTargets!!.targets.isEmpty())
        assertNull(ColituServerListResponse.fromJson(JsonParser.parseString("""{"servers":[]}""").asJsonObject).measureTargets)
    }

    @Test
    fun tlsProbeEventsFlagIsParsedFromFeatures() {
        val on = ColituRemoteFeatures.parse(JsonParser.parseString("""{"tls_probe_events":true,"ac_engine_4":false}""").asJsonObject)!!
        assertTrue(on["tls_probe_events"] == true)
        assertFalse(ColituRemoteFeatures.parse(JsonParser.parseString("""{"ac_engine_4":true}""").asJsonObject)!!["tls_probe_events"] == true)
    }
}
