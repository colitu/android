package com.v2ray.ang.colitu.data

import com.google.gson.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The panel's credentials_pending reaches every rendered transport. */
class ColituCredentialsPendingTest {
    private val now = ColituWarmSpareFixtures.now

    @Test
    fun absentMeansSettled() {
        val configs = XrayMobileAdapter.renderCandidates(ColituWarmSpareFixtures.envelope("vless-reality"), now)
        assertTrue(configs.isNotEmpty())
        assertFalse(configs.any { it.credentialsPending })
    }

    @Test
    fun pendingIsCarried() {
        val envelope = ColituWarmSpareFixtures.envelope("vless-reality")
        envelope.add("credentials_pending", JsonPrimitive(true))
        assertTrue(XrayMobileAdapter.renderCandidates(envelope, now).all { it.credentialsPending })
    }

    @Test
    fun malformedIsIgnored() {
        val envelope = ColituWarmSpareFixtures.envelope("vless-reality")
        envelope.add("credentials_pending", JsonPrimitive("yes"))
        assertFalse(XrayMobileAdapter.renderCandidates(envelope, now).any { it.credentialsPending })
    }
}
