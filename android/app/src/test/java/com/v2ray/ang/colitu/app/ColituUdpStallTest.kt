package com.v2ray.ang.colitu.app

import com.v2ray.ang.colitu.data.ColituConnectMemory
import com.v2ray.ang.colitu.data.ColituNetworkHintsPolicy
import com.v2ray.ang.colitu.data.ColituServerRanking
import com.v2ray.ang.colitu.data.ColituTransportChoice
import com.v2ray.ang.colitu.data.ColituVpnConfig
import com.v2ray.ang.colitu.data.ColituWarmSpare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diagnostic report 2026-10-26 (Xiaomi, Android 16, Wi-Fi): Hysteria2 stalled
 * every 2-5 minutes on six servers of three providers, and every recovery
 * went back to Hysteria2 (on the next server, with a Hysteria2 spare).
 */
class ColituUdpStallTest {
    private val net = ColituServerRanking.networkKey("wifi", "EE-AS0001")
    private val now = 1_000_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val all = listOf("hysteria2", "vless-reality", "vless-xhttp", "trojan", "shadowsocks")

    @Test
    fun repeatedUdpStallsPutUdpLastOnTheNetwork() {
        val memory = ColituConnectMemory.empty()
        assertFalse(memory.recordUdpStall(net, now))
        assertFalse(memory.udpDemoted(net, now))
        // Hysteria2 connecting again (on another server) does not clear the count: it always connects.
        memory.recordSuccess(net, "fi", "hysteria2", now + minute)
        assertTrue(memory.recordUdpStall(net, now + 4 * minute))
        assertTrue(memory.udpDemoted(net, now + 4 * minute))
        memory.recordSuccess(net, "fi", "hysteria2", now + 5 * minute)
        assertTrue(memory.udpDemoted(net, now + 5 * minute))
        // Only this network, and for 6 h.
        assertFalse(memory.udpDemoted(ColituServerRanking.networkKey("cellular", "EE-AS0002"), now + 5 * minute))
        assertTrue(memory.udpDemoted(net, now + 4 * minute + ColituConnectMemory.UDP_DEMOTED_TTL_MS - 1))
        assertFalse(memory.udpDemoted(net, now + 4 * minute + ColituConnectMemory.UDP_DEMOTED_TTL_MS))
        // Survives a restart (stored with the rest of the memory).
        assertTrue(ColituConnectMemory.fromJson(memory.toJson(), now + 5 * minute).udpDemoted(net, now + 5 * minute))
    }

    @Test
    fun stallsFarApartDoNotDemote() {
        val memory = ColituConnectMemory.empty()
        memory.recordUdpStall(net, now)
        assertFalse(memory.recordUdpStall(net, now + 31 * minute))
        assertFalse(memory.udpDemoted(net, now + 31 * minute))
    }

    @Test
    fun aUdpPrimaryAndUdpSpareDyingTogetherDemoteAtOnce() {
        assertTrue(ColituTransportOrder.udpPair("hysteria2", "hysteria2"))
        assertFalse(ColituTransportOrder.udpPair("hysteria2", "vless-reality"))
        assertFalse(ColituTransportOrder.udpPair("hysteria2", null))
        val memory = ColituConnectMemory.empty()
        assertTrue(memory.recordUdpStall(net, now, count = 2))
        assertTrue(memory.udpDemoted(net, now))
    }

    @Test
    fun demotedUdpGoesLastEvenThoughItWorkedHere() {
        assertEquals(setOf("hysteria2"), ColituNetworkHintsPolicy.demoted(emptySet(), udpDemoted = true) { true })
        assertTrue(ColituNetworkHintsPolicy.demoted(emptySet()) { true }.isEmpty())
        // The spare then never takes Hysteria2 while a TCP transport qualifies.
        val hinted = ColituNetworkHintsPolicy.demoted(emptySet(), udpDemoted = true) { true }
        assertEquals(
            "vless-reality",
            ColituWarmSpare.spareTransport("hysteria2", all, emptySet(), sameServer = false, hinted = hinted, worked = setOf("hysteria2")),
        )
    }

    @Test
    fun udpPrimaryPrefersAProvenTcpSpareOnAnotherServer() {
        // Both proven here: the TCP one, so the core can move over without the app.
        assertEquals(
            "vless-xhttp" to "proven",
            ColituWarmSpare.spareChoice("hysteria2", all, emptySet(), sameServer = false, worked = setOf("hysteria2", "vless-xhttp")),
        )
        // Only Shadowsocks proven besides Hysteria2: Hysteria2 as before.
        assertEquals(
            "hysteria2" to "proven",
            ColituWarmSpare.spareChoice("hysteria2", all, emptySet(), sameServer = false, worked = setOf("hysteria2", "shadowsocks")),
        )
        // A TCP primary keeps its own proven transport on the next server.
        assertEquals(
            "vless-reality" to "proven",
            ColituWarmSpare.spareChoice("vless-reality", all, emptySet(), sameServer = false, worked = setOf("hysteria2", "vless-reality")),
        )
    }

    @Test
    fun pickedMethodGoesFirstUnlessItStalledInThisConnect() {
        fun config(p: String) = ColituVpnConfig("ee", null, "xray-mobile-v1", p, "{}", null, false)
        val order = all.map(::config)
        fun pick(method: String?, skip: Set<String> = emptySet()) =
            ColituTransportChoice.putFirst(order, { it.protocolType }, method, skip).map { it.protocolType }
        assertEquals(listOf("vless-xhttp", "hysteria2", "vless-reality", "trojan", "shadowsocks"), pick("vless-xhttp"))
        assertEquals(all, pick(""))
        assertEquals(all, pick("wireguard"))
        assertEquals(all, pick("vless-xhttp", skip = setOf("vless-xhttp")))
        // Not offered on this server (a multihop route: VLESS only): the automatic order.
        val vlessOnly = order.filter { it.protocolType!!.startsWith("vless") }
        assertEquals(listOf("vless-reality", "vless-xhttp"), ColituTransportChoice.putFirst(vlessOnly, { it.protocolType }, "trojan").map { it.protocolType })
        assertEquals("hysteria2", ColituTransportChoice.normalize(" Hysteria2 "))
        assertEquals("", ColituTransportChoice.normalize(null))
    }
}
