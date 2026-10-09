package com.v2ray.ang.colitu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituUiModeTest {
    @Test
    fun newInstallStartsSimpleAndAnUpdateKeepsAdvanced() {
        assertFalse(ColituUiMode.initialAdvanced(stored = null, signedIn = false, priorUse = false))
        assertTrue(ColituUiMode.initialAdvanced(stored = null, signedIn = true, priorUse = false))
        // Signed out after using an earlier version: still an update.
        assertTrue(ColituUiMode.initialAdvanced(stored = null, signedIn = false, priorUse = true))
        // A stored choice always wins.
        assertFalse(ColituUiMode.initialAdvanced(stored = false, signedIn = true, priorUse = true))
        assertTrue(ColituUiMode.initialAdvanced(stored = true, signedIn = false, priorUse = false))
    }

    @Test
    fun simpleModeConnectsAutomaticallyInsteadOfAChosenRoute() {
        // Simple: a selected multihop route falls back to the automatic choice.
        assertTrue(ColituUiMode.connectsAutomatically(advanced = false, autoSelection = false, selectedIsMultihop = true))
        // Advanced: the route (the stored choice) comes back.
        assertFalse(ColituUiMode.connectsAutomatically(advanced = true, autoSelection = false, selectedIsMultihop = true))
        // A chosen country stays chosen in both modes.
        assertFalse(ColituUiMode.connectsAutomatically(advanced = false, autoSelection = false, selectedIsMultihop = false))
        assertTrue(ColituUiMode.connectsAutomatically(advanced = true, autoSelection = true, selectedIsMultihop = false))
    }

    @Test
    fun warmSpareIsAlwaysOnInSimpleMode() {
        assertTrue(ColituUiMode.warmSpareActive(advanced = false, setting = false))
        assertEquals(false, ColituUiMode.warmSpareActive(advanced = true, setting = false))
        assertTrue(ColituUiMode.warmSpareActive(advanced = true, setting = true))
    }
}
