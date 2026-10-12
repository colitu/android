package com.v2ray.ang.colitu.l10n

import org.junit.Assert.assertEquals
import org.junit.Test

class ColituServerNamesTest {
    @Test
    fun displayNameTranslatesTheCountryAndTheSuffix() {
        assertEquals("Latvia (Bridge)", ColituServerNames.displayName("Letonya (Köprü)", language = "en"))
        assertEquals("Латвия (Мост)", ColituServerNames.displayName("Letonya (Köprü)", language = "ru"))
        assertEquals("Letonya (Köprü)", ColituServerNames.displayName("Letonya (Köprü)", language = "tr"))
        assertEquals("Germany", ColituServerNames.displayName("Almanya", language = "en"))
        assertEquals("Германия", ColituServerNames.displayName("ALMANYA", language = "ru"))
        // The country code itself, and a suffix the table does not know.
        assertEquals("Latvia (Test)", ColituServerNames.displayName("LV (Test)", countryCode = "LV", language = "en"))
        assertEquals("Birleşik Krallık", ColituServerNames.displayName("UK", language = "tr"))
    }

    @Test
    fun citiesMatchCaseAndDiacriticInsensitivelyWithAliases() {
        assertEquals("London", ColituServerNames.city("Londra", language = "en"))
        assertEquals("Лондон", ColituServerNames.city("londra", language = "ru"))
        assertEquals("Warsaw", ColituServerNames.city("Varşova", language = "en"))
        assertEquals("Варшава", ColituServerNames.city("Varsova", language = "ru"))
        assertEquals("Almaty", ColituServerNames.city("Almatı", language = "en"))
        assertEquals("São Paulo", ColituServerNames.city("Sao Paulo", language = "tr"))
        assertEquals("Киев", ColituServerNames.city("Kyiv", language = "ru"))
        assertEquals("Istanbul", ColituServerNames.city("İstanbul", language = "en"))
        assertEquals("Londra", ColituServerNames.city("Лондон", language = "tr"))
    }

    @Test
    fun unknownTextStaysExactlyAsThePanelSentIt() {
        val raw = "Codex staging Docker node"
        for (language in ColituLoc.languages) {
            assertEquals(raw, ColituServerNames.displayName(raw, language = language))
            assertEquals(raw, ColituServerNames.city(raw, language = language))
            assertEquals(raw, ColituServerNames.route(raw, language = language))
        }
        assertEquals("", ColituServerNames.city("", language = "en"))
        assertEquals("", ColituServerNames.city(null, language = "en"))
    }

    @Test
    fun multihopRouteNamesTranslateEachEnd() {
        assertEquals("Warsaw → Frankfurt", ColituServerNames.route("Varşova → Frankfurt", language = "en"))
        assertEquals("Хельсинки → Германия", ColituServerNames.route("Helsinki → Almanya", language = "ru"))
    }
}
