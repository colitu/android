package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import com.v2ray.ang.colitu.data.ColituUpsell.FreeState
import com.v2ray.ang.colitu.data.ColituUpsell.Route
import com.v2ray.ang.colitu.l10n.ColituLoc
import com.v2ray.ang.colitu.screens.inviteRewardText
import com.v2ray.ang.colitu.screens.referralShareText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ColituUpsellTest {
    private val gb = 1L shl 30
    private val now = Instant.parse("2026-10-27T12:00:00Z")

    @Test
    fun playBuildNeverLinksToTheWeb() {
        assertEquals(Route.Web, ColituUpsell.route(playPolicy = false, playBilling = false))
        assertEquals(Route.PlayBilling, ColituUpsell.route(playPolicy = true, playBilling = true))
        assertEquals(Route.None, ColituUpsell.route(playPolicy = true, playBilling = false))
    }

    @Test
    fun freeStateFollowsTheAllowance() {
        assertEquals(FreeState.None, ColituUpsell.freeState(false, "active", gb, gb))
        assertEquals(FreeState.Normal, ColituUpsell.freeState(true, "active", 0, gb))
        assertEquals(FreeState.Normal, ColituUpsell.freeState(true, "active", gb * 79 / 100, gb))
        assertEquals(FreeState.Low, ColituUpsell.freeState(true, "active", gb * 80 / 100 + 1, gb))
        assertEquals(FreeState.Exhausted, ColituUpsell.freeState(true, "active", gb, gb))
        assertEquals(FreeState.Exhausted, ColituUpsell.freeState(true, "active", gb + 5, gb))
        // The panel already blocked the period: exhausted even before usage arrived.
        assertEquals(FreeState.Exhausted, ColituUpsell.freeState(true, "quota", 0, gb))
        // No limit sent: no percentage to judge.
        assertEquals(FreeState.Normal, ColituUpsell.freeState(true, "active", gb, null))
    }

    @Test
    fun remainingNeverGoesNegative() {
        assertEquals(gb / 4, ColituUpsell.remainingBytes(gb * 3 / 4, gb))
        assertEquals(0L, ColituUpsell.remainingBytes(gb * 2, gb))
    }

    private fun outlook(endsAt: String, nextPlan: String?) =
        ColituDeviceOutlook(Instant.parse(endsAt), nextPlan, 1, 1, gb)

    @Test
    fun premiumEndingSoonOnlyBeforeTheFreePlan() {
        val soon = outlook("2026-10-28T12:00:00Z", "colitu-free")
        assertTrue(ColituUpsell.premiumEndingSoon("active", false, soon, now))
        assertFalse(ColituUpsell.premiumEndingSoon("trialing", false, soon, now))
        assertFalse(ColituUpsell.premiumEndingSoon("active", true, soon, now))
        assertFalse(ColituUpsell.premiumEndingSoon("active", false, outlook("2026-10-28T12:00:00Z", "personal"), now))
        assertFalse(ColituUpsell.premiumEndingSoon("active", false, outlook("2026-11-05T12:00:00Z", "free"), now))
        assertFalse(ColituUpsell.premiumEndingSoon("active", false, outlook("2026-10-27T11:00:00Z", "free"), now))
        assertFalse(ColituUpsell.premiumEndingSoon("active", false, null, now))
    }

    private fun referral(invitee: Int, reward: Int) = ColituReferral.parse(
        JsonParser.parseString(
            """{"data":{"link":"https://colitu.com/r/AAAAAA","invitee_days":$invitee,"reward_days":$reward}}""",
        ).asJsonObject,
    )!!

    private fun <T> inLanguage(language: String, block: () -> T): T {
        val field = ColituLoc::class.java.getDeclaredField("languageState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(ColituLoc) as androidx.compose.runtime.MutableState<String>
        val previous = state.value
        state.value = language
        return try { block() } finally { state.value = previous }
    }

    @Test
    fun inviteTextUsesTheServerDays() {
        inLanguage("en") {
            assertEquals("You and your friend each get 1 day of Premium", inviteRewardText(referral(1, 1)))
            assertEquals("Your friend gets 3 days of Premium, you get 1 day", inviteRewardText(referral(3, 1)))
            assertEquals("Get 1 gift: 1 day.\nhttps://colitu.com/r/AAAAAA", referralShareText("Get {n} gift: {days}.", referral(1, 1)))
        }
        inLanguage("ru") {
            assertEquals("Вам и другу — 1 день Premium каждому", inviteRewardText(referral(1, 1)))
        }
    }
}
