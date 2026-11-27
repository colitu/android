package com.v2ray.ang.colitu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituConnectGateTest {
    @Test fun tenQuickStartsForTheSameTargetRunOnce() {
        val gate = ColituConnectGate(windowMs = 300)
        var started = 0
        var running = false
        for (i in 0 until 10) {
            if (gate.shouldStart("node-1|wl=node-1|m=gecko", 1_000L + i * 150L / 10, running)) {
                started++
                running = true
            }
        }
        assertEquals(1, started)
    }

    @Test fun aNewTargetOrALaterTapStillStarts() {
        val gate = ColituConnectGate(windowMs = 300)
        assertTrue(gate.shouldStart("node-1|wl=", 0, running = false))
        // The whitelist row of the same node is another target: it starts at once.
        assertTrue(gate.shouldStart("node-1|wl=node-1", 50, running = true))
        assertFalse(gate.shouldStart("node-1|wl=node-1", 100, running = true))
        // After the window, the same target starts again.
        assertTrue(gate.shouldStart("node-1|wl=node-1", 400, running = true))
        // A flow that already ended never folds a new start.
        assertTrue(gate.shouldStart("node-1|wl=node-1", 450, running = false))
    }

    /** Switching to a Whitelist row while connected over a picked ColituGecko runs no Gecko step. */
    @Test fun aWhitelistRowSwitchRunsNoGeckoStep() {
        assertFalse(ColituGeckoRules.pickApplies(ColituGeckoRules.PROTOCOL, whitelistRowSelected = true))
        assertFalse(ColituGeckoRules.autoStepAllowed(whitelistRowSelected = true))
    }
}
