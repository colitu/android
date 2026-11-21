package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The grip failure queue: batches, caps and the panel's answers (GECKO-G4-CONTRACTS.md §3, as Windows). */
class ColituGeckoReportsTest {
    private val t0 = 1_763_190_000_000L

    private fun grip(entry: String) = ColituGeckoEvent("grip", skin = "s1", rev = "3", cls = "frozen", bytes = 10, bucket = "4KiB", entry = entry)

    private fun queue(count: Int) = ColituGeckoReportQueue("3.1.4").apply { repeat(count) { add(grip("e$it"), t0) } }

    @Test
    fun add_onlyCompleteGripEvents_andAtMost300() {
        val q = ColituGeckoReportQueue()
        assertFalse(q.add(ColituGeckoEvent("session"), t0))
        assertFalse(q.add(grip("e").copy(bucket = null), t0))
        assertTrue(q.add(grip("e"), t0))
        val full = queue(350)
        assertEquals(ColituGeckoReportQueue.MAX_WAITING, full.size)
    }

    @Test
    fun flush_sendsAtMost100_thenWaitsAMinute() = runBlocking {
        val q = queue(250)
        val bodies = mutableListOf<String>()
        assertEquals(100, q.flush({ bodies += it; 204 }, t0))
        assertEquals(0, q.flush({ bodies += it; 204 }, t0 + 30_000))
        assertEquals(100, q.flush({ bodies += it; 204 }, t0 + 60_000))
        assertEquals(50, q.flush({ bodies += it; 200 }, t0 + 120_000))
        assertEquals(0, q.size)
        val first = JsonParser.parseString(bodies[0]).asJsonObject
        assertEquals(1, first.get("v").asInt)
        val report = first.getAsJsonArray("reports")[0].asJsonObject
        assertEquals("e0", report.get("entry").asString)
        assertEquals(3, report.get("rev").asInt)
        assertTrue(report.get("rev").asJsonPrimitive.isNumber)
        assertEquals("frozen", report.get("class").asString)
        assertEquals(t0 / 1000, report.get("at").asLong)
        assertEquals(setOf("entry", "skin", "rev", "class", "bucket", "at", "app"), report.keySet())
        assertEquals("3.1.4", report.get("app").asString)
    }

    @Test
    fun buckets_everyBoundary() {
        listOf(0.0 to "<50", 49.9 to "<50", 50.0 to "50-100", 99.9 to "50-100", 100.0 to "100-200", 199.9 to "100-200",
            200.0 to "200-400", 399.9 to "200-400", 400.0 to ">400", 5000.0 to ">400")
            .forEach { (v, want) -> assertEquals("rtt $v", want, ColituGeckoBuckets.rtt(v)) }
        listOf(0.0 to "<1", 0.99 to "<1", 1.0 to "1-5", 4.99 to "1-5", 5.0 to "5-20", 19.9 to "5-20",
            20.0 to "20-100", 99.9 to "20-100", 100.0 to ">100", 900.0 to ">100")
            .forEach { (v, want) -> assertEquals("rate $v", want, ColituGeckoBuckets.rate(v)) }
        listOf(0L to "<10s", 9_900L to "<10s", 10_000L to "10-60s", 59_900L to "10-60s", 60_000L to "1-10m",
            599_900L to "1-10m", 600_000L to ">10m", 86_400_000L to ">10m")
            .forEach { (v, want) -> assertEquals("life $v", want, ColituGeckoBuckets.life(v)) }
    }

    @Test
    fun buckets_refuseUnusableValues_andBadVersions() {
        assertEquals(null, ColituGeckoBuckets.rtt(-1.0))
        assertEquals(null, ColituGeckoBuckets.rate(Double.NaN))
        assertEquals(null, ColituGeckoBuckets.life(-1))
        assertEquals(null, ColituGeckoBuckets.mbitPerSecond(1_000_000, 0))
        assertEquals(10.0, ColituGeckoBuckets.mbitPerSecond(12_500_000, 10_000)!!, 1e-9)
        assertEquals("3.1.4+abc-1", ColituGeckoBuckets.app("3.1.4+abc-1"))
        assertEquals(null, ColituGeckoBuckets.app(""))
        assertEquals(null, ColituGeckoBuckets.app("1".repeat(33)))
        assertEquals(null, ColituGeckoBuckets.app("3.1 4"))
        assertEquals(null, ColituGeckoBuckets.app(null))
    }

