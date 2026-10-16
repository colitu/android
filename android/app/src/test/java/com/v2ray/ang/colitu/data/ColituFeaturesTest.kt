package com.v2ray.ang.colitu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituFeaturesTest {
    @Test
    fun shippedBuildHasAdaptiveConnect3Off() {
        // The next release goes out with the switch off (-PcolituAdaptiveConnect3 not set).
        assertFalse(ColituFeatures.ADAPTIVE_CONNECT_3)
    }

    @Test
    fun switchOffDisablesHintedStartAndRecoverySet() {
        assertFalse(ColituFeatures.adaptiveConnect3(flag = false, recoveryTest = false, remote = false))
        assertFalse(ColituFeatures.hintedStart(flag = false, recoveryTest = false, remote = false))
        assertFalse(ColituFeatures.recoverySet(flag = false, recoveryTest = false, remote = false))
    }

    @Test
    fun panelRolloutEnablesBoth() {
        assertTrue(ColituFeatures.adaptiveConnect3(flag = false, recoveryTest = false, remote = true))
        assertTrue(ColituFeatures.hintedStart(flag = false, recoveryTest = false, remote = true))
        assertTrue(ColituFeatures.recoverySet(flag = false, recoveryTest = false, remote = true))
    }

    @Test
    fun withoutStoredValueTheRemoteSwitchReadsOff() {
        // No MMKV on the JVM: the stored value cannot be read and counts as off.
        assertFalse(ColituRemoteFeatures.adaptiveConnect3)
    }

    @Test
    fun serverListCarriesTheFeatures() {
        val on = ColituServerListResponse.fromJson(
            com.google.gson.JsonParser.parseString("""{"servers":[],"features":{"adaptive_connect_3":true,"bad":"x"}}""").asJsonObject,
        )
        assertEquals(mapOf("adaptive_connect_3" to true), on.features)
        val old = ColituServerListResponse.fromJson(com.google.gson.JsonParser.parseString("""{"servers":[]}""").asJsonObject)
        assertEquals(null, old.features)
    }

    @Test
    fun switchOnEnablesBoth() {
        assertTrue(ColituFeatures.hintedStart(flag = true, recoveryTest = false))
        assertTrue(ColituFeatures.recoverySet(flag = true, recoveryTest = false))
    }

    @Test
    fun recoveryTestBuildForcesTheFeatureOn() {
        assertTrue(ColituFeatures.adaptiveConnect3(flag = false, recoveryTest = true))
        assertTrue(ColituFeatures.hintedStart(flag = false, recoveryTest = true))
        assertTrue(ColituFeatures.recoverySet(flag = false, recoveryTest = true))
    }

    @Test
    fun giveUpPathTurnsToTheSetOnlyWithTheSwitch() {
        // Conditions that would send a connect to the set...
        assertTrue(ColituRecoverySet.shouldTryAtGiveUp(automatic = true, apiUnreachable = true, alreadyTried = false))
        // ...do so only when the feature is on.
        assertFalse(ColituFeatures.recoveryAtGiveUp(true, true, false, flag = false, recoveryTest = false))
        assertTrue(ColituFeatures.recoveryAtGiveUp(true, true, false, flag = true, recoveryTest = false))
        assertTrue(ColituFeatures.recoveryAtGiveUp(true, true, false, flag = false, recoveryTest = true))
        // The existing conditions still apply when on.
        assertFalse(ColituFeatures.recoveryAtGiveUp(true, true, true, flag = true, recoveryTest = false))
        assertFalse(ColituFeatures.recoveryAtGiveUp(false, true, false, flag = true, recoveryTest = false))
        assertFalse(ColituFeatures.recoveryAtGiveUp(true, false, false, flag = true, recoveryTest = false))
    }

    @Test
    fun recoveryFetchSendsTheClientCountryWhenKnown() {
        assertEquals("/client/recovery?client_country=TR", ColituRecoverySet.fetchPath("TR"))
        assertEquals("/client/recovery?client_country=RU", ColituRecoverySet.fetchPath(" ru "))
        assertEquals("/client/recovery", ColituRecoverySet.fetchPath(null))
        assertEquals("/client/recovery", ColituRecoverySet.fetchPath(""))
        assertEquals("/client/recovery", ColituRecoverySet.fetchPath("TUR"))
        assertEquals("/client/recovery", ColituRecoverySet.fetchPath("T&"))
    }
}
