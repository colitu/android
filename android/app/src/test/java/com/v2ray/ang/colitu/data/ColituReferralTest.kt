package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import com.v2ray.ang.colitu.repository.ColituAuthRepository
import com.v2ray.ang.colitu.screens.referralShareText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituReferralTest {
    @Test
    fun codeIsUpperCasedAndKeptToLettersAndDigits() {
        assertEquals("AB12CD", ColituInvite.normalize(" ab-12 cd "))
        assertEquals("ABCDEFGHIJKL", ColituInvite.sanitize("abcdefghijklmnop"))
    }

    @Test
    fun codeOutsideSixToTwelveCharactersIsNotSent() {
        assertNull(ColituInvite.normalize(""))
        assertNull(ColituInvite.normalize(null))
        assertNull(ColituInvite.normalize("ABC12"))
        assertEquals("ABC123", ColituInvite.normalize("abc123"))
    }

    @Test
    fun inviteLinkYieldsItsCode() {
        assertEquals("K7M2QX", ColituInvite.fromParts("https", "colitu.com", listOf("r", "k7m2qx")))
        assertEquals("K7M2QX", ColituInvite.fromParts("HTTPS", "www.colitu.com", listOf("r", "K7M2QX")))
    }

    @Test
    fun otherLinksYieldNothing() {
        assertNull(ColituInvite.fromParts("http", "colitu.com", listOf("r", "K7M2QX")))
        assertNull(ColituInvite.fromParts("https", "evil.example", listOf("r", "K7M2QX")))
        assertNull(ColituInvite.fromParts("https", "colitu.com", listOf("download", "K7M2QX")))
        assertNull(ColituInvite.fromParts("https", "colitu.com", listOf("r")))
        assertNull(ColituInvite.fromParts("https", "colitu.com", listOf("r", "ab")))
    }

    @Test
    fun registerBodyCarriesSourceAndOnlyAUsableInvite() {
        val with = ColituAuthRepository.registerBody("a@b.co", "pw", "tr", "k7m2qx")
        assertEquals("app:android", with.get("source").asString)
        assertEquals("K7M2QX", with.get("invite").asString)
        assertEquals("tr", with.get("locale").asString)

        for (none in listOf(null, "", "  ", "abc")) {
            val without = ColituAuthRepository.registerBody("a@b.co", "pw", "tr", none)
            assertEquals("app:android", without.get("source").asString)
            assertFalse(without.has("invite"))
        }
    }

    @Test
    fun referralParsesTheDataEnvelope() {
        val json = JsonParser.parseString(
            """{"data":{"code":"K7M2QX","link":"https://colitu.com/r/K7M2QX","invitee_days":7,"reward_days":7,
            "monthly_cap":6,"invited":5,"pending":2,"rewarded":3,"rejected":0,"days_earned":21,"rewarded_this_month":4,
            "recent":[{"status":"rewarded","days":7,"created_at":"2026-10-11T10:00:00Z"},{"status":"rejected","reason":"same_device","days":0}]}}""",
        ).asJsonObject
        val referral = ColituReferral.parse(json)
        assertNotNull(referral)
        referral!!
        assertEquals("https://colitu.com/r/K7M2QX", referral.link)
        assertEquals(5, referral.invited)
        assertEquals(2, referral.pending)
        assertEquals(3, referral.rewarded)
        assertEquals(21, referral.daysEarned)
        assertEquals(2, referral.left)
        assertEquals(2, referral.recent.size)
        assertEquals("same_device", referral.recent[1].reason)
    }

    @Test
    fun leftNeverGoesNegativeAndNoLinkIsNoReferral() {
        val over = JsonParser.parseString("""{"data":{"link":"https://colitu.com/r/AAAAAA","monthly_cap":6,"rewarded_this_month":9}}""").asJsonObject
        assertEquals(0, ColituReferral.parse(over)!!.left)
        assertNull(ColituReferral.parse(JsonParser.parseString("""{"data":{"code":"X"}}""").asJsonObject))
        assertNull(ColituReferral.parse(null))
    }

    @Test
    fun shareTextEndsWithTheLink() {
        val referral = ColituReferral.parse(
            JsonParser.parseString("""{"data":{"link":"https://colitu.com/r/AAAAAA","invitee_days":7}}""").asJsonObject,
        )!!
        val text = referralShareText("Colitu'yu deniyorum; {n} gün Premium hediye.", referral)
        assertEquals("Colitu'yu deniyorum; 7 gün Premium hediye.\nhttps://colitu.com/r/AAAAAA", text)
        assertTrue(text.endsWith(referral.link))
    }
}
