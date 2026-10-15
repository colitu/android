package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ColituDiagnosticsRequestTest {
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject

    private val min = 60_000L
    private fun request(id: String = "r1", status: String = "pending", expiresAt: Instant? = null) =
        ColituDiagnosticsRequest(id, "c1", status, null, expiresAt)

    // ── Parsing ─────────────────────────────────────────────────────────────

    @Test
    fun parsesAPendingRequest() {
        val r = ColituDiagnosticsRequest.parseResponse(
            json("""{"data":{"request":{"id":"r1","conversation_id":"c9","status":"pending","created_at":"2026-10-15T10:00:00Z","expires_at":"2026-10-16T10:00:00Z"}}}"""),
        )!!
        assertEquals("r1", r.id)
        assertEquals("c9", r.conversationId)
        assertTrue(r.pending)
        assertEquals(Instant.parse("2026-10-16T10:00:00Z"), r.expiresAt)
    }

    @Test
    fun noRequestOrBadShapeIsNull() {
        assertNull(ColituDiagnosticsRequest.parseResponse(json("""{"data":{"request":null}}""")))
        assertNull(ColituDiagnosticsRequest.parseResponse(json("""{"data":{}}""")))
        assertNull(ColituDiagnosticsRequest.parseResponse(json("""{}""")))
        assertNull(ColituDiagnosticsRequest.parseResponse(json("""{"data":{"request":{"id":"r1"}}}""")))
        assertNull(ColituDiagnosticsRequest.parseResponse(json("""{"data":{"request":{"id":"r1","conversation_id":"c","status":"declined"}}}""")))
    }

    @Test
    fun uploadBodyCarriesRequestIdOnlyWhenAnswering() {
        val answer = ColituDiagnosticsUpload.body("r1", "text", "2.8.6 (1)", "Android 14 (API 34)")
        assertEquals("r1", answer.get("request_id").asString)
        assertEquals("android", answer.get("platform").asString)
        assertEquals("text", answer.get("report").asString)
        assertEquals("2.8.6 (1)", answer.get("app_version").asString)
        assertEquals("Android 14 (API 34)", answer.get("os_version").asString)

        assertFalse(ColituDiagnosticsUpload.body(null, "t", "v", "o").has("request_id"))
        assertFalse(ColituDiagnosticsUpload.body("", "t", "v", "o").has("request_id"))
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
    fun aNewRequestIsAskedOnceThenTenMinutesLater() {
        var now = 1_000_000L
        val gate = ColituDiagnosticsGate { now }
        val r = request()
        assertTrue(gate.shouldAsk(r))
        gate.markShown(r.id)
        assertFalse("dialog is up", gate.shouldAsk(r))

        now += 2 * min
        gate.markLater(r.id) // dismissed
        assertFalse(gate.shouldAsk(r))
        now += 9 * min + 59_000
        assertFalse(gate.shouldAsk(r))
        now += 1_000
        assertTrue(gate.shouldAsk(r))
    }

    @Test
    fun aFailedUploadWaitsTenMinutesFromTheFailure() {
        var now = 5_000_000L
        val gate = ColituDiagnosticsGate { now }
        val r = request()
        gate.markShown(r.id)
        now += 30 * min // the dialog stayed open a long time
        gate.markLater(r.id) // upload failed
        now += 5 * min
        assertFalse(gate.shouldAsk(r))
        now += 5 * min
        assertTrue(gate.shouldAsk(r))
    }

    @Test
    fun sentOrDeclinedIsNeverAskedAgainInThisRun() {
        var now = 1_000_000L
        val gate = ColituDiagnosticsGate { now }
        val r = request()
        gate.markShown(r.id)
        gate.markAnswered(r.id)
        now += 24 * 60 * min
        assertFalse(gate.shouldAsk(r))
        assertTrue("another request is independent", gate.shouldAsk(request("r2")))
    }

    @Test
    fun expiredAndNonPendingRequestsAreSkipped() {
        val now = Instant.parse("2026-10-15T12:00:00Z").toEpochMilli()
        val gate = ColituDiagnosticsGate { now }
        assertFalse(gate.shouldAsk(request(expiresAt = Instant.parse("2026-10-15T11:59:59Z"))))
        assertTrue(gate.shouldAsk(request(expiresAt = Instant.parse("2026-10-15T12:00:01Z"))))
        assertFalse(gate.shouldAsk(request(status = "expired")))
        assertNotNull(gate)
    }

    @Test
    fun resetForgetsEverything() {
        var now = 1_000_000L
        val gate = ColituDiagnosticsGate { now }
        val r = request()
        gate.markShown(r.id)
        gate.markAnswered(r.id)
        gate.tryStartCheck()
        gate.reset()
        assertTrue(gate.tryStartCheck())
        assertTrue(gate.shouldAsk(r))
    }
}
