package com.v2ray.ang.colitu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituRecoveryTestModeTest {
    private val bases = listOf("https://api.colitu.com/api/v1", "https://mirror.example.test/capi/v1")

    @Test
    fun withTheFlagOffNothingChanges() {
        assertEquals(bases, ColituRecoveryTestMode.apiBases(bases, enabled = false))
        assertEquals(bases[0], ColituRecoveryTestMode.preferredBase(bases[0], enabled = false))
        assertTrue(ColituRecoveryTestMode.endpointListRefresh(true, enabled = false))
        assertFalse(ColituRecoveryTestMode.endpointListRefresh(false, enabled = false))
        assertEquals("cached", ColituRecoveryTestMode.regularCache("cached", enabled = false))
        assertNull(ColituRecoveryTestMode.regularCache<String>(null, enabled = false))
    }

    @Test
    fun withTheFlagOnTheApiIsBlackholedAndTheRegularCacheIgnored() {
        assertEquals(listOf("https://10.255.255.1/api/v1"), ColituRecoveryTestMode.apiBases(bases, enabled = true))
        assertEquals("https://10.255.255.1/api/v1", ColituRecoveryTestMode.preferredBase(bases[0], enabled = true))
        assertFalse(ColituRecoveryTestMode.endpointListRefresh(true, enabled = true))
        assertNull(ColituRecoveryTestMode.regularCache("cached", enabled = true))
    }

    @Test
    fun shippedBuildsHaveTheFlagOff() {
        // The unit-test build is not started with -PcolituRecoveryTest.
        assertFalse(ColituRecoveryTestMode.enabled)
    }
}
