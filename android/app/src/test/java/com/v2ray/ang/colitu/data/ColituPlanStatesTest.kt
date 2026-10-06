package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import com.v2ray.ang.colitu.repository.ColituAuthRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class ColituPlanStatesTest {
    private val now = Instant.parse("2026-10-06T12:00:00Z")

    private fun bootstrap(endsAt: String, nextPlan: String?, registered: Int) = JsonParser.parseString(
        """{"app_policy":{"update_required":false},
            "entitlement":{"status":"trialing","ends_at":"$endsAt",
              "next_plan":${nextPlan?.let { "\"$it\"" } ?: "null"},"next_device_limit":1,
              "devices":{"active":$registered,"suspended":0,"registered":$registered,"limit":5}}}""",
    ).asJsonObject

    @Test
    fun outlookIsReadFromTheBootstrapEntitlement() {
        val outlook = ColituDeviceOutlook.fromJson(bootstrap("2026-10-08T12:00:00Z", "free", 3))!!
        assertEquals(Instant.parse("2026-10-08T12:00:00Z"), outlook.endsAt)
        assertEquals(1, outlook.nextDeviceLimit)
        assertEquals(3, outlook.registeredDevices)
        assertTrue(outlook.nextIsFree)
        assertTrue(outlook.willPauseDevices)
        assertTrue(outlook.trialEndingSoon("trialing", now))
    }

    @Test
    fun bannerOnlyForATrialEndingWithinThreeDaysIntoTheFreePlan() {
        assertFalse(ColituDeviceOutlook.fromJson(bootstrap("2026-10-10T12:00:01Z", "free", 3))!!.trialEndingSoon("trialing", now))
        assertFalse(ColituDeviceOutlook.fromJson(bootstrap("2026-10-08T12:00:00Z", "premium-month", 3))!!.trialEndingSoon("trialing", now))
        assertFalse(ColituDeviceOutlook.fromJson(bootstrap("2026-10-08T12:00:00Z", null, 3))!!.trialEndingSoon("trialing", now))
        assertFalse(ColituDeviceOutlook.fromJson(bootstrap("2026-10-08T12:00:00Z", "free", 3))!!.trialEndingSoon("active", now))
        assertFalse(ColituDeviceOutlook.fromJson(bootstrap("2026-10-06T11:00:00Z", "free", 3))!!.trialEndingSoon("trialing", now))
        val single = ColituDeviceOutlook.fromJson(bootstrap("2026-10-07T12:00:00Z", "free", 1))!!
        assertTrue(single.trialEndingSoon("trialing", now, Duration.ofDays(3)))
        assertFalse(single.willPauseDevices)
    }

    @Test
    fun overLimitBodyListsTheActiveDevices() {
        val body = JsonParser.parseString(
            """{"error":{"code":"DEVICE_OVER_LIMIT"},"device_limit":1,
                "active_devices":[{"id":"d1","name":"Phone","platform":"android","last_seen_at":"2026-10-06T10:00:00Z"}]}""",
        ).asJsonObject
        val pause = ColituDevicePause.fromJson(body)
        assertEquals(1, pause.deviceLimit)
        assertEquals("Phone", pause.activeDevices.single().name)
        assertEquals(Instant.parse("2026-10-06T10:00:00Z"), pause.activeDevices.single().lastSeenAt)
        assertNull(ColituDevicePause.fromJson(null).deviceLimit)
    }

    @Test
    fun suspendedDevicesAreMarked() {
        val device = ColituDevice.fromJson(JsonParser.parseString("""{"id":"d2","name":"Tablet","suspended_at":"2026-10-06T10:00:00Z","suspended_reason":"over_limit"}""").asJsonObject)!!
        assertTrue(device.suspended)
        assertFalse(ColituDevice.fromJson(JsonParser.parseString("""{"id":"d3","name":"Tv","suspended_at":null}""").asJsonObject)!!.suspended)
    }

    @Test
    fun mfaChallengeAndCodes() {
        val body = JsonParser.parseString("""{"error":{"code":"MFA_REQUIRED"},"mfa_token":"tok","mfa_expires_in":300}""").asJsonObject
        val challenge = ColituAuthRepository.mfaChallengeOf(body, "a@b.c", sendCode = true, nowMs = 1_000L)
        assertNotNull(challenge)
        assertEquals("tok", challenge!!.token)
        assertEquals(301_000L, challenge.expiresAtMs)
        assertNull(ColituAuthRepository.mfaChallengeOf(JsonParser.parseString("""{"error":{"code":"MFA_REQUIRED"}}""").asJsonObject, "", false))

        assertEquals("123456", ColituAuthRepository.normalizeMfaCode("123 456", recovery = false))
        assertNull(ColituAuthRepository.normalizeMfaCode("12345", recovery = false))
        assertEquals("abcd2345ef", ColituAuthRepository.normalizeMfaCode(" ABCD-2345-EF ", recovery = true))
        assertNull(ColituAuthRepository.normalizeMfaCode("ab-c", recovery = true))
    }
}
