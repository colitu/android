package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class XrayMobileAdapterTest {
    private val now = Instant.parse("2026-09-11T10:00:00Z")

    @Test
    fun rendersRealityProfileWithoutLeakingEnvelopeFields() {
        val result = XrayMobileAdapter.render(envelope(), now)
        val runtime = JsonParser.parseString(requireNotNull(result.rawConfig)).asJsonObject
        val outbound = runtime.getAsJsonArray("outbounds")[0].asJsonObject
        assertEquals("vless", outbound.get("protocol").asString)
        assertEquals("reality", outbound.getAsJsonObject("streamSettings").get("security").asString)
        assertEquals(42L, result.revision)
        assertFalse(requireNotNull(result.rawConfig).contains("offline_grace_until"))
    }

    @Test
    fun rejectsExpiredOfflineGrace() {
        try {
            XrayMobileAdapter.render(envelope(), Instant.parse("2026-09-13T10:00:01Z"))
            fail("expired profile was accepted")
        } catch (expected: IllegalArgumentException) {
            assertEquals("CONFIG_EXPIRED", expected.message)
        }
    }

    @Test
    fun preservesBackendDnsAndRoutingPolicies() {
        val source = envelope()
        val profile = source.getAsJsonObject("profile").getAsJsonObject("payload")
        val dns = JsonParser.parseString("""{"servers":["https://dns.example.test/dns-query"]}""").asJsonObject
        val routing = JsonParser.parseString("""{"domainStrategy":"AsIs","rules":[{"type":"field","domain":["example.test"],"outboundTag":"proxy"}]}""").asJsonObject
        profile.add("dns_policy", dns)
        profile.add("routing_policy", routing)
        val runtime = JsonParser.parseString(XrayMobileAdapter.render(source, now).rawConfig).asJsonObject
        assertEquals(dns, runtime.getAsJsonObject("dns"))
        assertEquals(routing, runtime.getAsJsonObject("routing"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonpositiveRevision() {
        XrayMobileAdapter.render(envelope().apply { addProperty("revision", 0) }, now)
    }

    @Test
    fun acceptsUnsignedRevisionAboveLongMax() {
        // The panel sends the first 8 bytes of a SHA-256 as uint64; half of
        // all revisions are above Long.MAX_VALUE and used to be rejected.
        val source = JsonParser.parseString(
            envelope().toString().replace("\"revision\":42", "\"revision\":13345678901234567890"),
        ).asJsonObject
        val result = XrayMobileAdapter.render(source, now)
        assertTrue(result.revision != 0L)
        assertEquals("13345678901234567890", java.lang.Long.toUnsignedString(result.revision))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRevisionWiderThan64Bits() {
        XrayMobileAdapter.render(
            JsonParser.parseString(envelope().toString().replace("\"revision\":42", "\"revision\":18446744073709551616")).asJsonObject,
            now,
        )
    }

    @Test
    fun localSocksInboundAlwaysRequiresTheSessionAccount() {
        val raw = requireNotNull(XrayMobileAdapter.render(envelope(), now).rawConfig)
        val placeholder = JsonParser.parseString(raw).asJsonObject.getAsJsonArray("inbounds")[0].asJsonObject
        assertEquals("password", placeholder.getAsJsonObject("settings").get("auth").asString)
        assertEquals("127.0.0.1", placeholder.get("listen").asString)

        val proxy = com.v2ray.ang.colitu.api.LocalProxy(34567, "user-a", "secret-b")
        val runtime = JsonParser.parseString(XrayMobileAdapter.withLocalProxy(raw, proxy)).asJsonObject
        val inbounds = runtime.getAsJsonArray("inbounds")
        assertEquals(1, inbounds.size())
        val socks = inbounds[0].asJsonObject
        assertEquals(34567, socks.get("port").asInt)
        val settings = socks.getAsJsonObject("settings")
        assertEquals("password", settings.get("auth").asString)
        val account = settings.getAsJsonArray("accounts")[0].asJsonObject
        assertEquals("user-a", account.get("user").asString)
        assertEquals("secret-b", account.get("pass").asString)
    }

    @Test
    fun runtimeHasNoAccessLog() {
        val log = JsonParser.parseString(XrayMobileAdapter.render(envelope(), now).rawConfig).asJsonObject.getAsJsonObject("log")
        assertEquals("none", log.get("access").asString)
    }

    @Test
    fun visionFlowOnlyOnRawTcp() {
        val tcp = JsonParser.parseString(XrayMobileAdapter.render(envelope(), now).rawConfig).asJsonObject
        val tcpUser = tcp.getAsJsonArray("outbounds")[0].asJsonObject.getAsJsonObject("settings")
            .getAsJsonArray("vnext")[0].asJsonObject.getAsJsonArray("users")[0].asJsonObject
        assertEquals("xtls-rprx-vision", tcpUser.get("flow").asString)

        val source = envelope()
        source.getAsJsonObject("profile").getAsJsonObject("payload").getAsJsonObject("transport").addProperty("type", "grpc")
        val grpc = JsonParser.parseString(XrayMobileAdapter.render(source, now).rawConfig).asJsonObject
        val grpcUser = grpc.getAsJsonArray("outbounds")[0].asJsonObject.getAsJsonObject("settings")
            .getAsJsonArray("vnext")[0].asJsonObject.getAsJsonArray("users")[0].asJsonObject
        assertFalse(grpcUser.has("flow"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvertedLifetime() {
        XrayMobileAdapter.render(envelope().apply { addProperty("expires_at", "2026-09-14T00:00:00Z") }, now)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsProtocolSecurityMismatch() {
        XrayMobileAdapter.render(envelope().also {
            it.getAsJsonObject("profile").getAsJsonObject("payload")
                .getAsJsonObject("security").addProperty("type", "tls")
        }, now)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedTransport() {
        XrayMobileAdapter.render(envelope().also {
            it.getAsJsonObject("profile").getAsJsonObject("payload")
                .getAsJsonObject("transport").addProperty("type", "quic")
        }, now)
    }

    @Test
    fun rendersEveryCandidateTransportWithHysteria2AsNativeQuic() {
        val source = envelope()
        source.add("candidates", JsonParser.parseString(
            """[
              {"protocol":"hysteria2","profile":{"format":"xray-mobile-v1","payload":{
                "schema_version":1,"protocol":"hysteria2",
                "endpoint":{"host":"hy.example.test","port":8443},
                "credentials":{"password":"fixture-password"},
                "transport":{"type":"hysteria"},
                "security":{"type":"tls","server_name":"hy.example.test"}}}},
              {"protocol":"vless-reality","profile":{"format":"xray-mobile-v1","payload":{"schema_version":1,"protocol":"vless-reality"}}},
              {"protocol":"tuic","profile":{"format":"xray-mobile-v1","payload":{"schema_version":1,"protocol":"tuic"}}}
            ]""",
        ))
        val configs = XrayMobileAdapter.renderCandidates(source, now)
        assertEquals(listOf("vless-reality", "hysteria2"), configs.map { it.protocolType })

        val runtime = JsonParser.parseString(requireNotNull(configs[1].rawConfig)).asJsonObject
        val outbound = runtime.getAsJsonArray("outbounds")[0].asJsonObject
        assertEquals("proxy", outbound.get("tag").asString)
        assertEquals("hysteria", outbound.get("protocol").asString)
        assertEquals("hy.example.test", outbound.getAsJsonObject("settings").get("address").asString)
        val stream = outbound.getAsJsonObject("streamSettings")
        assertEquals("hysteria", stream.get("network").asString)
        assertEquals("tls", stream.get("security").asString)
        assertEquals("fixture-password", stream.getAsJsonObject("hysteriaSettings").get("auth").asString)
        assertEquals("hy.example.test", stream.getAsJsonObject("tlsSettings").get("serverName").asString)
        // Counters for the live speed on the home screen.
        assertTrue(runtime.getAsJsonObject("policy").getAsJsonObject("system").get("statsOutboundDownlink").asBoolean)
    }

    @Test
    fun hysteria2IsPreferredOverTcpTransports() {
        val rank = XrayMobileAdapter.transportRank
        assertTrue(rank.getValue("hysteria2") < rank.getValue("vless-reality"))
        assertTrue(rank.getValue("vless-reality") < rank.getValue("trojan"))
        assertTrue(rank.getValue("trojan") < rank.getValue("shadowsocks"))
    }

    private fun envelope() = JsonParser.parseString(
        """{
          "revision":42,
          "expires_at":"2026-09-12T10:00:00Z",
          "offline_grace_until":"2026-09-13T10:00:00Z",
          "server":{"id":"server-1"},
          "profile":{"format":"xray-mobile-v1","payload":{
            "schema_version":1,"protocol":"vless-reality",
            "endpoint":{"host":"vpn.example.test","port":443},
            "credentials":{"uuid":"11111111-1111-4111-8111-111111111111"},
            "transport":{"type":"tcp"},
            "security":{"type":"reality","server_name":"cdn.example.test","public_key":"public","short_id":"abcd","fingerprint":"chrome"}
          }}
        }""",
    ).asJsonObject
}
