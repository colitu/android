package com.v2ray.ang.colitu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituFlapGuardTest {
    private val minute = 60_000L

    @Test
    fun aDropRightAfterTheConnectDemotesAtOnce() {
        val guard = ColituFlapGuard()
        val now = 1_794_390_000_000L
        assertTrue(guard.drop("wifi", "pl", "vless-reality", 16_000L, now))
        assertTrue(guard.isDemoted("wifi", "pl", "vless-reality", now))
        // Per server, transport and network.
        assertFalse(guard.isDemoted("wifi", "pl", "hysteria2", now))
        assertFalse(guard.isDemoted("wifi", "al", "vless-reality", now))
        assertFalse(guard.isDemoted("lte", "pl", "vless-reality", now))
        // It ends after 30 minutes.
        assertFalse(guard.isDemoted("wifi", "pl", "vless-reality", now + ColituFlapGuard.DEMOTED_FOR_MS))
    }

    @Test
    fun longSessionsNeedTwoDropsWithinTheWindow() {
        val guard = ColituFlapGuard()
        val now = 1_794_380_000_000L
        val long = 38 * minute
        assertFalse(guard.drop("wifi", "al", "vless-reality", long, now))
        assertFalse(guard.isDemoted("wifi", "al", "vless-reality", now))
        // 16 minutes later: outside the 15-minute window, still one drop.
        assertFalse(guard.drop("wifi", "al", "vless-reality", long, now + 16 * minute))
        // 10 minutes after that: two within the window.
        assertTrue(guard.drop("wifi", "al", "vless-reality", long, now + 26 * minute))
        assertTrue(guard.isDemoted("wifi", "al", "vless-reality", now + 27 * minute))
    }

    @Test
    fun dropsWithoutServerOrTransportAreIgnored() {
        val guard = ColituFlapGuard()
        assertFalse(guard.drop("wifi", null, "trojan", 0L, 0L))
        assertFalse(guard.drop("wifi", "pl", null, 0L, 0L))
        assertFalse(guard.isDemoted("wifi", "pl", null, 0L))
    }

    @Test
    fun demotedAndPenalizedGoLast_StableOtherwise() {
        val order = listOf("dead", "a", "b", "dead2", "c")
        assertEquals(listOf("a", "b", "c", "dead", "dead2"), ColituFlapGuard.penalizedLast(order) { it.startsWith("dead") })
        // Nothing penalized, or everything: the very same list.
        assertSame(order, ColituFlapGuard.penalizedLast(order) { false })
        assertSame(order, ColituFlapGuard.penalizedLast(order) { true })
        assertEquals(listOf("hysteria2", "trojan", "vless-reality"), ColituFlapGuard.demotedLast(listOf("vless-reality", "hysteria2", "trojan")) { it == "vless-reality" })
    }

    @Test
    fun nothingCanConnect_OnlyWhenEveryTransportIsASilentTcpOne() {
        val probed = mapOf("vless-reality" to -1L, "trojan" to -1L)
        assertTrue(ColituFlapGuard.nothingCanConnect(listOf("vless-reality", "trojan"), { it }, probed))
        // Hysteria2 is not probed (UDP): it may still connect.
        assertFalse(ColituFlapGuard.nothingCanConnect(listOf("hysteria2", "vless-reality", "trojan"), { it }, probed))
        assertFalse(ColituFlapGuard.nothingCanConnect(listOf("vless-reality", "trojan"), { it }, mapOf("vless-reality" to -1L, "trojan" to 70L)))
        assertFalse(ColituFlapGuard.nothingCanConnect(emptyList<String>(), { it }, probed))
    }
}
