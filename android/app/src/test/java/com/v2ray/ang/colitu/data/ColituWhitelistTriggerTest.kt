package com.v2ray.ang.colitu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituWhitelistTriggerTest {
    private val net = "wifi|RU-AS1"

    private fun ledger(vararg servers: Pair<String, String>): ColituWhitelist.FailureLedger =
        ColituWhitelist.FailureLedger().also { l -> servers.forEach { (id, cc) -> l.add(net, id, cc) } }

    private fun server(id: String, cc: String) = ColituServer(id, id, cc, null, false, true)

    @Test
    fun threeServersInTwoCountriesAreDue() {
        assertTrue(ledger("a" to "DE", "b" to "NL", "c" to "DE").due(0))
    }

    @Test
    fun threeServersInOneCountryAreNotDue() {
        assertFalse(ledger("a" to "DE", "b" to "DE", "c" to "DE").due(0))
    }

    @Test
    fun sameServerThreeTimesIsNotDue() {
        val l = ColituWhitelist.FailureLedger()
        repeat(3) { l.add(net, "a", "DE") }
        assertEquals(1, l.size)
        assertFalse(l.due(0))
    }

    @Test
    fun excludedReasonsAreNotCounted() {
        val l = ColituWhitelist.FailureLedger()
        for (reason in listOf("auth", "quota", "core_crash", "network_change")) assertFalse(l.add(net, "x$reason", "DE", reason))
        assertEquals(0, l.size)
    }

    @Test
    fun successAndNetworkChangeReset() {
        val l = ledger("a" to "DE", "b" to "NL", "c" to "FI")
        l.reset()
        assertEquals(0, l.size)
        val m = ledger("a" to "DE", "b" to "NL", "c" to "FI")
        m.add("cellular|RU-AS2", "d", "SE")
        assertEquals(1, m.size)
    }

    @Test
    fun afterNotWhitelistOneMoreServerIsNeeded() {
        val l = ledger("a" to "DE", "b" to "NL", "c" to "FI")
        l.checked(0, whitelist = false)
        val later = ColituWhitelist.DEBOUNCE_MS + 1
        assertFalse(l.due(later))
        l.add(net, "d", "SE")
        assertTrue(l.due(later))
    }

    @Test
    fun debounceSixtySeconds() {
        val l = ledger("a" to "DE", "b" to "NL", "c" to "FI")
        l.started(1_000)
        assertFalse(l.due(1_000 + ColituWhitelist.DEBOUNCE_MS - 1))
        assertTrue(l.due(1_000 + ColituWhitelist.DEBOUNCE_MS))
    }

    @Test
    fun enteringWhitelistResetsLedger() {
        val l = ledger("a" to "DE", "b" to "NL", "c" to "FI")
        l.checked(0, whitelist = true)
        assertEquals(0, l.size)
    }

    @Test
    fun pingRule() {
        val four = listOf(server("a", "DE"), server("b", "NL"), server("c", "DE"), server("d", "FI"))
        val silent = four.associate { it.id to null as Int? }
        assertTrue(ColituWhitelist.pingsDue(four, silent))
        assertFalse(ColituWhitelist.pingsDue(four.take(3), silent))
        assertFalse(ColituWhitelist.pingsDue(four, silent + ("b" to 40)))
        val oneCountry = listOf(server("a", "DE"), server("b", "DE"), server("c", "DE"), server("d", "DE"))
        assertFalse(ColituWhitelist.pingsDue(oneCountry, oneCountry.associate { it.id to null as Int? }))
    }

    @Test
    fun pingRuleIgnoresRussian() {
        val list = listOf(server("a", "DE"), server("b", "NL"), server("c", "FI"), server("ru", "RU"))
        val silent = list.associate { it.id to null as Int? }
        assertFalse(ColituWhitelist.pingsDue(list, silent))
        // A fourth non-RU server makes it due even when the Russian one answers.
        val more = list + server("d", "SE")
        assertTrue(ColituWhitelist.pingsDue(more, more.associate { it.id to (if (it.id == "ru") 20 else null) }))
    }

    @Test
    fun whitelistModePicksVlessWsCandidate() {
        val model = ColituLm.Model(version = ColituLm.FORMAT_VERSION, country = "RU", explore = 0.0)
        val candidates = listOf(
            ColituLm.Candidate("de1", "vless", "DE", "DE", latencyMs = 20.0),
            ColituLm.Candidate("wl1", "vless-ws", "RU", "GB", whitelistOk = true),
        )
        fun decide(mode: Boolean) = ColituLm.decide(
            ColituLm.Input(model, 0.0, true, "RU", "cellular", mode, 1L, candidates),
        )
        val on = decide(true)
        assertEquals(1, on.choice)
        assertFalse(on.whitelistNone)
        // Mode off stays on the normal rules: the whitelist flag alone does not restrict the pick.
        assertTrue(decide(false).ranking.contains(0))
    }

    @Test
    fun serversTokenSentOnSameNetworkOnly() {
        val now = 1_000_000L
        val record = ColituNetworkRecord("RU-AS1", "tok", emptySet(), now, issuedKey = "wifi|RU-AS1")
        assertEquals("tok", record.tokenForList("RU-AS1", "wifi|RU-AS1", now + 1000))
        assertNull(record.tokenForList("RU-AS1", "cellular|RU-AS1", now + 1000))
        assertNull(record.tokenForList("TR-AS9", "wifi|RU-AS1", now + 1000))
        assertEquals("/servers?network_token=tok", ColituServersToken.path("tok"))
        assertEquals("/servers", ColituServersToken.path(null))
    }
}
