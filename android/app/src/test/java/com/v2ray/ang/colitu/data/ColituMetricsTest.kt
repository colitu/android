package com.v2ray.ang.colitu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituMetricsTest {
    @Test
    fun histogramBucketsFollowTheBounds() {
        assertEquals(0, ColituMetricsData.bucketOf(0))
        assertEquals(0, ColituMetricsData.bucketOf(500))
        assertEquals(1, ColituMetricsData.bucketOf(501))
        assertEquals(1, ColituMetricsData.bucketOf(1000))
        assertEquals(2, ColituMetricsData.bucketOf(2000))
        assertEquals(3, ColituMetricsData.bucketOf(3000))
        assertEquals(4, ColituMetricsData.bucketOf(5000))
        assertEquals(5, ColituMetricsData.bucketOf(8000))
        assertEquals(6, ColituMetricsData.bucketOf(15000))
        assertEquals(7, ColituMetricsData.bucketOf(30000))
        assertEquals(8, ColituMetricsData.bucketOf(30001))
        assertEquals(8, ColituMetricsData.bucketOf(10_000_000))
    }

    @Test
    fun observeFillsNineBucketsAndIgnoresUnknownOrNegative() {
        val d = ColituMetricsData()
        d.observe("connect_ms", 700)
        d.observe("connect_ms", 900)
        d.observe("connect_ms", 60_000)
        d.observe("connect_ms", -5)
        d.observe("something_else", 10)
        assertEquals(listOf(0, 2, 0, 0, 0, 0, 0, 0, 1), d.histograms.getValue("connect_ms").toList())
        assertEquals(setOf("connect_ms"), d.histograms.keys)
    }

    @Test
    fun countersAreCappedAndUnknownNamesIgnored() {
        val d = ColituMetricsData()
        d.add("connect_attempts", 9_999)
        d.add("connect_attempts", 50)
        d.add("not_a_counter", 3)
        assertEquals(ColituMetricsData.MAX_COUNT, d.counters["connect_attempts"])
        assertFalse(d.counters.containsKey("not_a_counter"))
    }

    @Test
    fun takingBackAnAttemptNeverGoesBelowZero() {
        val d = ColituMetricsData()
        d.add("connect_attempts")
        d.add("connect_attempts", -1)
        d.add("connect_attempts", -1)
        assertNull(d.counters["connect_attempts"])
        assertTrue(d.isEmpty)
    }

    @Test
    fun featuresKeepOnlyTheKnownNames() {
        val d = ColituMetricsData()
        d.feature("split_tunnel")
        d.feature("kill_switch")
        d.feature("visited_example.com")
        assertEquals(setOf("split_tunnel", "kill_switch"), d.features)
    }

    @Test
    fun requestHasOnlyNonZeroCountersAndNonEmptyHistograms() {
        val d = ColituMetricsData()
        d.add("connect_attempts", 3)
        d.add("connect_successes", 2)
        d.add("flaps", 0)
        d.observe("connect_ms", 1500)
        d.feature("ad_block")
        val json = d.toRequest("android", "2.9.0", killSwitch = true)

        assertEquals("android", json.get("platform").asString)
        assertEquals("2.9.0", json.get("app_version").asString)
        assertTrue(json.get("kill_switch").asBoolean)
        val counters = json.getAsJsonObject("counters")
        assertEquals(setOf("connect_attempts", "connect_successes"), counters.keySet())
        assertEquals(3, counters.get("connect_attempts").asInt)
        val histograms = json.getAsJsonObject("histograms")
        assertEquals(setOf("connect_ms"), histograms.keySet())
        val buckets = histograms.getAsJsonArray("connect_ms")
        assertEquals(9, buckets.size())
        assertEquals(1, buckets[2].asInt)
        assertEquals("ad_block", json.getAsJsonArray("features")[0].asString)
    }

    @Test
    fun resetAfterASendKeepsWhatCameInDuringTheRequest() {
        val d = ColituMetricsData()
        d.add("connect_attempts", 2)
        d.observe("connect_ms", 400)
        d.feature("shield")
        val sent = d.copy()
        // Recorded while the request was in flight.
        d.add("connect_attempts", 1)
        d.observe("connect_ms", 400)
        d.observe("recovery_ms", 4000)

        d.subtract(sent)

        assertEquals(1, d.counters["connect_attempts"])
        assertEquals(1, d.histograms.getValue("connect_ms")[0])
        assertEquals(1, d.histograms.getValue("recovery_ms")[4])
        assertTrue(d.features.isEmpty())
        d.subtract(d.copy())
        assertTrue(d.isEmpty)
    }

    @Test
    fun storedFormRoundTrips() {
        val d = ColituMetricsData()
        d.add("sessions", 4)
        d.observe("detection_ms", 6000)
        d.feature("multihop")
        val back = ColituMetricsData.fromJson(d.toJson())
        assertEquals(d.counters, back.counters)
        assertEquals(d.histograms.getValue("detection_ms").toList(), back.histograms.getValue("detection_ms").toList())
        assertEquals(d.features, back.features)
        assertTrue(ColituMetricsData.fromJson("garbage").isEmpty)
        assertTrue(ColituMetricsData.fromJson(null).isEmpty)
    }

    @Test
    fun serverAnswersDecideWhatHappensToTheNumbers() {
        assertEquals(ColituMetricsOutcome.Reset, ColituMetrics.outcomeFor(202))
        assertEquals(ColituMetricsOutcome.Reset, ColituMetrics.outcomeFor(400))
        assertEquals(ColituMetricsOutcome.Keep, ColituMetrics.outcomeFor(429))
        assertEquals(ColituMetricsOutcome.Keep, ColituMetrics.outcomeFor(503))
        assertEquals(ColituMetricsOutcome.Keep, ColituMetrics.outcomeFor(-1))
        assertEquals(ColituMetricsOutcome.Keep, ColituMetrics.outcomeFor(null))
    }

    @Test
    fun reportIsDueOnlyWhenEnabledSignedInWithDataAndSixHoursPassed() {
        val h = 60L * 60 * 1000
        val now = 100 * h
        assertTrue(ColituMetrics.due(true, true, true, 0L, now))
        assertTrue(ColituMetrics.due(true, true, true, now - 6 * h, now))
        assertFalse(ColituMetrics.due(true, true, true, now - 5 * h, now))
        assertFalse(ColituMetrics.due(false, true, true, 0L, now))
        assertFalse(ColituMetrics.due(true, false, true, 0L, now))
        assertFalse(ColituMetrics.due(true, true, false, 0L, now))
    }

    @Test
    fun flapIsASwitchBackWithinTwoMinutes() {
        val last = Triple("reality", "hysteria2", 1_000L)
        assertTrue(ColituMetricsRules.isFlap(last, "hysteria2", "reality", 1_000L + 119_000))
        assertFalse(ColituMetricsRules.isFlap(last, "hysteria2", "reality", 1_000L + 121_000))
        assertFalse(ColituMetricsRules.isFlap(last, "hysteria2", "xhttp", 2_000L))
        assertFalse(ColituMetricsRules.isFlap(null, "hysteria2", "reality", 2_000L))
    }
}
