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
            "fi" to ping(10, age = 7 * 60 * minute),
            "de" to ping(20, network = ColituServerRanking.networkKey("wifi", "RU-AS0001")),
            "us" to ping(90),
        )
        assertEquals(listOf("us", "de", "fi"), rank(listOf(de, fi, us), pings))
        // 11 minutes old on the same network: no longer fresh, still usable for the order.
        assertEquals(listOf("fi", "us", "de"), rank(listOf(de, fi, us), pings + ("fi" to ping(10, age = 11 * minute))))
    }

    @Test
    fun lastGoodServerLeadsTheFreshPings() {
        // de is faster, but not clearly (25 % and 50 ms): the remembered server stays first.
        val pings = mapOf("de" to ping(150), "fi" to ping(200))
        assertEquals(listOf("fi", "de", "us"), rank(listOf(us, de, fi), pings, lastGood = "fi"))
    }

    @Test
    fun clearlyFasterFreshServerBeatsTheRememberedOne() {
        // 20 vs 200 ms: far more than 40 % and 60 ms faster.
        assertEquals(listOf("de", "fi", "us"), rank(listOf(us, de, fi), mapOf("de" to ping(20), "fi" to ping(200)), lastGood = "fi"))
        // 40 % but only 40 ms faster: memory stays.
        assertEquals(listOf("fi", "de"), rank(listOf(de, fi), mapOf("de" to ping(60), "fi" to ping(100)), lastGood = "fi"))
        // 60 ms faster but only 30 %: memory stays.
        assertEquals(listOf("fi", "de"), rank(listOf(de, fi), mapOf("de" to ping(140), "fi" to ping(200)), lastGood = "fi"))
        // Both thresholds met (40 % and 60 ms): the faster one leads.
        assertEquals(listOf("de", "fi"), rank(listOf(fi, de), mapOf("de" to ping(90), "fi" to ping(150)), lastGood = "fi"))
        // The remembered server has no fresh measurement: current behaviour (it leads).
        assertEquals(listOf("fi", "de"), rank(listOf(de, fi), mapOf("de" to ping(5)), lastGood = "fi"))
        // A 30-minute-old ping still counts (usable up to 6 h): 200 against 5 ms, the faster one leads.
        assertEquals(listOf("de", "fi"), rank(listOf(de, fi), mapOf("de" to ping(5), "fi" to ping(200, age = 30 * minute)), lastGood = "fi"))
        // Older than 6 h: no measurement, the memory leads.
        assertEquals(listOf("fi", "de"), rank(listOf(de, fi), mapOf("de" to ping(5), "fi" to ping(200, age = 7 * 60 * minute)), lastGood = "fi"))
    }

    @Test
    fun europeClientWithAUsServerRememberedGetsTheNearNode() {
        // The US server was remembered (a manual pick, old behaviour) with a fresh 150 ms ping; a Finnish node answers in 30 ms.
        val pings = mapOf("us" to ping(150), "fi" to ping(30), "de" to ping(45))
        assertEquals(listOf("fi", "de", "us"), rank(listOf(us, de, fi), pings, lastGood = "us", country = "SE"))
        assertEquals(listOf("fi", "de", "us"), rank(listOf(us, de, fi), pings, lastGood = null, country = "SE"))
    }

    @Test
    fun aPenalizedFasterNodeDoesNotTakeTheLeadFromTheRememberedServer() {
        val pings = mapOf("de" to ping(10), "fi" to ping(200))
        assertEquals(listOf("fi", "us", "de"), rank(listOf(de, fi, us), pings, lastGood = "fi", penalized = setOf("de")))
    }

    @Test
    fun homeCountryOnlyGoesBackInCensoringCountries() {
        val nl = server("nl", "NL")
        // NL client: the local node competes on latency and wins.
        assertEquals(listOf("nl", "us"), rank(listOf(us, nl), mapOf("nl" to ping(5), "us" to ping(150)), country = "NL"))
        // TR client: the Turkish node goes after the German one although it is faster.
        val trPings = mapOf("tr" to ping(5), "de" to ping(60))
        assertEquals(listOf("de", "tr"), rank(listOf(tr, de), trPings, country = "TR"))
        assertEquals(listOf("de", "tr"), rank(listOf(tr, de), trPings, country = "tr"))
        assertEquals(listOf("tr", "de"), rank(listOf(tr, de), trPings, country = "GB"))
        // Every listed country except RU (whose servers are excluded anyway) pushes its own servers back.
        (ColituServerRanking.HOME_PUSHBACK_COUNTRIES - "RU").forEach { code ->
            val order = rank(listOf(server("home", code), server("other", "DE")), mapOf("home" to ping(5), "other" to ping(60)), country = code)
            assertEquals(code, listOf("other", "home"), order)
        }
        assertEquals(18, ColituServerRanking.HOME_PUSHBACK_COUNTRIES.size)
        assertFalse("NL" in ColituServerRanking.HOME_PUSHBACK_COUNTRIES)
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
    fun tentativeHysteriaStallBecomesANetworkStallOnceAnotherTransportWorks() {
        val memory = ColituConnectMemory.empty()
        memory.markStalled(net, "de", "hysteria2", now, confirmed = false)
        assertEquals(setOf("hysteria2"), memory.stalled(net, "fi", now + minute))
        memory.confirm(net, "de", setOf("hysteria2"), now + minute)
        memory.recordSuccess(net, "de", "vless-xhttp", now + minute)
        // The next server of this network does not lead with Hysteria2 again.
        assertEquals(setOf("hysteria2"), memory.stalled(net, "fi", now + 5 * hour))
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
