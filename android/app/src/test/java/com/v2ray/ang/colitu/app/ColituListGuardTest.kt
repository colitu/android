package com.v2ray.ang.colitu.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A list or model fetched through a bridge names the exit: it must not become the device's home. */
class ColituListGuardTest {
    private fun off(
        status: VpnStatus = VpnStatus.Disconnected,
        serviceUp: Boolean = false,
        serviceHeld: Boolean = false,
        starting: Boolean = false,
        coreUp: Boolean = false,
    ) = ColituListGuard.vpnOff(status, serviceUp, serviceHeld, starting, coreUp)

    @Test
    fun onlyAnIdleAppWithNoServiceOrCoreIsOff() {
        assertTrue(off())
    }

    @Test
    fun everyStatusButDisconnectedIsOn() {
        VpnStatus.entries.filter { it != VpnStatus.Disconnected }.forEach { assertFalse(it.name, off(status = it)) }
    }

    @Test
    fun aTunnelTheStatusDoesNotKnowYetIsOn() {
        // App restarted with the tunnel up, tile, Always-on: the status catches up later.
        assertFalse(off(coreUp = true))
        assertFalse(off(serviceUp = true))
        assertFalse(off(serviceHeld = true))
        assertFalse(off(starting = true))
    }

    @Test
    fun theListCountsOnlyWhenAskedAndAnsweredWithTheVpnOff() {
        assertTrue(ColituListGuard.trustsPlace(offWhenAsked = true, offWhenAnswered = true))
        // Asked through the tunnel, answered after it went down: still the exit.
        assertFalse(ColituListGuard.trustsPlace(offWhenAsked = false, offWhenAnswered = true))
        // The tunnel came up meanwhile: kept out, the next list with the VPN off tells.
        assertFalse(ColituListGuard.trustsPlace(offWhenAsked = true, offWhenAnswered = false))
        assertFalse(ColituListGuard.trustsPlace(offWhenAsked = false, offWhenAnswered = false))
    }

    @Test
    fun theModelIsFetchedThroughTheVpnOnlyWithAToken() {
        assertTrue(ColituListGuard.fetchesModel(vpnOff = true, token = null))
        assertTrue(ColituListGuard.fetchesModel(vpnOff = false, token = "signed"))
        assertFalse(ColituListGuard.fetchesModel(vpnOff = false, token = null))
        assertFalse(ColituListGuard.fetchesModel(vpnOff = false, token = " "))
    }
}
