package com.v2ray.ang.colitu.screens

import com.v2ray.ang.colitu.data.ColituServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationGroupsTest {
    private fun server(id: String, country: String?, city: String? = id, available: Boolean = true) =
        ColituServer(id = id, displayName = id, countryCode = country, city = city, isRecommended = false, isAvailable = available)

    private val list = listOf(
        server("de-fra", "DE"),
        server("se-sto", "SE"),
        server("de-ber", "de"),
        server("nl-ams", "NL"),
        server("de-muc", "DE"),
    )

    @Test fun groupsServersByCountryKeepingFirstSeenOrder() {
        val out = groupByCountry(list, selectedServerId = null)
        assertEquals(3, out.size)
        val de = out[0] as LocationEntry.Group
        assertEquals("DE", de.countryCode)
        assertEquals(listOf("de-fra", "de-ber", "de-muc"), de.servers.map { it.id })
        assertEquals("se-sto", (out[1] as LocationEntry.Single).server.id)
        assertEquals("nl-ams", (out[2] as LocationEntry.Single).server.id)
    }

    @Test fun countryWithOneServerStaysAPlainRow() {
        val out = groupByCountry(listOf(server("se-sto", "SE")), selectedServerId = "se-sto")
        assertTrue(out.single() is LocationEntry.Single)
    }

    @Test fun serverWithoutCountryIsNeverGrouped() {
        val out = groupByCountry(listOf(server("a", null), server("b", null), server("c", " ")), null)
        assertEquals(3, out.size)
        assertTrue(out.all { it is LocationEntry.Single })
    }

    @Test fun searchShowsMatchesFlat() {
        val out = groupByCountry(list, selectedServerId = "de-ber", flat = true)
        assertEquals(list.map { it.id }, out.map { (it as LocationEntry.Single).server.id })
    }

    @Test fun groupHoldingTheSelectedServerStartsExpanded() {
        val out = groupByCountry(list, selectedServerId = "de-muc")
        assertTrue((out[0] as LocationEntry.Group).expandedByDefault)
        val other = groupByCountry(list, selectedServerId = "nl-ams")
        assertFalse((other[0] as LocationEntry.Group).expandedByDefault)
        val auto = groupByCountry(list, selectedServerId = null)
        assertFalse((auto[0] as LocationEntry.Group).expandedByDefault)
    }

    @Test fun inputOrderDecidesCountryAndCityOrder() {
        // Already sorted by ping by the caller: SE is the fastest country, DE's cities follow their pings.
        val sorted = listOf(server("se-sto", "SE"), server("de-muc", "DE"), server("de-fra", "DE"), server("se-got", "SE"))
        val out = groupByCountry(sorted, null)
        assertEquals(listOf("SE", "DE"), out.map { (it as LocationEntry.Group).countryCode })
        assertEquals(listOf("se-sto", "se-got"), (out[0] as LocationEntry.Group).servers.map { it.id })
        assertEquals(listOf("de-muc", "de-fra"), (out[1] as LocationEntry.Group).servers.map { it.id })
    }

    @Test fun bestPingIgnoresOfflineAndUnmeasuredServers() {
        val pings = mapOf("a" to 90, "b" to 40, "c" to 10)
        val servers = listOf(server("a", "DE"), server("b", "DE"), server("c", "DE", available = false), server("d", "DE"))
        assertEquals(40, bestPing(servers) { pings[it.id] })
        assertNull(bestPing(listOf(server("d", "DE"))) { pings[it.id] })
        assertNull(bestPing(emptyList()) { 1 })
    }
}
