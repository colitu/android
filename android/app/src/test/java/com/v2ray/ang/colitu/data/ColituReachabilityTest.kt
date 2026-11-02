package com.v2ray.ang.colitu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituReachabilityTest {
    private val net = "wifi|AS1"
    private val ids = listOf("a", "b", "c", "d", "e")
    private val t0 = 1_000_000_000L

    /** [failed] time out, every other server answers. */
    private fun round(vararg failed: String): Map<String, Int?> = ids.associateWith { if (it in failed) null else 40 }

    private fun ColituReachability.hide(keep: Set<String> = emptySet(), now: Long = t0, network: String = net) =
        hidden(ids, keep, network, now)

    @Test fun oneFailedRoundHidesNothing() {
        val r = ColituReachability().afterRound(net, round("a"), t0)
        assertFalse(r.unreachable("a"))
        assertEquals(emptySet<String>(), r.hide())
    }

    @Test fun twoFailedRoundsInARowHide() {
        val r = ColituReachability().afterRound(net, round("a"), t0).afterRound(net, round("a"), t0)
        assertTrue(r.unreachable("a"))
        assertEquals(setOf("a"), r.hide())
    }

    @Test fun aSuccessBetweenFailuresResetsTheCount() {
        val r = ColituReachability()
            .afterRound(net, round("a"), t0)
            .afterRound(net, round(), t0)
            .afterRound(net, round("a"), t0)
        assertEquals(emptySet<String>(), r.hide())
    }

    @Test fun oneSuccessBringsAServerBack() {
        val down = ColituReachability().afterRound(net, round("a"), t0).afterRound(net, round("a"), t0)
        assertEquals(emptySet<String>(), down.afterRound(net, round(), t0).hide())
    }

    @Test fun aSuccessOutsideTheTunnelWhileConnectedBringsItBack() {
        val down = ColituReachability().afterRound(net, round("a"), t0).afterRound(net, round("a"), t0)
        assertEquals(emptySet<String>(), down.afterSuccesses(net, listOf("a")).hide())
        // Another network's success says nothing here.
        assertEquals(setOf("a"), down.afterSuccesses("cellular|AS2", listOf("a")).hide())
    }

    @Test fun nothingIsHiddenWhenEveryServerFailed() {
        val r = ColituReachability().afterRound(net, round(*ids.toTypedArray()), t0).afterRound(net, round(*ids.toTypedArray()), t0)
        assertTrue(r.networkProblem)
        assertEquals(emptySet<String>(), r.hide())
    }

    @Test fun nothingIsHiddenWhenHalfOrMoreFailed() {
        // 3 of 5 failed twice: the network is the problem, and such rounds count against nobody.
        val r = ColituReachability().afterRound(net, round("a", "b", "c"), t0).afterRound(net, round("a", "b", "c"), t0)
        assertTrue(r.networkProblem)
        assertFalse(r.unreachable("a"))
        assertEquals(emptySet<String>(), r.hide())
    }

    @Test fun aNetworkProblemRoundDoesNotCountAgainstAServer() {
        // a failed once, then the whole network failed, then a failed again: two real failures in a row.
        val r = ColituReachability()
            .afterRound(net, round("a"), t0)
            .afterRound(net, round(*ids.toTypedArray()), t0)
        assertFalse(r.unreachable("a"))
        assertEquals(setOf("a"), r.afterRound(net, round("a"), t0).hide())
    }

    @Test fun halfOfTheListDownHidesNothing() {
        // Two separate rounds of 2/5 failures each make 2 unreachable; 4 of the listed 4 is too many.
        val r = ColituReachability().afterRound(net, round("a", "b"), t0).afterRound(net, round("a", "b"), t0)
        assertEquals(setOf("a", "b"), r.hide())
        assertEquals(emptySet<String>(), r.hidden(listOf("a", "b", "c", "d"), emptySet(), net, t0))
    }

    @Test fun connectedConnectingOrChosenServerIsNeverHidden() {
        val r = ColituReachability().afterRound(net, round("a", "b"), t0).afterRound(net, round("a", "b"), t0)
        assertEquals(setOf("b"), r.hide(keep = setOf("a")))
    }

    @Test fun anotherNetworkStartsFromScratchAndHidesNothing() {
        val r = ColituReachability().afterRound(net, round("a"), t0).afterRound(net, round("a"), t0)
        assertEquals(emptySet<String>(), r.hide(network = "cellular|AS2"))
        val other = r.afterRound("cellular|AS2", round("a"), t0)
        assertFalse(other.unreachable("a"))
    }

    @Test fun oldStateHidesNothing() {
        val r = ColituReachability().afterRound(net, round("a"), t0).afterRound(net, round("a"), t0)
        assertEquals(emptySet<String>(), r.hide(now = t0 + ColituReachability.STATE_TTL_MS))
        assertEquals(emptySet<String>(), r.hide(now = t0 - 1))
    }

    @Test fun jsonRoundTrip() {
        val r = ColituReachability().afterRound(net, round("a"), t0).afterRound(net, round("a", "b"), t0)
        assertEquals(r, ColituReachability.fromJson(r.toJson()))
        assertEquals(ColituReachability(), ColituReachability.fromJson("not json"))
        assertEquals(ColituReachability(), ColituReachability.fromJson(null))
    }

    // ── What a row shows ───────────────────────────────────────────────────

    @Test fun vpnOffShowsTheLastPing() {
        assertEquals(42, ColituReachability.displayMs(ColituPing(42, t0, net), vpnOn = false, now = t0 + 86_400_000L))
        assertNull(ColituReachability.displayMs(ColituPing(null, t0, net), vpnOn = false, now = t0))
        assertNull(ColituReachability.displayMs(null, vpnOn = false, now = t0))
    }

    @Test fun vpnOnShowsAPingUpTo30MinutesOld() {
        val ping = ColituPing(42, t0, net)
        assertEquals(42, ColituReachability.displayMs(ping, vpnOn = true, now = t0 + ColituReachability.CACHE_WHILE_CONNECTED_MS))
        assertNull(ColituReachability.displayMs(ping, vpnOn = true, now = t0 + ColituReachability.CACHE_WHILE_CONNECTED_MS + 1))
        assertNull(ColituReachability.displayMs(ping, vpnOn = true, now = t0 - 1))
        assertNull(ColituReachability.displayMs(ColituPing(null, t0, net), vpnOn = true, now = t0))
    }
}
