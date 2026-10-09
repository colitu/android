package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import com.v2ray.ang.colitu.api.LocalProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spare health probe, replacement, swap timing and the parallel connect round. */
class ColituSpareHealthTest {
    private val all = listOf("hysteria2", "vless-reality", "vless-xhttp", "trojan", "shadowsocks")

    // ── Spare probe miss → replacement ────────────────────────────────────

    @Test
    fun deadSpareIsReplacedByTheNextCandidateByTheSpareRules() {
        val options = listOf(
            // The dead spare's server (EE), then the next ranked server (FI), then the primary's own (LT).
            ColituWarmSpare.SpareOption("ee", sameServer = false, offered = all, stalled = setOf("vless-reality", "vless-xhttp")),
            ColituWarmSpare.SpareOption("fi", sameServer = false, offered = all, stalled = emptySet()),
            ColituWarmSpare.SpareOption("lt", sameServer = true, offered = all, stalled = setOf("hysteria2")),
        )
        // EE/hysteria2 (proven) died: EE's next pick by the rules is Trojan (other family, untested).
        assertEquals(
            Triple("ee", "trojan", "other-family"),
            ColituWarmSpare.replacement("hysteria2", options, dead = setOf("ee" to "hysteria2"), worked = setOf("hysteria2")),
        )
        // Trojan on EE died too: FI's proven Hysteria2 next.
        assertEquals(
            Triple("fi", "hysteria2", "proven"),
            ColituWarmSpare.replacement("hysteria2", options, dead = setOf("ee" to "hysteria2", "ee" to "trojan", "ee" to "shadowsocks"), worked = setOf("hysteria2")),
        )
        // Manual mode: only the primary's server, other family, never the primary's own transport.
        val manual = listOf(ColituWarmSpare.SpareOption("lt", sameServer = true, offered = all, stalled = setOf("hysteria2")))
        assertEquals("vless-reality", ColituWarmSpare.replacement("hysteria2", manual, dead = setOf("lt" to "trojan"))?.second)
        // Nothing left: null, the old spare stays.
        val single = listOf(ColituWarmSpare.SpareOption("lt", sameServer = true, offered = listOf("hysteria2", "trojan"), stalled = setOf("hysteria2")))
        assertNull(ColituWarmSpare.replacement("hysteria2", single, dead = setOf("lt" to "trojan")))
    }

    @Test
    fun probeIntervalsKeepTheBudget() {
        assertEquals(60_000L, ColituWarmSpare.spareProbeIntervalMs("hysteria2"))
        assertEquals(180_000L, ColituWarmSpare.spareProbeIntervalMs("trojan"))
        assertEquals(2, ColituWarmSpare.SPARE_PROBE_MISSES)
    }

    // ── Swap timing ──────────────────────────────────────────────────────

    @Test
    fun swapWaitsWhileTheTunnelIsBusy() {
        assertNull(ColituWarmSpare.swapDeferral(0))
        assertNull(ColituWarmSpare.swapDeferral(4 * 1024))
        // A call (~4 KB/s for 10 s) or a page load: deferred.
        assertEquals("busy (40 KB in the last 10 s)", ColituWarmSpare.swapDeferral(40 * 1024))
        assertEquals("traffic unknown", ColituWarmSpare.swapDeferral(null))
    }

    // ── Parallel connect ─────────────────────────────────────────────────

    @Test
    fun parallelRoundWinner() {
        // Primary passes: normal (the spare's result does not matter).
        assertEquals(ColituWarmSpare.ParallelWinner.Primary, ColituWarmSpare.parallelWinner(320, 150))
        assertEquals(ColituWarmSpare.ParallelWinner.Primary, ColituWarmSpare.parallelWinner(320, -1))
        assertEquals(ColituWarmSpare.ParallelWinner.Primary, ColituWarmSpare.parallelWinner(320, null))
        // Only the spare passes: roles swap.
        assertEquals(ColituWarmSpare.ParallelWinner.Spare, ColituWarmSpare.parallelWinner(-1, 210))
        // Both fail (or no spare): the next pair.
        assertEquals(ColituWarmSpare.ParallelWinner.None, ColituWarmSpare.parallelWinner(-1, -1))
        assertEquals(ColituWarmSpare.ParallelWinner.None, ColituWarmSpare.parallelWinner(-1, null))
    }

    @Test
    fun afterARoleSwapTheNewSpareIsNeitherTheFailedNorTheNewPrimary() {
        // Same server: Hysteria2 (primary) failed, Trojan (spare) passed and leads; the new spare is neither of them.
        assertEquals("vless-reality", ColituWarmSpare.spareChoice("trojan", all, stalled = setOf("hysteria2", "trojan"), sameServer = true)?.first)
        assertEquals("vless-xhttp", ColituWarmSpare.spareChoice("trojan", all, stalled = setOf("hysteria2", "trojan", "vless-reality"), sameServer = true)?.first)
        // Spare was on another server: the old primary's server offers the new spare (not the failed transport).
        assertEquals("vless-reality", ColituWarmSpare.spareChoice("trojan", all, stalled = setOf("hysteria2"), sameServer = false)?.first)
    }

    // ── The spare's own check inbound ────────────────────────────────────

    @Test
    fun spareCheckInboundGoesStraightToTheSpareAndLeavesWithIt() {
        val primary = """{"inbounds":[{"tag":"socks","protocol":"socks","port":1080}],
            "outbounds":[{"tag":"proxy","protocol":"vless","settings":{}},{"tag":"direct","protocol":"freedom"}],
            "routing":{"rules":[]}}"""
        val spare = """{"outbounds":[{"tag":"proxy","protocol":"trojan","settings":{}}]}"""
        val out = XrayMobileAdapter.withVerifyInbound(ColituWarmSpare.attach(primary, spare), LocalProxy(1, "a", "b"), LocalProxy(2, "c", "d"))
        val json = JsonParser.parseString(out).asJsonObject
        val rules = json.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        assertEquals("proxy", rules[0].get("outboundTag").asString)
        assertEquals(listOf("colitu-verify-spare"), rules[1].getAsJsonArray("inboundTag").map { it.asString })
        assertEquals("warm-spare", rules[1].get("outboundTag").asString)
        assertTrue(json.getAsJsonArray("inbounds").any { it.asJsonObject.get("tag").asString == "colitu-verify-spare" })
        // Rerouting keeps both check rules first and on their outbounds.
        val rerouted = JsonParser.parseString(ColituWarmSpare.reroute(out)).asJsonObject.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        assertEquals("warm-spare", rerouted[1].get("outboundTag").asString)
        // Spare switched off: its check inbound and rule go too (a rule to a missing outbound would stop the core).
        val detached = JsonParser.parseString(ColituWarmSpare.detach(out)).asJsonObject
        assertFalse(detached.getAsJsonArray("inbounds").any { it.asJsonObject.get("tag").asString == "colitu-verify-spare" })
        assertFalse(detached.toString().contains("warm-spare"))
        // Without a spare no spare check inbound is added at all.
        val single = JsonParser.parseString(XrayMobileAdapter.withVerifyInbound(primary, LocalProxy(1, "a", "b"), LocalProxy(2, "c", "d"))).asJsonObject
        assertFalse(single.toString().contains("colitu-verify-spare"))
    }
}
