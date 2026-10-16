package com.v2ray.ang.colitu.update

import com.v2ray.ang.colitu.update.ColituUpdateSignature.Freshness
import com.v2ray.ang.colitu.update.ColituUpdater.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituUpdaterFreshnessTest {
    private val installed = 4029100L

    @Test
    fun freshManifestSaysCurrentOrOffers() {
        assertEquals(Decision.CURRENT, ColituUpdater.decide(Freshness.FRESH, installed, installed, installed))
        assertEquals(Decision.OFFER, ColituUpdater.decide(Freshness.FRESH, installed + 100, installed, installed))
        // Before any v3 manifest was seen (the switch-over), a v2-only manifest is believed.
        assertEquals(Decision.CURRENT, ColituUpdater.decide(Freshness.UNSIGNED_ALLOWED, installed, 0, installed))
    }

    @Test
    fun staleManifestNeverSaysLatestVersion() {
        // Expired, or v3 missing after one was seen: "no update" is not believed.
        assertEquals(Decision.UNVERIFIABLE, ColituUpdater.decide(Freshness.EXPIRED, installed, installed, installed))
        assertEquals(Decision.UNVERIFIABLE, ColituUpdater.decide(Freshness.UNSIGNED_REFUSED, installed, installed, installed))
        // A newer release is still offered: signature_v2, hash and signer protect it.
        assertEquals(Decision.OFFER, ColituUpdater.decide(Freshness.EXPIRED, installed + 100, installed, installed))
        // Older than one seen before (a replay) is unverifiable, fresh or not.
        assertEquals(Decision.UNVERIFIABLE, ColituUpdater.decide(Freshness.FRESH, installed - 100, installed, installed - 100))
    }

    @Test
    fun repeatedUnverifiableChecksBecomeVisibleOncePerDay() {
        val day = ColituUpdater.UNVERIFIED_NOTICE_GAP_MS
        assertFalse(ColituUpdater.shouldShowUnverified(1, 0L, 10 * day))
        assertTrue(ColituUpdater.shouldShowUnverified(2, 0L, 10 * day))
        assertFalse(ColituUpdater.shouldShowUnverified(3, 10 * day, 10 * day + day / 2))
        assertTrue(ColituUpdater.shouldShowUnverified(3, 10 * day, 11 * day))
    }

    @Test
    fun expiredPinsAreRaisedOncePerDay() {
        val day = ColituUpdater.UNVERIFIED_NOTICE_GAP_MS
        assertFalse(ColituUpdater.shouldShowPinsExpired(false, 0L, 10 * day))
        assertTrue(ColituUpdater.shouldShowPinsExpired(true, 0L, 10 * day))
        assertFalse(ColituUpdater.shouldShowPinsExpired(true, 10 * day, 10 * day + day / 2))
        assertTrue(ColituUpdater.shouldShowPinsExpired(true, 10 * day, 11 * day))
    }
}