    @Test
    fun ok_carriesOnlyLabels_noRawNumber() = runBlocking {
        val q = ColituGeckoReportQueue("3.1.4")
        assertTrue(q.addOk("e1", "s1", "3", t0, rttMs = 73.4, rateMbit = ColituGeckoBuckets.mbitPerSecond(5_000_000, 10_000), lifeMs = 754_000))
        var body = ""
        q.flush({ body = it; 204 }, t0)
        val r = JsonParser.parseString(body).asJsonObject.getAsJsonArray("reports")[0].asJsonObject
        assertEquals(setOf("entry", "skin", "rev", "class", "at", "rtt", "rate", "life", "app"), r.keySet())
        assertEquals("ok", r.get("class").asString)
        assertEquals("50-100", r.get("rtt").asString)
        assertEquals("1-5", r.get("rate").asString)
        assertEquals(">10m", r.get("life").asString)
        assertFalse(body.contains("73") || body.contains("754") || body.contains("5000000"))
    }

    @Test
    fun ok_onePerEntryPerTenMinutes_unknownValuesLeftOut() = runBlocking {
        val q = ColituGeckoReportQueue(null)
        assertTrue(q.addOk("e1", "s1", "3", t0))
        assertFalse(q.addOk("e1", "s1", "3", t0 + 9 * 60_000))
        assertTrue(q.addOk("e2", "s1", "3", t0 + 9 * 60_000))
        assertTrue(q.addOk("e1", "s1", "3", t0 + 10 * 60_000))
        assertEquals(3, q.size)
        var body = ""
        q.flush({ body = it; 204 }, t0 + 11 * 60_000)
        val r = JsonParser.parseString(body).asJsonObject.getAsJsonArray("reports")[0].asJsonObject
        assertEquals(setOf("entry", "skin", "rev", "class", "at"), r.keySet())
    }

    @Test
    fun notOffered_stopsAndClears_untilResumed() = runBlocking {
        val q = queue(5)
        assertEquals(0, q.flush({ 404 }, t0))
        assertEquals(0, q.size)
        q.add(grip("x"), t0)
        var calls = 0
        assertEquals(0, q.flush({ calls++; 204 }, t0 + 120_000))
        assertEquals(0, calls)
        q.resume()
        assertEquals(1, q.flush({ calls++; 204 }, t0 + 240_000))
    }

    @Test
    fun failures_keepTheBatchInOrder_429Pauses_400Drops() = runBlocking {
        val q = queue(3)
        assertEquals(0, q.flush({ throw java.io.IOException("down") }, t0))
        assertEquals(3, q.size)
        var order = emptyList<String>()
        q.flush({ body ->
            order = JsonParser.parseString(body).asJsonObject.getAsJsonArray("reports").map { it.asJsonObject.get("entry").asString }
            429
        }, t0 + 60_000)
        assertEquals(listOf("e0", "e1", "e2"), order)
        assertEquals(3, q.size)
        // Ten minutes of rest after a 429.
        assertEquals(0, q.flush({ 204 }, t0 + 60_000 + 5 * 60_000))
        assertEquals(0, q.flush({ 400 }, t0 + 60_000 + 10 * 60_000))
        assertEquals(0, q.size)
    }

    @Test
    fun pump_sendsOnceMoreAfterItStops() = runBlocking {
        val q = queue(2)
        var running = true
        var sent = 0
        q.pump({ running.also { running = false } }, { body -> sent += JsonParser.parseString(body).asJsonObject.getAsJsonArray("reports").size(); 204 }, tickMs = 1)
        assertEquals(2, sent)
    }

