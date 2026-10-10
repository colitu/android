package com.v2ray.ang.colitu.app

import com.google.gson.JsonParser
import com.v2ray.ang.colitu.data.ColituConnectMemory
import com.v2ray.ang.colitu.data.ColituPing
import com.v2ray.ang.colitu.data.ColituServer
import com.v2ray.ang.colitu.data.ColituServerListResponse
import com.v2ray.ang.colitu.data.ColituServerRanking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituServerRankingTest {
    private val net = ColituServerRanking.networkKey("cellular", "RU-AS0001")
    private val now = 1_000_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun server(id: String, country: String, available: Boolean = true) =
        ColituServer(id = id, displayName = id, countryCode = country, city = null, isRecommended = false, isAvailable = available)

    private fun ping(ms: Int?, age: Long = minute, network: String = net) = ColituPing(ms, now - age, network)

    private fun rank(
        servers: List<ColituServer>,
        pings: Map<String, ColituPing> = emptyMap(),
        lastGood: String? = null,
        penalized: Set<String> = emptySet(),
        country: String? = null,
    ) = ColituServerRanking.rank(servers, pings, net, lastGood, penalized, country, now).map { it.id }

    private val de = server("de", "DE")
    private val fi = server("fi", "FI")
    private val us = server("us", "US")
    private val ru = server("ru", "RU")
    private val tr = server("tr", "TR")

    @Test
    fun withoutPingsOrMemoryPanelOrderIsKept() {
        assertEquals(listOf("us", "de", "fi"), rank(listOf(us, de, fi)))
    }

    @Test
    fun freshPingsAscendingThenUnpingedInPanelOrder() {
        val pings = mapOf("fi" to ping(40), "de" to ping(25))
        assertEquals(listOf("de", "fi", "us"), rank(listOf(us, de, fi, ru), pings))
    }

    @Test
    fun stalePingOrPingFromAnotherNetworkCountsAsNone() {
        val pings = mapOf(
            "fi" to ping(10, age = 11 * minute),
            "de" to ping(20, network = ColituServerRanking.networkKey("wifi", "RU-AS0001")),
            "us" to ping(90),
        )
        assertEquals(listOf("us", "de", "fi"), rank(listOf(de, fi, us), pings))
    }

    @Test
    fun lastGoodServerLeadsTheFreshPings() {
        val pings = mapOf("de" to ping(20), "fi" to ping(200))
        assertEquals(listOf("fi", "de", "us"), rank(listOf(us, de, fi), pings, lastGood = "fi"))
    }

    @Test
    fun ownCountryGoesAfterForeignOnesEvenWhenFasterOrLastGood() {
        val pings = mapOf("tr" to ping(5), "de" to ping(60))
        assertEquals(listOf("de", "fi", "tr"), rank(listOf(tr, de, fi), pings, lastGood = "tr", country = "tr"))
        // Unknown country: no reordering by country.
        assertEquals(listOf("tr", "de", "fi"), rank(listOf(tr, de, fi), pings, lastGood = "tr", country = null))
    }

    @Test
    fun russianServersAreNeverPickedAutomatically() {
        val pings = mapOf("ru" to ping(3), "de" to ping(80))
        // Fastest, last good, client in Russia or abroad or unknown: never in the automatic order.
        listOf("RU", "TR", null).forEach { country ->
            assertEquals(listOf("de", "fi"), rank(listOf(ru, de, fi), pings, lastGood = "ru", country = country))
        }
        assertEquals(listOf("de", "fi"), rank(listOf(server("ru2", " ru "), de, fi), pings))
        // Only Russian servers left: automatic mode has nothing to pick.
        assertEquals(emptyList<String>(), rank(listOf(ru)))
        assertTrue(ColituServerRanking.autoExcluded(ru))
    }

    @Test
    fun freshFailedPingGoesAfterAllButPenalized() {
        val pings = mapOf("de" to ping(null), "tr" to ping(30))
        // Even the own country (tr) and the last good server (de) rank around it.
        assertEquals(listOf("fi", "us", "tr", "de", "x"), rank(listOf(de, fi, us, tr, server("x", "NL")), pings, lastGood = "de", penalized = setOf("x"), country = "TR"))
        // An old failed ping is no longer held against the server.
        assertEquals(listOf("de", "fi"), rank(listOf(de, fi), mapOf("de" to ping(null, age = 30 * minute))))
    }

    @Test
    fun penalizedLastAndUnavailableOrMultihopSkipped() {
        val pings = mapOf("de" to ping(10))
        val off = server("off", "NL", available = false)
        assertEquals(listOf("fi", "us", "de"), rank(listOf(de, off, fi, us), pings, lastGood = "de", penalized = setOf("de")))
    }

    @Test
    fun pingsRoundTripAndReadTheOldFormat() {
        val pings = mapOf("de" to ping(25), "fi" to ping(null))
        assertEquals(pings, ColituServerRanking.pingsFromJson(ColituServerRanking.pingsToJson(pings)))
        val old = ColituServerRanking.pingsFromJson("""{"de":42}""")
        assertEquals(42, old["de"]?.ms)
        assertTrue(old["de"]?.fresh(net, now) == false)
    }

    @Test
    fun serverListKeepsClientFieldsAndTreatsEmptyAsUnknown() {
        val json = JsonParser.parseString(
            """{"servers":[],"client_country":"TR","client_network":"TR-AS9121"}""",
        ).asJsonObject
        val parsed = ColituServerListResponse.fromJson(json)
        assertEquals("TR", parsed.clientCountry)
        assertEquals("TR-AS9121", parsed.clientNetwork)
        val throughVpn = ColituServerListResponse.fromJson(JsonParser.parseString("""{"servers":[],"client_country":"","client_network":""}""").asJsonObject)
        assertNull(throughVpn.clientCountry)
        assertNull(throughVpn.clientNetwork)
    }

    // ── Memory ─────────────────────────────────────────────────────────────

    @Test
    fun successIsRememberedPerNetworkAndExpiresAfter24h() {
        val memory = ColituConnectMemory.empty()
        memory.recordSuccess(net, "de", "hysteria2", now)
        assertEquals("de", memory.lastGoodServer(net, now + 23 * hour))
        assertEquals("hysteria2", memory.lastGoodTransport(net, "de", now + 23 * hour))
        assertNull(memory.lastGoodServer(ColituServerRanking.networkKey("wifi", "RU-AS0001"), now))
        assertNull(memory.lastGoodServer(net, now + 24 * hour))
        assertNull(memory.lastGoodTransport(net, "de", now + 24 * hour))
    }

    @Test
    fun stallCountsForTheNetworkFor6hAndSuccessClearsIt() {
        val memory = ColituConnectMemory.empty()
        memory.recordSuccess(net, "de", "trojan", now)
        memory.markStalled(net, "de", "trojan", now)
        // The stalled transport is no longer the remembered one.
        assertNull(memory.lastGoodTransport(net, "de", now))
        assertEquals(setOf("trojan"), memory.stalled(net, "de", now + 5 * hour))
        assertEquals(setOf("trojan"), memory.stalled(net, "fi", now + 5 * hour))
        assertTrue(memory.stalled(ColituServerRanking.networkKey("wifi", ""), "de", now).isEmpty())
        assertTrue(memory.stalled(net, "de", now + 6 * hour).isEmpty())
        // Working on another server clears the network-wide stall, not the one of this server.
        memory.markStalled(net, "de", "trojan", now)
        memory.recordSuccess(net, "fi", "trojan", now + minute)
        assertTrue(memory.stalled(net, "fi", now + 2 * minute).isEmpty())
        assertEquals(setOf("trojan"), memory.stalled(net, "de", now + 2 * minute))
    }

    @Test
    fun penaltyLasts30MinutesAndDropsTheLastGoodServer() {
        val memory = ColituConnectMemory.empty()
        memory.recordSuccess(net, "de", "vless-reality", now)
        memory.penalize(net, "de", now)
        assertNull(memory.lastGoodServer(net, now))
        assertEquals(setOf("de"), memory.penalized(net, now + 29 * minute))
        assertTrue(memory.penalized(net, now + 30 * minute).isEmpty())
        memory.penalize(net, "fi", now)
        memory.recordSuccess(net, "fi", null, now + minute)
        assertEquals(setOf("de"), memory.penalized(net, now + 2 * minute))
    }

    @Test
    fun loadPrunesExpiredEntriesAndTheStoreIsCapped() {
        val memory = ColituConnectMemory.empty()
        memory.penalize(net, "old", now - hour)
        memory.recordSuccess(net, "de", "trojan", now)
        val loaded = ColituConnectMemory.fromJson(memory.toJson(), now)
        assertTrue(loaded.penalized(net, now).isEmpty())
        assertEquals("de", loaded.lastGoodServer(net, now))
        // Last good server, last good transport and "worked on this network".
        assertEquals(3, loaded.size)

        val big = ColituConnectMemory.empty()
        for (i in 0 until 300) big.penalize(net, "s$i", now + i)
        assertTrue(big.size <= ColituConnectMemory.MAX_ENTRIES)
        // The newest survive.
        assertTrue("s299" in big.penalized(net, now + 300))
    }

    @Test
    fun entriesFromTheFutureAreIgnored() {
        val memory = ColituConnectMemory.empty()
        memory.recordSuccess(net, "de", "trojan", now + hour)
        assertNull(memory.lastGoodServer(net, now))
        assertEquals(0, ColituConnectMemory.fromJson("not json", now).size)
    }

    @Test
    fun networkReplacedOnlyWhenEveryUnderlyingNetworkIsNew() {
        assertTrue(ColituServerRanking.networkReplaced(setOf("100"), setOf("101")))
        // Wi-Fi joined next to mobile data, or the first callback: not a new access network.
        assertFalse(ColituServerRanking.networkReplaced(setOf("100"), setOf("100", "101")))
        assertFalse(ColituServerRanking.networkReplaced(null, setOf("101")))
        assertFalse(ColituServerRanking.networkReplaced(setOf("100"), emptySet()))
    }
}
