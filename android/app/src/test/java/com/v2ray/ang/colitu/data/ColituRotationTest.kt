package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import com.v2ray.ang.colitu.data.ColituRotation.Validation
import com.v2ray.ang.colitu.l10n.ColituLoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Panel contract of 2026-10-06, section 4: the rotating exit IP. */
class ColituRotationTest {
    private val available = listOf(
        ColituRotationCountry("DE", inDefault = true, exits = 1),
        ColituRotationCountry("NL", inDefault = true, exits = 2),
        ColituRotationCountry("RU", inDefault = false, exits = 1),
        ColituRotationCountry("SE", inDefault = true, exits = 0),
    )

    @Test
    fun thePreferenceIsParsed() {
        val json = JsonParser.parseString(
            """{"rotation":{"interval_seconds":600,"countries":["nl","DE"],"intervals":[1800,300,600],
                "available_countries":[{"country":"DE","in_default":true,"exits":1},{"country":"ru","in_default":false,"exits":1}],
                "protocols":["vless-reality","vless-xhttp"],"changes_exit_country":true}}""",
        ).asJsonObject

        val preference = ColituRotation.parsePreference(json)

        assertEquals(600, preference.intervalSeconds)
        assertTrue(preference.active)
        assertEquals(listOf("DE", "NL"), preference.countries)
        assertEquals(listOf(300, 600, 1800), preference.intervals)
        assertEquals(listOf("DE", "RU"), preference.availableCountries.map { it.country })
        assertFalse(preference.availableCountries.single { it.country == "RU" }.inDefault)
        assertEquals(listOf("vless-reality", "vless-xhttp"), preference.protocols)
        assertTrue(preference.changesExitCountry)
    }

    @Test
    fun aDefaultResponseIsOffWithTheDefaultSet() {
        val preference = ColituRotation.parsePreference(
            JsonParser.parseString(
                """{"rotation":{"interval_seconds":0,"countries":[],"available_countries":[{"country":"DE","in_default":true,"exits":1},{"country":"NL","in_default":true,"exits":1},{"country":"RU","in_default":false,"exits":1}]}}""",
            ).asJsonObject,
        )

        assertFalse(preference.active)
        assertEquals(listOf("DE", "NL"), ColituRotation.selectedCountries(preference))
    }

    @Test
    fun anUnknownIntervalFromThePanelReadsAsOff() {
        val preference = ColituRotation.parsePreference(JsonParser.parseString("""{"rotation":{"interval_seconds":42}}""").asJsonObject)

        assertEquals(0, preference.intervalSeconds)
        assertFalse(ColituRotation.parsePreference(JsonParser.parseString("{}").asJsonObject).active)
    }

    @Test
    fun theStatusIsParsed() {
        val json = JsonParser.parseString(
            """{"status":{"active":true,"reason":"","interval_seconds":600,
                "entry":{"node_id":"e","name":"Helsinki","country":"FI","city":"Helsinki"},
                "current_exit":{"node_id":"x","name":"Frankfurt","country":"de","city":"Frankfurt"},
                "next_exit":{"node_id":"y","country":"NL"},
                "window_started_at":"2026-10-06T10:00:00Z","next_change_at":"2026-10-06T10:10:00Z",
                "exit_set":["x","y"],"protocols":["vless-reality"]}}""",
        ).asJsonObject

        val status = ColituRotation.parseStatus(json)!!

        assertTrue(status.active)
        assertEquals(600, status.intervalSeconds)
        assertEquals("FI", status.entry?.country)
        assertEquals("DE", status.currentExit?.country)
        assertEquals("Frankfurt", status.currentExit?.label)
        assertEquals("NL", status.nextExit?.country)
        assertEquals(Instant.parse("2026-10-06T10:10:00Z"), status.nextChangeAt)
        assertEquals(Instant.parse("2026-10-06T10:00:00Z"), status.windowStartedAt)
        assertEquals(listOf("vless-reality"), status.protocols)
    }

    @Test
    fun anInactiveStatusKeepsTheReasonAndToleratesOddShapes() {
        val status = ColituRotation.parseStatus(
            JsonParser.parseString(
                """{"status":{"active":false,"reason":"not_in_mesh","interval_seconds":300,"entry":"Helsinki","current_exit":null,"next_change_at":"nonsense"}}""",
            ).asJsonObject,
        )!!

        assertFalse(status.active)
        assertEquals("not_in_mesh", status.reason)
        assertEquals("Helsinki", status.entry?.name)
        assertNull(status.currentExit)
        assertNull(status.nextChangeAt)
        assertNull(ColituRotation.parseStatus(JsonParser.parseString("{}").asJsonObject))
    }

    @Test
    fun theIntervalIsOffOr5Or10Or30Minutes() {
        listOf(0, 300, 600, 1800).forEach { assertTrue("$it", ColituRotation.isValidInterval(it)) }
        listOf(60, 900, -300, 123).forEach { assertFalse("$it", ColituRotation.isValidInterval(it)) }
    }

