package com.v2ray.ang.colitu.app

import com.v2ray.ang.colitu.app.ColituTransportOrder.StallMove
import com.v2ray.ang.colitu.app.ColituTransportOrder.WatchAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adaptive Connect audit 2026-10-27: stall watcher decisions and the probe round. */
class ColituStallWatchTest {
    @Test
    fun probeRoundReturnsWithoutWaitingForStragglers() = runBlocking {
        val started = System.nanoTime()
        // The preferred transport answers; the other is stuck in a "native call" for 3 s.
        val walked = mutableListOf<Int>()
        val winner = ColituTransportOrder.firstInOrder(2, 5_000L, probe = { i ->
            if (i == 0) 20L else { Thread.sleep(3_000); 30L }
        }) { i, _ -> walked += i }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(0, winner)
        assertEquals(listOf(0), walked)
        assertTrue("round took $tookMs ms", tookMs < 1_500)
    }

    @Test
    fun probeRoundStopsAtItsTimeout() = runBlocking {
        val started = System.nanoTime()
        val results = mutableMapOf<Int, Long>()
        val winner = ColituTransportOrder.firstInOrder(3, 400L, probe = { i ->
            when (i) {
                0 -> { Thread.sleep(3_000); 10L }
                1 -> -1L
                else -> 15L
            }
        }) { i, ms -> results[i] = ms }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        // The first one timed out, the second failed, the third answered in time.
        assertEquals(2, winner)
        assertEquals(mapOf(0 to -1L, 1 to -1L, 2 to 15L), results)
        assertTrue("round took $tookMs ms", tookMs < 1_500)
    }

    @Test
    fun probeRoundWithoutAnswerAndThrowingProbe() = runBlocking {
        val winner = ColituTransportOrder.firstInOrder(2, 300L, probe = { i -> if (i == 0) error("jni") else -1L })
        assertEquals(-1, winner)
    }

    @Test
    fun spareAnsweringAloneOverridesBothDead() {
        assertEquals(WatchAction.SpareCarries, ColituTransportOrder.confirmBothDead(WatchAction.BothDead, spareOk = true))
        assertEquals(WatchAction.BothDead, ColituTransportOrder.confirmBothDead(WatchAction.BothDead, spareOk = false))
        assertEquals(WatchAction.BothDead, ColituTransportOrder.confirmBothDead(WatchAction.BothDead, spareOk = null))
        assertEquals(WatchAction.Fine, ColituTransportOrder.confirmBothDead(WatchAction.Fine, spareOk = true))
    }

    @Test
    fun singleTransportNeverKeepsADeadTunnel() {
        // Automatic: the next server (the gap does not matter, it is not this transport again).
        assertEquals(StallMove.NextServer, ColituTransportOrder.stallMove(nothingElse = true, automatic = true, inGap = false))
        assertEquals(StallMove.NextServer, ColituTransportOrder.stallMove(nothingElse = true, automatic = true, inGap = true))
        // The spare on another server died too: the network, the server is kept.
        assertEquals(StallMove.ReconnectSame, ColituTransportOrder.stallMove(nothingElse = true, automatic = true, inGap = false, spareOnOtherServer = true))
        // Manual: the same server, after the gap.
        assertEquals(StallMove.Wait, ColituTransportOrder.stallMove(nothingElse = true, automatic = false, inGap = true))
        assertEquals(StallMove.ReconnectSame, ColituTransportOrder.stallMove(nothingElse = true, automatic = false, inGap = false))
    }

    @Test
    fun gapOnlyKeepsTheLeftTransportFromComingBack() {
        // Other transports on offer: always a switch, inside the gap too.
        assertEquals(StallMove.SwitchTransport, ColituTransportOrder.stallMove(nothingElse = false, automatic = false, inGap = true))
        assertEquals(StallMove.SwitchTransport, ColituTransportOrder.stallMove(nothingElse = false, automatic = true, inGap = true))
        assertEquals("hysteria2", ColituTransportOrder.avoidAfterSwitch(true, "hysteria2", "s1", "s1", "trojan"))
        assertNull(ColituTransportOrder.avoidAfterSwitch(false, "hysteria2", "s1", "s1", "trojan"))
        assertNull(ColituTransportOrder.avoidAfterSwitch(true, "hysteria2", "s2", "s1", "trojan"))
        assertNull(ColituTransportOrder.avoidAfterSwitch(true, "trojan", "s1", "s1", "trojan"))
        assertNull(ColituTransportOrder.avoidAfterSwitch(true, null, null, "s1", "trojan"))
    }

    @Test
    fun frozenWatcherRestartsItsMissStreak() {
        assertTrue(ColituTransportOrder.restartStreakAfterPause(lateMs = 45_000, thresholdMs = 20_000, misses = 2))
        assertFalse(ColituTransportOrder.restartStreakAfterPause(lateMs = 45_000, thresholdMs = 20_000, misses = 0))
        assertFalse(ColituTransportOrder.restartStreakAfterPause(lateMs = 3_000, thresholdMs = 20_000, misses = 2))
    }

    @Test
    fun manualModeNeverPenalizesAServer() {
        assertTrue(ColituTransportOrder.penalizeFailedServer(automatic = true))
        assertFalse(ColituTransportOrder.penalizeFailedServer(automatic = false))
    }
}
