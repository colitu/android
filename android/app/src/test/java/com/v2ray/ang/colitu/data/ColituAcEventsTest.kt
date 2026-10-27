package com.v2ray.ang.colitu.data

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituAcEventsTest {
    private val node = "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b"
    private val other = "aaaaaaaa-0000-4000-8000-bbbbbbbbbbbb"

    @Test
    fun connectEventShape() {
        val ok = ColituAcEvents.connect(node.uppercase(), "vless-reality", true, null, 1234, firstTry = true, auto = false)!!
        assertEquals("connect", ok.get("kind").asString)
        assertEquals(node, ok.get("node_id").asString)
        assertEquals("vless-reality", ok.get("protocol").asString)
        assertEquals("ok", ok.get("outcome").asString)
        assertFalse(ok.has("reason"))
        assertEquals(1234, ok.get("ms").asInt)
        assertTrue(ok.get("first_try").asBoolean)
        assertEquals("manual", ok.get("mode").asString)

        val fail = ColituAcEvents.connect(node, "hysteria2", false, "VERIFY_FAILED", 9_999_999, firstTry = false, auto = true)!!
        assertEquals("fail", fail.get("outcome").asString)
        assertEquals("blocked", fail.get("reason").asString)
        assertEquals(600_000, fail.get("ms").asInt)
        assertEquals("auto", fail.get("mode").asString)
    }

    @Test
    fun eventsWithoutAValidNodeOrProtocolAreNotBuilt() {
        assertNull(ColituAcEvents.connect(null, "hysteria2", true, null, 1, true, true))
        assertNull(ColituAcEvents.connect(node, "wireguard", true, null, 1, true, true))
        assertNull(ColituAcEvents.drop("x", "hysteria2", null, 3))
        assertNull(ColituAcEvents.recovery(node, null, null, true, null, 1, null, false))
        assertNull(ColituAcEvents.probe("", 20))
    }

    @Test
    fun recoverySwitchDropAndProbeShapes() {
        val r = ColituAcEvents.recovery(node, "hysteria2", "vless-reality", true, null, 4200, 1500, spare = true)!!
        assertEquals("recovery", r.get("kind").asString)
        assertEquals("vless-reality", r.get("to_protocol").asString)
        assertEquals(1500, r.get("detect_ms").asInt)
        assertTrue(r.get("spare").asBoolean)
        val rf = ColituAcEvents.recovery(node, "hysteria2", null, false, "ENGINE_FAILED", 100, null, false)!!
        assertEquals("core_crash", rf.get("reason").asString)
        assertFalse(rf.has("detect_ms"))
        assertFalse(rf.has("to_protocol"))

        val s = ColituAcEvents.switch("hysteria2", "trojan", true, flap = true)!!
        assertEquals("switch", s.get("kind").asString)
        assertTrue(s.get("flap").asBoolean)
        assertEquals("trojan", s.get("to_protocol").asString)
        // Back on the transport it left: no switch event (was outcome=fail).
        assertNull(ColituAcEvents.switch("hysteria2", "hysteria2", true, flap = false))
        assertNull(ColituAcEvents.switch("Trojan", "trojan", false, flap = false))

        val d = ColituAcEvents.drop(node, "trojan", "x", 12)!!
        assertEquals("other", d.get("reason").asString)
        assertEquals(12, d.get("minutes").asInt)

        val p = ColituAcEvents.probe(node, 38)!!
        assertEquals("ok", p.get("outcome").asString)
        assertEquals(38, p.get("ms").asInt)
        val pf = ColituAcEvents.probe(node, null)!!
        assertEquals("fail", pf.get("outcome").asString)
        assertFalse(pf.has("ms"))
    }

    @Test
    fun choiceComparesWithTheBestMeasuredNode() {
        val c = ColituAcEvents.choice(node, mapOf(node to 80, other to 25, "cccccccc-0000-4000-8000-dddddddddddd" to null))!!
        assertEquals("choice", c.get("kind").asString)
        assertEquals(node, c.get("node_id").asString)
        assertEquals(80, c.get("ms").asInt)
        assertEquals(other, c.get("best_node_id").asString)
        assertEquals(25, c.get("best_ms").asInt)
        assertEquals("auto", c.get("mode").asString)
        assertTrue(c.get("best_ms").asInt <= c.get("ms").asInt)
        // The chosen node is the best one.
        assertEquals(node, ColituAcEvents.choice(node, mapOf(node to 20, other to 25))!!.get("best_node_id").asString)
        // Nothing measured for the chosen node: no event.
        assertNull(ColituAcEvents.choice(node, mapOf(node to null, other to 25)))
    }

    @Test
    fun failureCodesMapToTheReasonEnum() {
        val cases = mapOf(
            "TIMEOUT" to "timeout", "TUNNEL_TIMEOUT" to "timeout", "UNREACHABLE" to "timeout",
            "IO_ERROR" to "reset", "network_error" to "no_network", "VERIFY_FAILED" to "blocked",
            "ENGINE_FAILED" to "core_crash", "TUN_FAILED" to "core_crash",
            "AUTH_EXPIRED" to "auth", "ENTITLEMENT_EXPIRED" to "auth", "DEVICE_REVOKED" to "auth",
            "QUOTA_EXCEEDED" to "quota", "FREE_DAILY_LIMIT_REACHED" to "quota",
            "NETWORK_CHANGE" to "network_change", "dns" to "dns", "TLS" to "tls",
            "NO_SERVERS" to "other", "CONFIG_NOT_READY" to "other", "" to "other", null to "other",
        )
        cases.forEach { (code, reason) -> assertEquals("$code", reason, ColituAcEvents.reasonOf(code)) }
        cases.values.forEach { assertTrue(it in ColituAcEvents.REASONS) }
    }

    @Test
    fun queueDropsTheOldestBeyondTheCap() {
        val q = ColituAcEvents.Queue(cap = 1000)
        for (i in 0 until 1005) {
            q.add(ColituAcEvents.drop(node, "trojan", null, i.toLong())!!, "t", "wifi", i.toLong())
        }
        assertEquals(1000, q.entries.size)
        assertEquals(5, q.entries.first().event.get("minutes").asInt)
        assertEquals(1004, q.entries.last().event.get("minutes").asInt)
    }

    @Test
    fun queueKeepsTheLastProbePerNodePerHour() {
        val q = ColituAcEvents.Queue()
        val h = 3_600_000L
        q.add(ColituAcEvents.probe(node, 50)!!, "t", "wifi", 0)
        q.add(ColituAcEvents.probe(node, 40)!!, "t", "wifi", 10 * 60_000L)
        q.add(ColituAcEvents.probe(other, 90)!!, "t", "wifi", 11 * 60_000L)
        assertEquals(2, q.entries.size)
        assertEquals(40, q.entries.first { it.event.get("node_id").asString == node }.event.get("ms").asInt)
        q.add(ColituAcEvents.probe(node, 45)!!, "t", "wifi", 10 * 60_000L + h + 1)
        assertEquals(3, q.entries.size)
    }

    @Test
    fun batchesAreOneNetworkAtMostThreeHundredAndClearedWhenSent() {
        val q = ColituAcEvents.Queue()
        for (i in 0 until 350) q.add(ColituAcEvents.drop(node, "trojan", null, 1)!!, "tok-a", "wifi", i.toLong())
        q.add(ColituAcEvents.drop(node, "trojan", null, 2)!!, "tok-b", "cellular", 999)
        val batch = q.nextBatch()
        assertEquals(300, batch.size)
        assertTrue(batch.all { it.token == "tok-a" })
        val body = ColituAcEvents.requestBody("2.9.0", batch.first().token, batch.first().type, batch)
        assertEquals("android", body.get("platform").asString)
        assertEquals("2.9.0", body.get("app_version").asString)
        assertEquals("tok-a", body.get("network_token").asString)
        assertEquals("wifi", body.get("network_type").asString)
        assertEquals(300, body.getAsJsonArray("events").size())
        q.remove(batch)
        assertEquals(51, q.entries.size)
        assertEquals("tok-a", q.nextBatch().first().token)
        // The second network follows once the first is empty.
        q.remove(q.nextBatch())
        assertEquals("tok-b", q.nextBatch().first().token)
    }

    @Test
    fun unknownNetworkTypeAndEmptyTokenAreAllowed() {
        val q = ColituAcEvents.Queue()
        q.add(ColituAcEvents.probe(node, 10)!!, "", "none", 0)
        val b = q.nextBatch()
        val body = ColituAcEvents.requestBody("1", b.first().token, b.first().type, b)
        assertEquals("", body.get("network_token").asString)
        assertEquals("", body.get("network_type").asString)
    }

    @Test
    fun queueSurvivesStorage() {
        val q = ColituAcEvents.Queue()
        q.add(ColituAcEvents.probe(node, 33)!!, "tok", "ethernet", 5)
        val back = ColituAcEvents.Queue.fromJson(q.toJson())
        assertEquals(1, back.entries.size)
        assertEquals("tok", back.entries[0].token)
        assertEquals("ethernet", back.entries[0].type)
        assertEquals(33, back.entries[0].event.get("ms").asInt)
        assertTrue(ColituAcEvents.Queue.fromJson("garbage").isEmpty)
        assertEquals(2, ColituAcEvents.Queue.fromJson(q.toJson().let { "[" + it.trim('[', ']') + "," + it.trim('[', ']') + "]" }).entries.size)
    }

    @Test
    fun sendOutcomeAndSchedule() {
        assertEquals(ColituMetricsOutcome.Reset, ColituAcEvents.outcomeFor(202))
        assertEquals(ColituMetricsOutcome.Reset, ColituAcEvents.outcomeFor(400))
        assertEquals(ColituMetricsOutcome.Keep, ColituAcEvents.outcomeFor(429))
        assertEquals(ColituMetricsOutcome.Keep, ColituAcEvents.outcomeFor(500))
        assertEquals(ColituMetricsOutcome.Keep, ColituAcEvents.outcomeFor(null))
        val h = 3_600_000L
        assertTrue(ColituAcEvents.due(true, true, true, 0, 10 * h))
        assertFalse(ColituAcEvents.due(true, true, true, 10 * h - 59 * 60_000L, 10 * h))
        assertTrue(ColituAcEvents.due(true, true, true, 9 * h, 10 * h))
        assertFalse(ColituAcEvents.due(false, true, true, 0, 10 * h))
        assertFalse(ColituAcEvents.due(true, false, true, 0, 10 * h))
        assertFalse(ColituAcEvents.due(true, true, false, 0, 10 * h))
    }

    @Test
    fun journalLineIsReadable() {
        val event = ColituAcEvents.connect("0f5c2d9e-aaaa-bbbb-cccc-111111111111", "hysteria2", false, "tls", 1234, true, true)!!
        val line = ColituAcEvents.journalLine(event, "wifi", 0)
        assertTrue(line, line.contains(" connect net=wifi ") && line.contains("outcome=fail") && line.contains("protocol=hysteria2") && line.contains("ms=1234"))
        assertFalse(line, line.contains("kind="))
    }
}
