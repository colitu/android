package com.v2ray.ang.colitu.l10n

import com.v2ray.ang.colitu.app.planStatusOf
import com.v2ray.ang.colitu.data.ColituSubscription
import com.v2ray.ang.colitu.data.ColituUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ColituLocTest {
    private fun <T> inLanguage(language: String, block: () -> T): T {
        val field = ColituLoc::class.java.getDeclaredField("languageState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(ColituLoc) as androidx.compose.runtime.MutableState<String>
        val previous = state.value
        state.value = language
        return try { block() } finally { state.value = previous }
    }

    @Test
    fun russianPluralsFollowTheNumber() = inLanguage("ru") {
        assertEquals("1 день", ColituLoc.count("day", 1))
        assertEquals("3 дня", ColituLoc.count("day", 3))
        assertEquals("11 дней", ColituLoc.count("day", 11))
        assertEquals("22 устройства", ColituLoc.count("device", 22))
    }

    @Test
    fun moneyMatchesTheWebsite() {
        inLanguage("ru") { assertEquals("1 290 ₽", ColituLoc.money(129_000)) }
        inLanguage("en") { assertEquals("1,290.50 ₽", ColituLoc.money(129_050)) }
    }

    @Test
    fun speedUsesDecimalUnits() = inLanguage("tr") {
        assertEquals("1,5 MB/s", ColituLoc.speed(1_500_000.0))
        assertEquals("0 B/s", ColituLoc.speed(0.0))
    }

    @Test
    fun everyLanguageHasItsOwnWording() {
        val tr = inLanguage("tr") { ColituLoc["home.connect"] }
        val ru = inLanguage("ru") { ColituLoc["home.connect"] }
        assertEquals("Bağlan", tr)
        assertNotEquals(tr, ru)
        assertEquals("missing.key", ColituLoc["missing.key"])
    }

    @Test
    fun androidWordingReplacesIphoneMentions() {
        for (language in ColituLoc.languages) {
            inLanguage(language) {
                for (key in listOf("auth.remember", "account.signOutConfirm", "onb.4.sub", "settings.alwaysOnHint")) {
                    val text = ColituLoc[key]
                    assert(!text.contains("iPhone") && !text.contains("iOS")) { "$language/$key: $text" }
                }
            }
        }
    }

    @Test
    fun errorCodesMapToSentences() = inLanguage("en") {
        assertEquals(ColituLoc["err.credentials"], colituErrorMessage("LOGIN_FAILED", signingIn = true))
        assertEquals(ColituLoc["err.noPlan"], colituErrorMessage("ENTITLEMENT_INACTIVE"))
        assertEquals(ColituLoc["err.network"], colituErrorMessage("timeout"))
        assertEquals(ColituLoc["err.generic"], colituErrorMessage("something_new"))
    }

    @Test
    fun planStatusTreatsPastExpiryAsExpired() {
        val user = ColituUser(entitlementStatus = "active")
        val past = ColituSubscription(true, "Colitu", "p", "active", "2020-01-01T00:00:00Z")
        val future = ColituSubscription(true, "Colitu", "p", "active", "2999-01-01T00:00:00Z")
        assertEquals("expired", planStatusOf(user, past))
        assertEquals("active", planStatusOf(user, future))
        assertEquals("inactive", planStatusOf(null, null))
        assertEquals("trialing", planStatusOf(ColituUser(entitlementStatus = "trialing"), null))
    }
}