    private fun end(cls: String, entry: String = "e1", life: Long = 754_321, received: Long = 98_765, rtt: Long = 73, goodput: Long = 333_444) =
        ColituGeckoRules.parseEvent(
            "gecko-client: event {\"event\":\"grip_end\",\"entry\":\"$entry\",\"skin\":\"s1\",\"rev\":3,\"class\":\"$cls\"," +
                "\"life_ms\":$life,\"sent\":111,\"received\":$received,\"rtt_ms\":$rtt,\"goodput\":$goodput}",
        )!!

    private fun endWithSent(entry: String, sent: Long, received: Long, life: Long) =
        ColituGeckoRules.parseEvent(
            "gecko-client: event {\"event\":\"grip_end\",\"entry\":\"$entry\",\"skin\":\"s1\",\"rev\":3,\"class\":\"ok\"," +
                "\"life_ms\":$life,\"sent\":$sent,\"received\":$received,\"rtt_ms\":0,\"goodput\":0}",
        )!!

    @Test
    fun gripEnd_ok_becomesASlicedReport_withoutRawNumbers() = runBlocking {
        val q = ColituGeckoReportQueue("3.1.4")
        val evt = end("ok")
        assertFalse(evt.describe().contains("754321") || evt.describe().contains("333444"))
        assertTrue(q.addEnd(evt, t0))
        var body = ""
        q.flush({ body = it; 204 }, t0)
        val r = JsonParser.parseString(body).asJsonObject.getAsJsonArray("reports")[0].asJsonObject
        assertEquals(setOf("entry", "skin", "rev", "class", "at", "rtt", "rate", "life", "app"), r.keySet())
        assertEquals("ok", r.get("class").asString)
        assertEquals("50-100", r.get("rtt").asString)
        assertEquals("<1", r.get("rate").asString) // 98765 B in 754 s; the goodput (upload direction) is not used
        assertEquals(">10m", r.get("life").asString)
        listOf("73,", "754321", "333444", "98765", "111").forEach { assertFalse(it, body.contains(it)) }
    }

    @Test
    fun gripEnd_rateIsTheBusierDirectionOverLife_nonOkAndIncompleteGiveNothing() = runBlocking {
        val q = ColituGeckoReportQueue(null)
        assertFalse(q.addEnd(end("frozen"), t0))
        assertFalse(q.addEnd(end("lost"), t0))
        assertFalse(q.addEnd(ColituGeckoRules.parseEvent("gecko-client: event {\"event\":\"grip_end\",\"class\":\"ok\"}")!!, t0))
        assertEquals(0, q.size)
        // goodput 0 and rtt 0: not measured; 2_500_000 B in 10 s = 2 Mbit/s
        assertTrue(q.addEnd(end("ok", "e1", life = 10_000, received = 2_500_000, rtt = 0, goodput = 0), t0))
        // a download seen on the canary: 158539591 B in 25389 ms is about 50 Mbit/s; the goodput (642 B/s) is the upload reading
        assertTrue(q.addEnd(end("ok", "e3", life = 25_389, received = 158_539_591, rtt = 0, goodput = 642), t0))
        // an upload: sent counts when it is the larger one (25 MB in 10 s = 20 Mbit/s)
        assertTrue(q.addEnd(endWithSent("e4", sent = 25_000_000, received = 100, life = 10_000), t0))
        // under 1 s: no rate, still a life slice
        assertTrue(q.addEnd(end("ok", "e2", life = 900, received = 5_000, rtt = 0, goodput = 0), t0))
        var body = ""
        q.flush({ body = it; 204 }, t0)
        val list = JsonParser.parseString(body).asJsonObject.getAsJsonArray("reports")
        assertEquals(setOf("entry", "skin", "rev", "class", "at", "rate", "life"), list[0].asJsonObject.keySet())
        assertEquals("1-5", list[0].asJsonObject.get("rate").asString)
        assertEquals("20-100", list[1].asJsonObject.get("rate").asString)
        assertEquals("20-100", list[2].asJsonObject.get("rate").asString)
        assertEquals(setOf("entry", "skin", "rev", "class", "at", "life"), list[3].asJsonObject.keySet())
    }
}
