package com.v2ray.ang.colitu.app

import com.v2ray.ang.colitu.data.ColituConnectMemory
import com.v2ray.ang.colitu.data.ColituServer
import com.v2ray.ang.colitu.data.ColituServerRanking
import com.v2ray.ang.colitu.data.ColituVpnConfig
import com.v2ray.ang.colitu.data.ColituWarmSpare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fixes from the device drop test (2026-10-09). */
class ColituDropRecoveryTest {
    private val net = ColituServerRanking.networkKey("wifi", "LT-AS0001")
    private val now = 1_000_000_000_000L
    private val minute = 60_000L
    private val all = listOf("hysteria2", "vless-reality", "vless-xhttp", "trojan", "shadowsocks")

    // 1. The spare carrying the traffic never causes a reconnect.
    @Test
    fun onlyADeadNormalPathReconnects() {
        assertEquals(ColituTransportOrder.WatchAction.SpareCarries, ColituTransportOrder.watchAction(spareAttached = true, primaryOk = false, normalOk = true))
        assertEquals(ColituTransportOrder.WatchAction.BothDead, ColituTransportOrder.watchAction(spareAttached = true, primaryOk = false, normalOk = false))
        assertEquals(ColituTransportOrder.WatchAction.Fine, ColituTransportOrder.watchAction(spareAttached = true, primaryOk = true, normalOk = true))
        // Without a spare the normal path is the primary.
        assertEquals(ColituTransportOrder.WatchAction.Fine, ColituTransportOrder.watchAction(spareAttached = false, primaryOk = false, normalOk = true))
        assertEquals(ColituTransportOrder.WatchAction.BothDead, ColituTransportOrder.watchAction(spareAttached = false, primaryOk = false, normalOk = false))
    }

    // 2. Stall marks never lock a network.
    @Test
    fun marksCoveringAllButOneTransportAreIgnoredForTheRound() {
        val remembered = setOf("hysteria2", "vless-xhttp", "vless-reality", "trojan")
        // Only Shadowsocks would be left: a network problem, default order this round.
        assertTrue(ColituTransportOrder.effectiveStalled(all, remembered, emptySet()).isEmpty())
        // What failed in this very round still counts.
        assertEquals(setOf("trojan"), ColituTransportOrder.effectiveStalled(all, remembered, setOf("trojan")))
        // Two marks out of five: kept.
        assertEquals(setOf("hysteria2", "trojan"), ColituTransportOrder.effectiveStalled(all, setOf("hysteria2", "trojan"), emptySet()))
        // A server with one transport: nothing to reorder, kept as it is.
        assertEquals(setOf("trojan"), ColituTransportOrder.effectiveStalled(listOf("trojan"), setOf("trojan"), emptySet()))
    }

    @Test
    fun stallsOfAFailingRoundExpireIn10MinutesUnlessAnotherTransportWorked() {
        val memory = ColituConnectMemory.empty()
        memory.markStalled(net, "lt", "trojan", now, confirmed = false)
        memory.markStalled(net, "lt", "vless-reality", now, confirmed = false)
        assertEquals(setOf("trojan", "vless-reality"), memory.stalled(net, "lt", now + 9 * minute))
        assertTrue(memory.stalled(net, "lt", now + 10 * minute).isEmpty())
        // Same round, but then XHTTP carried traffic: the two failures were real (6 h).
        memory.markStalled(net, "lt", "trojan", now, confirmed = false)
        memory.recordSuccess(net, "lt", "vless-xhttp", now + minute)
        memory.confirm(net, "lt", setOf("trojan"), now + minute)
        assertEquals(setOf("trojan"), memory.stalled(net, "lt", now + 5 * 60 * minute))
        // A success of the transport itself clears both kinds.
        memory.markStalled(net, "lt", "hysteria2", now, confirmed = false)
        memory.recordSuccess(net, "lt", "hysteria2", now + minute)
        assertTrue("hysteria2" !in memory.stalled(net, "lt", now + 2 * minute))
    }

    // 3. A manual choice is never switched; automatic mode keeps its fallback.
    @Test
    fun manualChoiceGetsNoFallbackServer() {
        val ranked = listOf("a", "b", "c", "d").map { ColituServer(it, it, "DE", null, false, true) }
        assertNull(ColituServerRanking.fallbackServer(automatic = false, ranked, listOf("a"), 3, budgetLeft = true))
        assertEquals("b", ColituServerRanking.fallbackServer(automatic = true, ranked, listOf("a"), 3, budgetLeft = true)?.id)
        assertEquals("d", ColituServerRanking.fallbackServer(automatic = true, ranked, listOf("a", "b", "c").take(2) + "c", 4, budgetLeft = true)?.id)
        assertNull(ColituServerRanking.fallbackServer(automatic = true, ranked, listOf("a", "b", "c"), 3, budgetLeft = true))
        assertNull(ColituServerRanking.fallbackServer(automatic = true, ranked, listOf("a"), 3, budgetLeft = false))
    }

