package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ClientBootstrapPolicyTest {
    private fun policy(status: String = "active", required: Boolean = false) = ClientBootstrapPolicy.fromJson(
        JsonParser.parseString("""{"entitlement":{"status":"$status"},"app_policy":{"update_required":$required,"update_recommended":true,"recommended_version":"2.0.0"},"maintenance":{"active":false}}""").asJsonObject)

    @Test fun trialAndPaidCanConnect() {
        assertNull(policy().denial)
        assertNull(policy("trialing").denial)
        assertEquals("trialing", policy("trialing").entitlementStatus)
    }
    @Test fun requiredUpdateBlocksTrialAndPaid() {
        assertEquals("APP_UPDATE_REQUIRED", policy(required=true).denial)
        assertEquals("APP_UPDATE_REQUIRED", policy("trialing", true).denial)
    }
    @Test fun expiredAndRevokedCannotConnect() {
        for (status in listOf("expired","disabled","revoked","quota_exceeded"))
            assertEquals("ENTITLEMENT_INACTIVE", policy(status).denial)
    }
    @Test fun recommendedUpdateDoesNotBlock() {
        assertNull(policy().denial)
        assertEquals("2.0.0", policy().recommendedVersion)
    }
}
