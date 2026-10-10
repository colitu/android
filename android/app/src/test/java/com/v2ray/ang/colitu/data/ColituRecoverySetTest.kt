package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituRecoverySetTest {
    private val hour = 3_600_000L
    private val now = Instant.parse("2026-10-10T12:00:00Z")

    private fun envelope(server: String, protocol: String = "vless-reality", grace: String = "2026-10-09T00:00:00Z") =
        """{"revision":7,"expires_at":"2026-10-08T00:00:00Z","offline_grace_until":"$grace",
            "server":{"id":"$server","country":"de"},
            "profile":{"format":"xray-mobile-v1","payload":{"schema_version":1,"protocol":"$protocol",
              "endpoint":{"host":"$server.example.test","port":443},
              "credentials":{"uuid":"11111111-1111-4111-8111-111111111111"},
              "transport":{"type":"tcp"},
              "security":{"type":"reality","server_name":"cdn.example.test","public_key":"pk","short_id":"ab","fingerprint":"chrome"}}}}"""

    private fun raw(
        servers: List<String> = listOf("s1", "s2", "s3"),
        generated: String = "2026-10-10T10:00:00Z",
        until: String = "2026-10-24T10:00:00Z",
    ) = """{"generated_at":"$generated","recovery_until":"$until","configs":[${servers.joinToString(",") { envelope(it) }}]}"""

    @Test
    fun parsesAValidSet() {
        val set = ColituRecoverySet.parse(raw())!!
        assertEquals(Instant.parse("2026-10-24T10:00:00Z"), set.recoveryUntil)
        assertEquals(Instant.parse("2026-10-10T10:00:00Z"), set.generatedAt)
        assertEquals(listOf("s1", "s2", "s3"), set.configs.map { ColituRecoverySet.serverId(it) })
    }

    @Test
    fun rejectsBrokenSets() {
        assertNull(ColituRecoverySet.parse(null))
        assertNull(ColituRecoverySet.parse("not json"))
        assertNull(ColituRecoverySet.parse("""{"generated_at":"2026-10-10T10:00:00Z","recovery_until":"2026-10-24T10:00:00Z","configs":[]}"""))
        assertNull(ColituRecoverySet.parse(raw(until = "soon")))
        assertNull(ColituRecoverySet.parse(raw().replace("\"recovery_until\"", "\"x\"")))
        assertNull(ColituRecoverySet.parse(raw(generated = "")))
        // Envelopes without a server id do not count.
        assertNull(ColituRecoverySet.parse("""{"generated_at":"2026-10-10T10:00:00Z","recovery_until":"2026-10-24T10:00:00Z","configs":[{"revision":1}]}"""))
    }

    @Test
    fun keepsAtMostFourEnvelopes() {
        val set = ColituRecoverySet.parse(raw(servers = listOf("a", "b", "c", "d", "e")))!!
        assertEquals(listOf("a", "b", "c", "d"), set.configs.map { ColituRecoverySet.serverId(it) })
    }

    @Test
    fun aSetPastRecoveryUntilIsExpired() {
        val set = ColituRecoverySet.parse(raw())!!
        assertFalse(ColituRecoverySet.isExpired(set, Instant.parse("2026-10-24T09:59:59Z")))
        assertTrue(ColituRecoverySet.isExpired(set, Instant.parse("2026-10-24T10:00:00Z")))
    }

    @Test
    fun usedOnlyWhenTheApiFailureIsNetworkLevelAndTheCacheIsUnusable() {
        assertTrue(ColituRecoverySet.shouldUse(automatic = true, apiNetworkLevelFailure = true, cacheUsable = false))
        assertFalse(ColituRecoverySet.shouldUse(automatic = true, apiNetworkLevelFailure = true, cacheUsable = true))
        assertFalse(ColituRecoverySet.shouldUse(automatic = true, apiNetworkLevelFailure = false, cacheUsable = false))
        // A chosen server or a multihop route never switches.
        assertFalse(ColituRecoverySet.shouldUse(automatic = false, apiNetworkLevelFailure = true, cacheUsable = false))
        // No HTTP answer at all is code -1; any answer (5xx, 401, 403, 404) is not network-level.
        assertTrue(ColituRecoverySet.isNetworkLevel(-1))
        listOf(401, 403, 404, 429, 500, 502, 503, 504).forEach { assertFalse(ColituRecoverySet.isNetworkLevel(it)) }
    }

    @Test
    fun theSetGetsOneRunBeforeAnyFinalFailureWhileTheApiIsUnreachable() {
        // Whatever the error (VERIFY_FAILED, Fatal, every server failed, budget used up) the decision is the same.
        assertTrue(ColituRecoverySet.shouldTryAtGiveUp(automatic = true, apiUnreachable = true, alreadyTried = false))
        assertFalse(ColituRecoverySet.shouldTryAtGiveUp(automatic = true, apiUnreachable = true, alreadyTried = true))
        assertFalse(ColituRecoverySet.shouldTryAtGiveUp(automatic = true, apiUnreachable = false, alreadyTried = false))
        assertFalse(ColituRecoverySet.shouldTryAtGiveUp(automatic = false, apiUnreachable = true, alreadyTried = false))
    }

    @Test
    fun serversAreTriedInOrderSkippingFailedOnes() {
        val set = ColituRecoverySet.parse(raw())!!
        assertEquals(listOf("s1", "s2", "s3"), ColituRecoverySet.serversToTry(set, emptyList()).map { ColituRecoverySet.serverId(it) })
        assertEquals(listOf("s1", "s3"), ColituRecoverySet.serversToTry(set, listOf("s2")).map { ColituRecoverySet.serverId(it) })
        assertTrue(ColituRecoverySet.serversToTry(set, listOf("s1", "s2", "s3", "other")).isEmpty())
    }

    @Test
    fun authAnswersDeleteTheSetNetworkErrorsAndServerErrorsKeepIt() {
        assertEquals(ColituRecoverySet.FetchError.DeleteSet, ColituRecoverySet.onFetchError(401, isAuthError = true))
        assertEquals(ColituRecoverySet.FetchError.DeleteSet, ColituRecoverySet.onFetchError(401, isAuthError = false))
        assertEquals(ColituRecoverySet.FetchError.DeleteSet, ColituRecoverySet.onFetchError(403, isAuthError = false))
        assertEquals(ColituRecoverySet.FetchError.KeepSet, ColituRecoverySet.onFetchError(-1, isAuthError = false))
        assertEquals(ColituRecoverySet.FetchError.KeepSet, ColituRecoverySet.onFetchError(500, isAuthError = false))
        assertEquals(ColituRecoverySet.FetchError.KeepSet, ColituRecoverySet.onFetchError(503, isAuthError = false))
    }

    @Test
    fun refreshIsDueAfter24hAndAtMostEvery6h() {
        val nowMs = now.toEpochMilli()
        val fresh = ColituRecoverySet.parse(raw(generated = "2026-10-10T10:00:00Z"))!! // 2 h old
        val old = ColituRecoverySet.parse(raw(generated = "2026-10-09T11:00:00Z"))!! // 25 h old
        val edge = ColituRecoverySet.parse(raw(generated = "2026-10-09T12:00:00Z"))!! // exactly 24 h
        assertFalse(ColituRecoverySet.refreshDue(fresh, 0L, nowMs))
        assertTrue(ColituRecoverySet.refreshDue(edge, 0L, nowMs))
        assertTrue(ColituRecoverySet.refreshDue(old, 0L, nowMs))
        // No stored set: due, but not within 6 h of the last attempt.
        assertTrue(ColituRecoverySet.refreshDue(null, 0L, nowMs))
        assertFalse(ColituRecoverySet.refreshDue(null, nowMs - 5 * hour, nowMs))
        assertTrue(ColituRecoverySet.refreshDue(null, nowMs - 6 * hour, nowMs))
        assertFalse(ColituRecoverySet.refreshDue(old, nowMs - hour, nowMs))
        assertTrue(ColituRecoverySet.refreshDue(old, nowMs - 7 * hour, nowMs))
        // A clock that went back does not block the fetch.
        assertTrue(ColituRecoverySet.refreshDue(null, nowMs + hour, nowMs))
    }

    @Test
    fun envelopesRenderUntilRecoveryUntilIgnoringTheirOwnGrace() {
        val set = ColituRecoverySet.parse(raw())!!
        val envelope = set.configs.first()
        // The envelope's own grace ended on 2026-10-09; as a cached config it is dead.
        val own = runCatching { XrayMobileAdapter.renderCandidates(envelope, now) }
        assertTrue(own.isFailure)
        // As a recovery envelope it renders, and recovery_until is its grace.
        val configs = XrayMobileAdapter.renderCandidates(envelope, now, set.recoveryUntil)
        assertEquals(listOf("vless-reality"), configs.map { it.protocolType })
        assertEquals("s1", configs.first().serverId)
        assertEquals(set.recoveryUntil.toString(), configs.first().offlineGraceUntil)
        assertNotNull(configs.first().rawConfig)
        // Past recovery_until nothing renders.
        assertTrue(runCatching { XrayMobileAdapter.renderCandidates(envelope, set.recoveryUntil, set.recoveryUntil) }.isFailure)
    }

    @Test
    fun theSetKeepsTheEnvelopeJsonAsReceived() {
        val set = ColituRecoverySet.parse(raw())!!
        assertEquals(JsonParser.parseString(envelope("s2")), set.configs[1])
    }
}