    // 4. The spare prefers what worked here lately; Shadowsocks last.
    @Test
    fun spareTakesARecentlyWorkingTransportFirstAndShadowsocksLast() {
        // Hysteria2 primary, nothing known: Reality first as before.
        assertEquals("vless-reality", ColituWarmSpare.spareTransport("hysteria2", all, emptySet(), sameServer = true))
        // XHTTP carried traffic here in the last 24 h: it beats untested Reality.
        assertEquals("vless-xhttp", ColituWarmSpare.spareTransport("hysteria2", all, emptySet(), sameServer = true, worked = setOf("vless-xhttp")))
        // Even a working Shadowsocks stays behind untested TCP transports.
        assertEquals("vless-reality", ColituWarmSpare.spareTransport("hysteria2", all, emptySet(), sameServer = true, worked = setOf("shadowsocks")))
        // TCP primary: a recently working Trojan beats untested Hysteria2.
        assertEquals("trojan", ColituWarmSpare.spareTransport("vless-xhttp", all, emptySet(), sameServer = true, worked = setOf("trojan")))
        // Shadowsocks only when nothing else is usable.
        assertEquals("shadowsocks", ColituWarmSpare.spareTransport("hysteria2", all, setOf("vless-reality", "vless-xhttp", "trojan"), sameServer = true))
    }

    // Cross-server test: TCP frozen on this Wi-Fi, Hysteria2 proven.
    @Test
    fun spareOnAnotherServerIsTheProvenTransport() {
        val stalled = setOf("vless-xhttp", "vless-reality")
        // Hysteria2 primary, only Hysteria2 proven here: the next server's Hysteria2, not an untested Trojan.
        assertEquals("hysteria2", ColituWarmSpare.spareTransport("hysteria2", all, stalled, sameServer = false, worked = setOf("hysteria2")))
        // Reality primary after a reconnect, Hysteria2 proven: Hysteria2 on the next server.
        assertEquals("hysteria2", ColituWarmSpare.spareTransport("vless-reality", all, stalled, sameServer = false, worked = setOf("hysteria2")))
        // Nothing proven: the family rule as before.
        assertEquals("trojan", ColituWarmSpare.spareTransport("hysteria2", all, stalled, sameServer = false))
        // A proven but stalled transport is still no spare.
        assertEquals("trojan", ColituWarmSpare.spareTransport("hysteria2", all, stalled + "hysteria2", sameServer = false, worked = setOf("hysteria2")))
        // Same server (manual): never the primary's own transport; the other family, stalled ones skipped.
        assertEquals("trojan", ColituWarmSpare.spareTransport("hysteria2", all, stalled, sameServer = true, worked = setOf("hysteria2")))
        assertEquals("shadowsocks", ColituWarmSpare.spareTransport("hysteria2", all, stalled + "trojan", sameServer = true, worked = setOf("hysteria2")))
    }

    @Test
    fun ignoredMarksStillLetTheLastGoodTransportLead() {
        fun config(p: String) = ColituVpnConfig("lt", null, "xray-mobile-v1", p, "{}", null, false)
        val configs = all.map(::config)
        val remembered = setOf("hysteria2", "vless-xhttp", "vless-reality", "trojan")
        // Marks ignored (only Shadowsocks would be left); Hysteria2 carried traffic here lately.
        val order = ColituTransportOrder.lockedOrder(configs, remembered, emptySet(), worked = setOf("hysteria2")).map { it.protocolType }
        assertEquals("hysteria2", order.first())
        // Unmarked Shadowsocks before the ignored marks; preference order among those.
        assertEquals(listOf("hysteria2", "shadowsocks", "vless-reality", "vless-xhttp", "trojan"), order)
        // What failed in this very round goes last.
        assertEquals("hysteria2", ColituTransportOrder.lockedOrder(configs, remembered, setOf("hysteria2"), setOf("hysteria2")).last().protocolType)
    }

    // Device log 2026-10-10: memory from an earlier build had only "last good hysteria2" for the
    // primary server (no network-wide record), and the cached spare got an untested Trojan.
    @Test
    fun aServersLastGoodTransportCountsAsProvenOnTheNetwork() {
        val stored = """{"gt|$net|lt":{"v":"hysteria2","t":${now - 60 * minute}}}"""
        val memory = ColituConnectMemory.fromJson(stored, now)
        assertTrue(memory.workedOnNetwork(net, "hysteria2", now))
        assertTrue(!memory.workedOnNetwork(net, "trojan", now))
        assertTrue(!memory.workedOnNetwork(ColituServerRanking.networkKey("cellular", "LT-AS0001"), "hysteria2", now))
        // Expires with the last good transport (24 h).
        assertTrue(!memory.workedOnNetwork(net, "hysteria2", now + 24 * 60 * minute))
        // The next server's spare (its profile from the cache) is then the proven Hysteria2.
        val worked = all.filterTo(mutableSetOf()) { memory.workedOnNetwork(net, it, now) }
        assertEquals(
            "hysteria2" to "proven",
            ColituWarmSpare.spareChoice("hysteria2", all, setOf("vless-xhttp", "vless-reality"), sameServer = false, worked = worked),
        )
        assertEquals(
            "trojan" to "other-family",
            ColituWarmSpare.spareChoice("hysteria2", all, setOf("vless-xhttp", "vless-reality"), sameServer = false),
        )
    }
}