    @Test
    fun noCountriesMeansTheDefaultSet() {
        assertEquals(Validation.Ok, ColituRotation.validate(600, emptyList(), available))
        assertEquals(Validation.Ok, ColituRotation.validate(600, null, available))
    }

    @Test
    fun atLeastTwoCountriesWithExitsAreNeeded() {
        assertEquals(Validation.Ok, ColituRotation.validate(600, listOf("DE", "NL"), available))
        assertEquals(Validation.Ok, ColituRotation.validate(600, listOf("DE", "RU"), available))
        assertEquals(Validation.TooFewCountries, ColituRotation.validate(600, listOf("DE"), available))
        // SE has no exits and FR is not offered: neither counts.
        assertEquals(Validation.TooFewCountries, ColituRotation.validate(600, listOf("DE", "SE"), available))
        assertEquals(Validation.TooFewCountries, ColituRotation.validate(600, listOf("de", "FR"), available))
        // Off never needs countries.
        assertEquals(Validation.Ok, ColituRotation.validate(0, listOf("DE"), available))
    }

    @Test
    fun anIntervalTheMenuDoesNotOfferIsRefused() {
        assertEquals(Validation.InvalidInterval, ColituRotation.validate(123, emptyList(), available))
    }

    @Test
    fun countriesAreNormalized() {
        assertEquals(listOf("DE", "GB", "NL"), ColituRotation.normalizeCountries(listOf(" nl ", "DE", "de", "uk", "X", "123", "", "R U", null)))
        assertTrue(ColituRotation.normalizeCountries(null).isEmpty())
    }

    @Test
    fun theDefaultSetExcludesRussiaAndCountriesWithoutExits() {
        assertEquals(listOf("DE", "NL"), ColituRotation.defaultCountries(available))
    }

    @Test
    fun theDefaultSetIsSentAsAnEmptyList() {
        assertTrue(ColituRotation.payloadCountries(listOf("NL", "DE"), available).isEmpty())
        assertEquals(listOf("DE", "NL", "RU"), ColituRotation.payloadCountries(listOf("DE", "NL", "RU"), available))
        assertEquals(listOf("DE", "RU"), ColituRotation.payloadCountries(listOf("DE", "RU"), available))
    }

    @Test
    fun theStatusPollWaitsForTheNextChangeButNeverUnderSixtySeconds() {
        val now = Instant.parse("2026-10-06T10:00:00Z")

        // 4 minutes to go: poll right after it.
        assertEquals(Duration.ofSeconds(241), ColituRotation.nextPollDelay(now.plusSeconds(240), now))
        // Due in 5 s, already due, or unknown: not before 60 s.
        assertEquals(Duration.ofSeconds(60), ColituRotation.nextPollDelay(now.plusSeconds(5), now))
        assertEquals(Duration.ofSeconds(60), ColituRotation.nextPollDelay(now.minusSeconds(180), now))
        assertEquals(Duration.ofSeconds(60), ColituRotation.nextPollDelay(null, now))
        // A far-off value (clock skew) is capped.
        assertTrue(ColituRotation.nextPollDelay(now.plus(Duration.ofDays(2)), now) <= Duration.ofMinutes(35))
    }

    @Test
    fun theCountdownIsMinutesAndSeconds() {
        assertEquals("4:07", ColituRotation.formatCountdown(Duration.ofSeconds(247)))
        assertEquals("10:00", ColituRotation.formatCountdown(Duration.ofSeconds(600)))
        assertEquals("0:59", ColituRotation.formatCountdown(Duration.ofSeconds(59)))
        assertEquals("0:00", ColituRotation.formatCountdown(Duration.ZERO))
        assertEquals("0:00", ColituRotation.formatCountdown(Duration.ofSeconds(-5)))
    }

    @Test
    fun theStringsExistInEveryLanguage() {
        val field = ColituLoc::class.java.getDeclaredField("languageState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(ColituLoc) as androidx.compose.runtime.MutableState<String>
        val previous = state.value
        try {
            val keys = listOf(
                "multihop.section", "multihop.ping", "multihop.home", "multihop.sub", "multihop.gone", "multihop.needsVless",
                "rotation.title", "rotation.off", "rotation.minutes", "rotation.home", "rotation.homeDue", "rotation.needsVless",
                "rotation.err.few", "account.manualConfig",
            )
            val perLanguage = ColituLoc.languages.map { language ->
                state.value = language
                keys.map { key -> ColituLoc[key].also { assertNotEquals("$key missing in $language", key, it) } }
            }
            // Three different wordings, not one copied text.
            keys.indices.forEach { i -> assertEquals(keys[i], 3, perLanguage.map { it[i] }.toSet().size) }
            state.value = "en"
            assertEquals("Estimated · +1 hop", ColituLoc["multihop.ping"])
            assertEquals("Entry FI → Exit DE", ColituLoc.format("multihop.home", "entry" to "FI", "exit" to "DE"))
        } finally {
            state.value = previous
        }
    }
}
