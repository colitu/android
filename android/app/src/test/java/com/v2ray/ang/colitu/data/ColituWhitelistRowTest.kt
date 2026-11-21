package com.v2ray.ang.colitu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituWhitelistRowTest {
    private fun server(id: String, whitelist: Boolean, vararg protocols: String) =
        ColituServer(id = id, displayName = id, countryCode = "GB", city = null, isRecommended = false, isAvailable = true, load = null,
            protocols = protocols.toList(), whitelistOk = whitelist)

    private val list = listOf(
        server("a", false, "hysteria2"),
        server("gb", true, "hysteria2", "vless-reality", "vless-ws"),
        server("flag-only", true, "hysteria2"),
        server("ws-no-flag", false, "vless-ws"),
        server("fi", true, "hysteria2", "vless-reality-relay"),
        server("se", true, "vless-ws", "vless-reality-relay"),
    )

    @Test
    fun oneRowPerWhitelistServerOnlyForRussia() {
        assertEquals(listOf("gb", "fi", "se"), ColituWhitelistRow.rows(list, "RU").map { it.id })
        assertEquals(listOf("gb", "fi", "se"), ColituWhitelistRow.rows(list, " ru ").map { it.id })
        assertTrue(ColituWhitelistRow.rows(list, "DE").isEmpty())
        assertTrue(ColituWhitelistRow.rows(list, null).isEmpty())
        assertTrue(ColituWhitelistRow.rows(list, "").isEmpty())
    }

    @Test
    fun theNormalServerKeepsItsOrderAndName() {
        assertEquals(listOf("a", "gb", "flag-only", "ws-no-flag", "fi", "se"), list.map { it.id })
        assertEquals("gb", list[1].displayName)
    }

    @Test
    fun relayIsPreferredOverWs() {
        assertEquals("vless-ws", ColituWhitelistRow.preferred(list[1]))
        assertEquals("vless-reality-relay", ColituWhitelistRow.preferred(list[4]))
        assertEquals("vless-reality-relay", ColituWhitelistRow.preferred(list[5]))
        assertNull(ColituWhitelistRow.preferred(list[0]))
    }

    @Test
    fun tappingTheRowForcesAWhitelistTransport() {
        assertEquals("vless-ws", ColituWhitelistRow.forcedProtocol(list[1], rowSelected = true, whitelistMode = false, automatic = false))
        assertEquals("vless-reality-relay", ColituWhitelistRow.forcedProtocol(list[5], rowSelected = true, whitelistMode = false, automatic = false))
        assertNull(ColituWhitelistRow.forcedProtocol(list[1], rowSelected = false, whitelistMode = false, automatic = false))
        assertNull(ColituWhitelistRow.forcedProtocol(list[0], rowSelected = true, whitelistMode = true, automatic = true))
    }

    @Test
    fun whitelistModeForcesOnAWhitelistNodeThatOffersOne() {
        assertEquals("vless-reality-relay", ColituWhitelistRow.forcedProtocol(list[4], rowSelected = false, whitelistMode = true, automatic = true))
        assertNull(ColituWhitelistRow.forcedProtocol(list[2], rowSelected = false, whitelistMode = true, automatic = true))
        assertNull(ColituWhitelistRow.forcedProtocol(list[3], rowSelected = false, whitelistMode = true, automatic = true))
        assertNull(ColituWhitelistRow.forcedProtocol(list[1], rowSelected = false, whitelistMode = true, automatic = false))
    }

    @Test
    fun forcedOrderIsWhitelistOnlyRelayFirstAndNormalOrderDropsThem() {
        val configs = listOf("hysteria2", "vless-ws", "vless-reality", "vless-reality-relay")
        assertEquals(listOf("vless-reality-relay", "vless-ws"), ColituWhitelistRow.forcedOrder(configs) { it })
        assertTrue(ColituWhitelistRow.forcedOrder(listOf("trojan"), { it }).isEmpty())
        assertEquals(listOf("hysteria2", "vless-reality"), ColituWhitelistRow.normalOrder(configs) { it })
        // Nothing else on offer: the list is kept rather than emptied.
        assertEquals(listOf("vless-ws"), ColituWhitelistRow.normalOrder(listOf("vless-ws")) { it })
    }

    @Test
    fun selectionKeyRoundTrip() {
        val key = ColituWhitelistRow.selectionKey("node-1")
        assertEquals("whitelist:node-1", key)
        assertEquals("node-1", ColituWhitelistRow.nodeOf(key))
        assertNull(ColituWhitelistRow.nodeOf("node-1"))
        assertNull(ColituWhitelistRow.nodeOf("whitelist:"))
        assertNull(ColituWhitelistRow.nodeOf(null))
        assertFalse(ColituWhitelistRow.nodeOf("") != null)
    }

    @Test
    fun searchFindsWhitelistRowsByCountryCityAndWords() {
        val helsinki = ColituServer(id = "fi", displayName = "Finlandiya", countryCode = "FI", city = "Helsinki", isRecommended = false,
            isAvailable = true, load = null, protocols = listOf("vless-reality-relay"), whitelistOk = true)
        for (q in listOf("", "Helsinki", "хельсинки", "Finland", "Финляндия", "finlandiya", "fi", "beyaz liste", "Whitelist", "белый", "白名单", "لیست سفید")) {
            assertTrue("query '$q'", ColituWhitelistRow.matches(helsinki, q))
        }
        assertFalse(ColituWhitelistRow.matches(helsinki, "Prag"))
        assertFalse(ColituWhitelistRow.matches(helsinki, "Türkiye"))
    }

    @Test
    fun whitelistRowOverridesThePickedMethod() {
        val fi = ColituServer(id = "fi", displayName = "Finlandiya", countryCode = "FI", city = "Helsinki", isRecommended = false,
            isAvailable = true, load = null, protocols = listOf("hysteria2", "vless-reality", "vless-reality-relay", "vless-ws"), whitelistOk = true)
        // The user picked "Stealth" (vless-reality); the whitelist row still forces the whitelist transports only.
        val order = ColituWhitelistRow.forcedOrder(listOf("hysteria2", "vless-reality", "vless-ws", "vless-reality-relay")) { it }
        assertEquals(listOf("vless-reality-relay", "vless-ws"), order)
        assertEquals("vless-reality-relay", ColituWhitelistRow.forcedProtocol(fi, rowSelected = true, whitelistMode = false, automatic = false))
        // The chip shows the whitelist, locked; a normal server (or automatic) gets the normal chip back.
        assertEquals("Beyaz Liste", ColituWhitelistRow.chipLabel(rowSelected = true, automatic = false, whitelistLabel = "Beyaz Liste"))
        assertNull(ColituWhitelistRow.chipLabel(rowSelected = false, automatic = false, whitelistLabel = "Beyaz Liste"))
        assertNull(ColituWhitelistRow.chipLabel(rowSelected = true, automatic = true, whitelistLabel = "Beyaz Liste"))
        // The picker never offers a whitelist transport.
        assertTrue(ColituWhitelistRow.PROTOCOLS.none { it in ColituTransportChoice.METHODS })
    }
}
