package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import com.v2ray.ang.colitu.app.ColituTransportOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituNetworkHintsTest {
    private val now = 1_000_000_000_000L
    private val hour = 3_600_000L
    private val net = ColituServerRanking.networkKey("cellular", "RU-AS0001")

    private fun config(protocol: String) = ColituVpnConfig("n1", null, "xray-mobile-v1", protocol, "{}", null, false)
    private val offered = listOf(config("hysteria2"), config("vless-reality"), config("trojan"))

    @Test
    fun parsesServersFields() {
        val json = JsonParser.parseString(
            """{"servers":[],"client_network":"RU-AS0001","network_token":"tok",
               "network_hints":{"blocked":["Hysteria2"],"scope":"network","updated_at":"2026-10-09T10:00:00Z"}}""",
        ).asJsonObject
        val parsed = ColituServerListResponse.fromJson(json)
        assertEquals("tok", parsed.networkToken)
        assertEquals(setOf("hysteria2"), parsed.networkHints?.blocked)
        assertEquals("network", parsed.networkHints?.scope)
        assertNull(ColituServerListResponse.fromJson(JsonParser.parseString("""{"servers":[]}""").asJsonObject).networkHints)
    }

    @Test
    fun hintedTransportGoesLastUnlessItWorkedHereWithin24h() {
        val memory = ColituConnectMemory.empty()
        val hinted = ColituNetworkHintsPolicy.demoted(setOf("hysteria2")) { memory.workedOnNetwork(net, it, now) }
        assertEquals(setOf("hysteria2"), hinted)
        // Ranked like a stalled transport: last, and the probe walks it last.
        assertEquals(listOf("vless-reality", "trojan", "hysteria2"), ColituTransportOrder.byRank(offered, hinted).map { it.protocolType })

        // Local experience beats the hint: it carried traffic here (on any server) an hour ago.
        memory.recordSuccess(net, "other-node", "hysteria2", now - hour)
        assertTrue(ColituNetworkHintsPolicy.demoted(setOf("hysteria2")) { memory.workedOnNetwork(net, it, now) }.isEmpty())
        // ... but only on this network and only for 24 h.
        val wifi = ColituServerRanking.networkKey("wifi", "RU-AS0001")
        assertEquals(setOf("hysteria2"), ColituNetworkHintsPolicy.demoted(setOf("hysteria2")) { memory.workedOnNetwork(wifi, it, now) })
        assertEquals(setOf("hysteria2"), ColituNetworkHintsPolicy.demoted(setOf("hysteria2")) { memory.workedOnNetwork(net, it, now + 24 * hour) })
    }

    @Test
    fun recordCountsOnlyOnItsNetworkFor48h() {
        val record = ColituNetworkRecord("RU-AS0001", "tok", setOf("hysteria2"), now)
        val back = ColituNetworkRecord.fromJson(record.toJson())
        assertEquals(record, back)
        assertEquals("tok", record.tokenFor("RU-AS0001", now + 47 * hour))
        assertNull(record.tokenFor("RU-AS0001", now + 48 * hour))
        assertNull(record.tokenFor("TR-AS9121", now))
        assertEquals(setOf("hysteria2"), record.blockedFor("RU-AS0001", now))
        assertTrue(record.blockedFor("TR-AS9121", now).isEmpty())
    }

    @Test
    fun warmSpareTakesAHintedTransportOnlyWhenNothingElseQualifies() {
        val all = listOf("hysteria2", "vless-reality", "vless-xhttp")
        // TCP primary: Hysteria2 would be first, but it is hinted.
        assertEquals("vless-xhttp", ColituWarmSpare.spareTransport("vless-reality", all, emptySet(), sameServer = true, hinted = setOf("hysteria2")))
        // Nothing else: the hinted one after all.
        assertEquals("hysteria2", ColituWarmSpare.spareTransport("vless-reality", listOf("vless-reality", "hysteria2"), emptySet(), sameServer = true, hinted = setOf("hysteria2")))
        // A stalled transport stays out even then.
        assertNull(ColituWarmSpare.spareTransport("vless-reality", listOf("vless-reality", "hysteria2"), setOf("hysteria2"), sameServer = true, hinted = setOf("hysteria2")))
    }

    @Test
    fun observationBodyMatchesThePanelRules() {
        val body = ColituNetworkHintsPolicy.observationBody(
            "node-1",
            listOf(
                ColituObservation("vless-reality", false, null),
                ColituObservation("hysteria2", true, 120),
                ColituObservation("vless-reality", true, 90_000),
                ColituObservation("auto", true, 10),
            ),
            "tok",
        )!!
        assertEquals("node-1", body.get("node_id").asString)
        assertEquals("tok", body.get("network_token").asString)
        val items = body.getAsJsonArray("observations").map { it.asJsonObject }
        // One entry per transport (the last result), latency clamped, "auto" left out.
        assertEquals(listOf("hysteria2", "vless-reality"), items.map { it.get("protocol").asString })
        assertEquals(60_000, items[1].get("latency_ms").asInt)
        val failed = ColituNetworkHintsPolicy.observationBody("node-1", listOf(ColituObservation("trojan", false, 5)), null)!!
        assertFalse(failed.has("network_token"))
        assertFalse(failed.getAsJsonArray("observations")[0].asJsonObject.has("latency_ms"))
        assertNull(ColituNetworkHintsPolicy.observationBody("node-1", emptyList(), "tok"))
        val many = (1..12).map { ColituObservation("p$it", true, 1) }
        assertEquals(8, ColituNetworkHintsPolicy.observationBody("node-1", many, null)!!.getAsJsonArray("observations").size())
    }

    @Test
    fun preferredIsParsedAndNeverIncludesABlockedTransport() {
        val json = com.google.gson.JsonParser.parseString("""{"blocked":["hysteria2"],"preferred":["vless-reality","hysteria2","trojan"],"scope":"network"}""").asJsonObject
        assertEquals(listOf("vless-reality", "trojan"), ColituNetworkHints.fromJson(json)?.preferred)
        val record = ColituNetworkRecord("TR-AS1", null, setOf("hysteria2"), 1_000L, listOf("vless-reality"))
        assertEquals(listOf("vless-reality"), ColituNetworkRecord.fromJson(record.toJson())?.preferredFor("TR-AS1", 2_000L))
        assertEquals(emptyList<String>(), record.preferredFor("TR-AS2", 2_000L))
    }

    @Test
    fun hintedStartOnlyWithoutOwnMemory() {
        val offered = listOf("hysteria2", "vless-reality", "trojan", "vless-xhttp")
        val rank: (List<String>) -> List<String> = { it.sorted() }
        assertEquals(listOf("trojan", "vless-reality", "hysteria2", "vless-xhttp"),
            ColituNetworkHintsPolicy.hintedStart(offered, { it }, listOf("trojan", "vless-reality"), emptySet(), null, rank))
        // The phone's own last good transport beats the hint.
        assertNull(ColituNetworkHintsPolicy.hintedStart(offered, { it }, listOf("trojan"), emptySet(), "hysteria2", rank))
        // A stalled or not offered preferred transport is skipped.
        assertEquals(listOf("vless-reality", "hysteria2", "trojan", "vless-xhttp"),
            ColituNetworkHintsPolicy.hintedStart(offered, { it }, listOf("tuic", "trojan", "vless-reality"), setOf("trojan"), null, rank))
        assertNull(ColituNetworkHintsPolicy.hintedStart(offered, { it }, emptyList(), emptySet(), null, rank))
    }
}
