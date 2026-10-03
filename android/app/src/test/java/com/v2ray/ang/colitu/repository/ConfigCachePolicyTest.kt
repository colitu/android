package com.v2ray.ang.colitu.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigCachePolicyTest {
    @Test
    fun cachedCredentialsCannotOverrideRejection() {
        for (status in listOf(400, 401, 403, 404, 409, 410, 422, 429, 500)) {
            assertFalse("status $status", ConfigCachePolicy.allowsFallback(status, false))
        }
    }

    @Test
    fun temporaryOutageCanUseValidatedOfflineGrace() {
        for (status in listOf(-1, 502, 503, 504)) {
            assertTrue("status $status", ConfigCachePolicy.allowsFallback(status, false))
            assertFalse("auth overrides $status", ConfigCachePolicy.allowsFallback(status, true))
        }
    }
}
