package com.v2ray.ang.colitu.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituWarmSpareTest {
    // ── Which spare ────────────────────────────────────────────────────────

    private val all = listOf("hysteria2", "vless-reality", "vless-xhttp", "trojan", "shadowsocks")

    @Test
    fun udpPrimaryGetsATcpSpareInPreferenceOrder() {
        assertEquals("vless-reality", ColituWarmSpare.spareTransport("hysteria2", all, emptySet(), sameServer = true))
        assertEquals("vless-xhttp", ColituWarmSpare.spareTransport("hysteria2", all, setOf("vless-reality"), sameServer = true))
        assertEquals("shadowsocks", ColituWarmSpare.spareTransport("hysteria2", listOf("hysteria2", "shadowsocks"), emptySet(), sameServer = true))
    }

    @Test
    fun tcpPrimaryGetsHysteria2UnlessStalledThenAnotherTcpTransport() {
        assertEquals("hysteria2", ColituWarmSpare.spareTransport("vless-reality", all, emptySet(), sameServer = true))
        assertEquals("vless-xhttp", ColituWarmSpare.spareTransport("vless-reality", all, setOf("hysteria2"), sameServer = true))
        assertEquals("vless-reality", ColituWarmSpare.spareTransport("trojan", listOf("trojan", "vless-reality"), emptySet(), sameServer = true))
    }

    @Test
    fun noSpareWhenTheServerOffersOnlyOneTransport() {
        assertNull(ColituWarmSpare.spareTransport("vless-reality", listOf("vless-reality"), emptySet(), sameServer = true))
        // Another server may run the same transport as a last resort, unless it stalled there.
        assertEquals("vless-reality", ColituWarmSpare.spareTransport("vless-reality", listOf("vless-reality"), emptySet(), sameServer = false))
        assertNull(ColituWarmSpare.spareTransport("vless-reality", listOf("vless-reality"), setOf("vless-reality"), sameServer = false))
        assertNull(ColituWarmSpare.spareTransport("hysteria2", listOf("hysteria2", "unknown"), emptySet(), sameServer = true))
    }

    private fun server(id: String, available: Boolean = true, route: Boolean = false) = ColituServer(
        id = id, displayName = id, countryCode = "DE", city = null, isRecommended = false, isAvailable = available,
        route = if (route) ColituRoute("r", ColituRouteEndpoint("a", "A", "DE", null), ColituRouteEndpoint("b", "B", "FI", null)) else null,
    )

    @Test
    fun spareServerIsTheNextRankedOneThatIsNotPenalized() {
        val ranked = listOf(server("a"), server("b"), server("c"))
        assertEquals("b", ColituWarmSpare.spareServer(ranked, "a", emptySet())?.id)
        assertEquals("c", ColituWarmSpare.spareServer(ranked, "a", setOf("b"))?.id)
        assertEquals("a", ColituWarmSpare.spareServer(ranked, "b", emptySet())?.id)
        assertNull(ColituWarmSpare.spareServer(listOf(server("a"), server("r", route = true), server("x", available = false)), "a", emptySet()))
    }

    // ── Core config ────────────────────────────────────────────────────────

    private fun outbound(protocol: String, address: String) =
        """{"tag":"proxy","protocol":"$protocol","settings":{"address":"$address"}}"""

    private val primary = """
        {"inbounds":[{"tag":"socks","protocol":"socks","port":1080}],
         "outbounds":[${outbound("hysteria", "primary.example.test")},{"tag":"direct","protocol":"freedom"}],
         "dns":{"servers":["https://dns.example.test/dns-query"]},
         "routing":{"domainStrategy":"AsIs","rules":[
           {"type":"field","domain":["example.test"],"outboundTag":"proxy"},
           {"type":"field","inboundTag":["dns-module"],"outboundTag":"proxy"},
           {"type":"field","ip":["10.0.0.0/8"],"outboundTag":"direct"}]}}
    """.trimIndent()

    private val spare = """{"outbounds":[${outbound("vless", "spare.example.test")},{"tag":"direct","protocol":"freedom"}]}"""

    private fun parse(raw: String): JsonObject = JsonParser.parseString(raw).asJsonObject
    private fun rules(raw: String) = parse(raw).getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }

    @Test
    fun attachAddsSpareBalancerAndObservatory() {
        val out = parse(ColituWarmSpare.attach(primary, spare))
        val tags = out.getAsJsonArray("outbounds").map { it.asJsonObject.get("tag").asString }
        assertEquals(listOf("proxy", "warm-spare", "direct"), tags)
        assertEquals("spare.example.test", out.getAsJsonArray("outbounds")[1].asJsonObject.getAsJsonObject("settings").get("address").asString)
        val balancer = out.getAsJsonObject("routing").getAsJsonArray("balancers")[0].asJsonObject
        assertEquals("proxy-auto", balancer.get("tag").asString)
        assertEquals(listOf("proxy"), balancer.getAsJsonArray("selector").map { it.asString })
        assertEquals("warm-spare", balancer.get("fallbackTag").asString)
        // A selector matches by prefix: the spare's tag must not start with the primary's.
        assertFalse(ColituWarmSpare.SPARE_TAG.startsWith(ColituWarmSpare.PRIMARY_TAG))
        val observatory = out.getAsJsonObject("burstObservatory")
        assertEquals(listOf("proxy"), observatory.getAsJsonArray("subjectSelector").map { it.asString })
        val ping = observatory.getAsJsonObject("pingConfig")
        assertEquals(ColituWarmSpare.PROBE_INTERVAL, ping.get("interval").asString)
        assertEquals("10s", ping.get("interval").asString)
        assertEquals(1, ping.get("sampling").asInt)
        assertEquals("http://www.gstatic.com/generate_204", ping.get("destination").asString)
        assertEquals("3s", ping.get("timeout").asString)
        // No direct connectivity check: when it fails Xray records nothing and the switch takes twice as long.
        assertFalse(ping.has("connectivity"))
        // TCP outbounds close a silently dead socket within 10 s, so long-lived connections (DoH) move over.
        val outbounds = out.getAsJsonArray("outbounds").map { it.asJsonObject }
        // The primary here is Hysteria2 (QUIC has its own idle timeout); the spare is TCP.
        assertFalse(outbounds[0].has("streamSettings"))
        assertEquals(10_000, outbounds[1].getAsJsonObject("streamSettings").getAsJsonObject("sockopt").get("tcpUserTimeout").asInt)
    }

    @Test
    fun everyFormerProxyRuleUsesTheBalancerAndACatchAllComesLast() {
        val out = ColituWarmSpare.attach(primary, spare)
        val all = rules(out)
        assertTrue(all.none { it.get("outboundTag")?.asString == "proxy" })
        assertEquals(2, all.count { it.get("balancerTag")?.asString == "proxy-auto" && it.get("ruleTag") == null })
        assertEquals("direct", all.first { it.has("ip") }.get("outboundTag").asString)
        val last = all.last()
        assertEquals("colitu-spare", last.get("ruleTag").asString)
        assertEquals("proxy-auto", last.get("balancerTag").asString)
        // Idempotent: a second pass changes nothing.
        assertEquals(out, ColituWarmSpare.attach(out, spare))
        assertEquals(out, ColituWarmSpare.reroute(out))
    }

    @Test
    fun layersAfterTheSpareAreReroutedAndOnlyModeKeepsItsDirectRuleInFront() {
        val out = ColituWarmSpare.attach(primary, spare)
        val settings = ColituSplitTunnel.Settings(ColituSplitTunnel.Mode.Only, domains = listOf("site.example.test"))
        val split = ColituWarmSpare.reroute(ColituSplitTunnel.configure(out, settings))
        val all = rules(split)
        assertTrue(all.none { it.get("outboundTag")?.asString == "proxy" })
        assertEquals("colitu-spare", all.last().get("ruleTag").asString)
        // Split tunneling's "everything else direct" rule sits right in front of the catch-all.
        assertEquals("direct", all[all.size - 2].get("outboundTag").asString)
        val adBlock = ColituWarmSpare.reroute(ColituAdBlock.apply(out, on = true, servers = listOf("https://dns.example.test/dns-query")))
        // The DNS module's own queries go first to the balancer, then the tunnel's port 53 to dns-out.
        val dnsRule = rules(adBlock).first()
        assertEquals("colitu-spare-dns", dnsRule.get("ruleTag").asString)
        assertEquals(listOf("colitu-adblock-dns"), dnsRule.getAsJsonArray("inboundTag").map { it.asString })
        assertEquals("proxy-auto", dnsRule.get("balancerTag").asString)
        assertEquals("dns-out", rules(adBlock)[1].get("outboundTag").asString)
        assertEquals("colitu-spare", rules(adBlock).last().get("ruleTag").asString)
        // A dns block without a tag gets one, and its rule names it.
        val tagged = parse(ColituWarmSpare.attach(primary, spare))
        assertEquals("colitu-dns", tagged.getAsJsonObject("dns").get("tag").asString)
        assertEquals("colitu-dns", rules(tagged.toString()).first().getAsJsonArray("inboundTag")[0].asString)
        // Switched off: the DNS rule goes with the rest.
        assertTrue(rules(ColituWarmSpare.detach(adBlock)).none { it.get("ruleTag")?.asString == "colitu-spare-dns" })
    }

    @Test
    fun withoutSpareTheConfigIsTodaysSingleOutboundOne() {
        assertEquals(primary, ColituWarmSpare.attach(primary, null))
        assertEquals(primary, ColituWarmSpare.reroute(primary))
        // A spare profile without a `proxy` outbound gives no spare either.
        assertEquals(primary, ColituWarmSpare.attach(primary, """{"outbounds":[{"tag":"direct","protocol":"freedom"}]}"""))
        // Switched off later: detach brings back exactly the old rules.
        val detached = parse(ColituWarmSpare.detach(ColituWarmSpare.attach(primary, spare)))
        assertEquals(parse(primary).getAsJsonObject("routing").getAsJsonArray("rules"), detached.getAsJsonObject("routing").getAsJsonArray("rules"))
        assertFalse(detached.has("burstObservatory"))
        assertFalse(detached.getAsJsonObject("routing").has("balancers"))
        assertEquals(2, detached.getAsJsonArray("outbounds").size())
        assertFalse(ColituWarmSpare.hasSpare(detached.toString()))
    }

    @Test
    fun trafficCheckInboundGoesStraightToThePrimaryFirstOfAll() {
        val verify = com.v2ray.ang.colitu.api.LocalProxy(23456, "u1", "p1")
        val withSpare = ColituWarmSpare.attach(primary, spare)
        val adBlocked = ColituAdBlock.apply(withSpare, on = true, servers = listOf("https://dns.example.test/dns-query"))
        val out = XrayMobileAdapter.withVerifyInbound(ColituWarmSpare.reroute(adBlocked), verify)
        val inbound = parse(out).getAsJsonArray("inbounds").map { it.asJsonObject }.single { it.get("tag").asString == "colitu-verify" }
        assertEquals("127.0.0.1", inbound.get("listen").asString)
        assertEquals(23456, inbound.get("port").asInt)
        assertEquals("http", inbound.get("protocol").asString)
        assertEquals("u1", inbound.getAsJsonObject("settings").getAsJsonArray("accounts")[0].asJsonObject.get("user").asString)
        val first = rules(out).first()
        assertEquals(listOf("colitu-verify"), first.getAsJsonArray("inboundTag").map { it.asString })
        assertEquals("proxy", first.get("outboundTag").asString)
        assertFalse(first.has("balancerTag"))
        // Later passes keep it on the primary, and a new session replaces it instead of adding one.
        val again = XrayMobileAdapter.withVerifyInbound(ColituWarmSpare.reroute(out), verify.copy(port = 34567))
        assertEquals(1, rules(again).count { it.get("ruleTag")?.asString == "colitu-verify" })
        assertEquals("proxy", rules(ColituWarmSpare.reroute(again)).first().get("outboundTag").asString)
        assertEquals(1, parse(again).getAsJsonArray("inbounds").count { it.asJsonObject.get("tag").asString == "colitu-verify" })
        // The SOCKS renewal at every start keeps the check inbound.
        val renewed = XrayMobileAdapter.withLocalProxy(again, com.v2ray.ang.colitu.api.LocalProxy(40000, "a", "b"))
        assertEquals(1, parse(renewed).getAsJsonArray("inbounds").count { it.asJsonObject.get("tag").asString == "colitu-verify" })
    }

    @Test
    fun rendersARealPanelProfileAsSpare() {
        val primaryConfig = XrayMobileAdapter.render(ColituWarmSpareFixtures.envelope("vless-reality"), ColituWarmSpareFixtures.now)
        val spareConfig = XrayMobileAdapter.render(ColituWarmSpareFixtures.envelope("trojan"), ColituWarmSpareFixtures.now)
        val out = parse(ColituWarmSpare.attach(requireNotNull(primaryConfig.rawConfig), spareConfig.rawConfig))
        val outbounds = out.getAsJsonArray("outbounds").map { it.asJsonObject }
        assertEquals("vless", outbounds.first { it.get("tag").asString == "proxy" }.get("protocol").asString)
        assertEquals("trojan", outbounds.first { it.get("tag").asString == "warm-spare" }.get("protocol").asString)
        assertEquals("proxy-auto", rules(out.toString()).last().get("balancerTag").asString)
    }
}

