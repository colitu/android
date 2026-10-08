package com.v2ray.ang.colitu.app

import com.v2ray.ang.colitu.data.ColituVpnConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColituTransportOrderTest {
    private fun config(protocol: String) = ColituVpnConfig(
        serverId = "server-1",
        configType = "xray-mobile-v1",
        protocolType = protocol,
        rawConfig = "{}",
        expiresAt = null,
        unlimited = false,
    )

    private val offered = listOf(config("vless-reality"), config("hysteria2"), config("trojan"))

    @Test
    fun midSessionStallPutsHysteria2LastWithoutProbe() {
        val stalled = ColituTransportOrder.stalled(connectTime = null, midSession = "hysteria2")
        // Hysteria2 last worked here, but it stalled mid-session: no shortcut to it.
        assertNull(ColituTransportOrder.remembered(offered, "hysteria2", stalled))
        val order = ColituTransportOrder.byRank(offered, stalled).map { it.protocolType }
        assertEquals(listOf("vless-reality", "trojan", "hysteria2"), order)
    }

    @Test
    fun rememberedTcpTransportStartsDirectlyWhileHysteria2IsStalled() {
        val stalled = ColituTransportOrder.stalled(connectTime = null, midSession = "hysteria2")
        val order = ColituTransportOrder.remembered(offered, "vless-reality", stalled)?.map { it.protocolType }
        assertEquals(listOf("vless-reality", "trojan", "hysteria2"), order)
    }

    @Test
    fun withoutStallHysteria2LeadsAndARememberedTcpTransportDefersToTheProbe() {
        val none = ColituTransportOrder.stalled(null, null)
        assertEquals("hysteria2", ColituTransportOrder.byRank(offered, none).first().protocolType)
        assertNull(ColituTransportOrder.remembered(offered, "vless-reality", none))
        assertEquals("hysteria2", ColituTransportOrder.remembered(offered, "hysteria2", none)?.first()?.protocolType)
    }

    @Test
    fun midSessionStallKeyIsPerServer() {
        assertNotEquals(
            ColituTransportOrder.midSessionStallKey("server-1", "hysteria2"),
            ColituTransportOrder.midSessionStallKey("server-2", "hysteria2"),
        )
        assertEquals("stall_until_server-1|hysteria2", ColituTransportOrder.midSessionStallKey("server-1", "hysteria2"))
    }
}
