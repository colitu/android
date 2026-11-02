package com.v2ray.ang.colitu.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-02: Android left CoreVpnService "Pending, app=null"; the connect blamed every server. */
class ColituEngineStartTest {
    @Test
    fun timeoutWithoutTheServiceIsEngineNotStarted() {
        assertEquals(ColituEngineStart.ENGINE_NOT_STARTED, ColituEngineStart.timeoutCode(serviceSeen = false))
    }

    @Test
    fun timeoutWithTheServiceUpIsATunnelTimeout() {
        assertEquals(ColituEngineStart.TUNNEL_TIMEOUT, ColituEngineStart.timeoutCode(serviceSeen = true))
    }

    @Test
    fun engineNotStartedBlamesNoServer() {
        assertFalse(ColituEngineStart.blamesServer("ENGINE_NOT_STARTED"))
        assertFalse(ColituEngineStart.blamesServer("engine_not_started"))
        assertTrue(ColituEngineStart.blamesServer("TUNNEL_TIMEOUT"))
        assertTrue(ColituEngineStart.blamesServer("UNREACHABLE"))
        assertTrue(ColituEngineStart.blamesServer(null))
    }
}