/** Minimal panel envelopes (no real hosts) for the spare tests. */
internal object ColituWarmSpareFixtures {
    val now: java.time.Instant = java.time.Instant.parse("2026-09-11T10:00:00Z")

    fun envelope(protocol: String): JsonObject {
        val (transport, security, credentials) = when (protocol) {
            "trojan" -> Triple(
                """{"type":"tcp"}""",
                """{"type":"tls","server_name":"sni.example.test"}""",
                """{"password":"secret"}""",
            )
            else -> Triple(
                """{"type":"tcp"}""",
                """{"type":"reality","server_name":"sni.example.test","public_key":"XfVFQMun8Tfi13R338PUHowzhZVKtGQYGxOOvCsv7ik","short_id":"ab"}""",
                """{"uuid":"00000000-0000-4000-8000-000000000000"}""",
            )
        }
        return JsonParser.parseString(
            """
            {"revision":42,"expires_at":"2026-09-12T10:00:00Z","offline_grace_until":"2026-09-13T10:00:00Z",
             "server":{"id":"node-1"},
             "profile":{"format":"xray-mobile-v1","payload":{"schema_version":1,"protocol":"$protocol",
               "endpoint":{"host":"node.example.test","port":443},
               "credentials":$credentials,"transport":$transport,"security":$security}}}
            """.trimIndent(),
        ).asJsonObject
    }
}
