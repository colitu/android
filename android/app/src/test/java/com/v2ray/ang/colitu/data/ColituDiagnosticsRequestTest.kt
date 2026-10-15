package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ColituDiagnosticsRequestTest {
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject

    private val min = 60_000L
    private fun request(id: String = "r1", status: String = "pending", expiresAt: Instant? = null) =
        ColituDiagnosticsRequest(id, status, null, expiresAt)

    // ── Parsing ─────────────────────────────────────────────────────────────

    @Test
    fun parsesAPendingRequestWithoutAConversation() {
        val r = ColituDiagnosticsRequest.parseResponse(
            json("""{"data":{"request":{"id":"r1","status":"pending","created_at":"2026-10-15T10:00:00Z","expires_at":"2026-10-16T10:00:00Z"}}}"""),
        )!!
        assertEquals("r1", r.id)
        assertTrue(r.pending)
        assertEquals(Instant.parse("2026-10-16T10:00:00Z"), r.expiresAt)
        // A leftover conversation_id (null or set) is ignored.
        assertEquals("r2", ColituDiagnosticsRequest.parseResponse(json("""{"data":{"request":{"id":"r2","conversation_id":null,"status":"pending"}}}"""))!!.id)
    }

    @Test
    fun noRequestOrBadShapeIsNull() {
        assertNull(ColituDiagnosticsRequest.parseResponse(json("""{"data":{"request":null}}""")))
        assertNull(ColituDiagnosticsRequest.parseResponse(json("""{"data":{}}""")))
        assertNull(ColituDiagnosticsRequest.parseResponse(json("""{}""")))
        assertNull(ColituDiagnosticsRequest.parseResponse(json("""{"data":{"request":{"status":"pending"}}}""")))
        assertNull(ColituDiagnosticsRequest.parseResponse(json("""{"data":{"request":{"id":"r1","status":"sent"}}}""")))
    }

    @Test
    fun uploadBodyHasTheReportAndVersions() {
        val body = ColituDiagnosticsUpload.body("text", "2.8.6 (1)", "Android 14 (API 34)")
        assertEquals("android", body.get("platform").asString)
        assertEquals("text", body.get("report").asString)
        assertEquals("2.8.6 (1)", body.get("app_version").asString)
        assertEquals("Android 14 (API 34)", body.get("os_version").asString)
        assertFalse(body.has("request_id"))
    }

    // ── Timing and decisions ────────────────────────────────────────────────

    @Test
    fun panelIsAskedAtMostOncePerMinute() {
        var now = 1_000_000L
        val gate = ColituDiagnosticsGate { now }
        assertTrue(gate.tryStartCheck())
        now += 59_999
        assertFalse(gate.tryStartCheck())
        now += 1
        assertTrue(gate.tryStartCheck())
        // A clock set back counts as due.
        now -= 10 * min
        assertTrue(gate.tryStartCheck())
    }

    @Test
    fun aNewRequestIsSentAtOnce() {
        val gate = ColituDiagnosticsGate { 1_000_000L }
        assertTrue(gate.shouldSend(request()))
    }

    @Test
    fun aFailedUploadIsRetriedNoSoonerThanTenMinutes() {
        var now = 5_000_000L
        val gate = ColituDiagnosticsGate { now }
        val r = request()
        gate.markAttempt(r.id)
        assertFalse("one upload at a time", gate.shouldSend(request("r2")))
        now += 30_000 // the upload took a while
        gate.markFailed(r.id)
        assertTrue("another request may go now", gate.shouldSend(request("r2")))
        now += 9 * min + 59_000
        assertFalse(gate.shouldSend(r))
        now += 1_000
        assertTrue(gate.shouldSend(r))
    }

    @Test
    fun sentOrGoneIsNeverSentAgainInThisRun() {
        var now = 1_000_000L
        val gate = ColituDiagnosticsGate { now }
        val r = request()
        gate.markAttempt(r.id)
        gate.markAnswered(r.id) // 204, or 404 "no longer pending"
        now += 24 * 60 * min
        assertFalse(gate.shouldSend(r))
        assertTrue("another request is independent", gate.shouldSend(request("r2")))
    }

    @Test
    fun expiredAndNonPendingRequestsAreSkipped() {
        val now = Instant.parse("2026-10-15T12:00:00Z").toEpochMilli()
        val gate = ColituDiagnosticsGate { now }
        assertFalse(gate.shouldSend(request(expiresAt = Instant.parse("2026-10-15T11:59:59Z"))))
        assertTrue(gate.shouldSend(request(expiresAt = Instant.parse("2026-10-15T12:00:01Z"))))
        assertFalse(gate.shouldSend(request(status = "expired")))
    }

    @Test
    fun resetForgetsEverything() {
        val gate = ColituDiagnosticsGate { 1_000_000L }
        val r = request()
        gate.markAttempt(r.id)
        gate.markAnswered(r.id)
        gate.tryStartCheck()
        gate.reset()
        assertTrue(gate.tryStartCheck())
        assertTrue(gate.shouldSend(r))
    }
}
