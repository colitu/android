package com.v2ray.ang.colitu.screens

import com.google.gson.JsonParser
import com.v2ray.ang.colitu.data.ColituServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendedOrderTest {
    private fun server(id: String, country: String, recommended: Boolean = false) =
        ColituServer(id = id, displayName = id, countryCode = country, city = id, isRecommended = false, isAvailable = true, recommended = recommended)

    @Test fun recommendedServersComeFirstInsideTheirCountry() {
        val list = listOf(server("de-fra", "DE"), server("se-sto", "SE"), server("de-ber", "DE", recommended = true), server("de-muc", "DE"))
        val out = groupByCountry(list, selectedServerId = null)
        val de = out[0] as LocationEntry.Group
        assertEquals(listOf("de-ber", "de-fra", "de-muc"), de.servers.map { it.id })
        // The countries keep the caller's order.
        assertEquals("se-sto", (out[1] as LocationEntry.Single).server.id)
    }

    @Test fun withoutRecommendedTheOrderIsUnchanged() {
        val list = listOf(server("b", "DE"), server("a", "DE"), server("c", "DE", recommended = true), server("d", "DE", recommended = true))
        assertEquals(listOf("c", "d", "b", "a"), recommendedFirst(list).map { it.id })
        assertEquals(listOf("b", "a"), recommendedFirst(list.take(2)).map { it.id })
    }

    @Test fun parsesTheOptionalRecommendedFlag() {
        fun parse(extra: String) = ColituServer.fromJson(
            JsonParser.parseString("""{"id":"n1","name":"Frankfurt","country":"DE","status":"online"$extra}""").asJsonObject,
        )!!
        assertTrue(parse(""","recommended":true""").recommended)
        assertFalse(parse(""","recommended":false""").recommended)
        assertFalse(parse("").recommended)
        assertFalse(parse(""","recommended":"yes"""").recommended)
    }
}
