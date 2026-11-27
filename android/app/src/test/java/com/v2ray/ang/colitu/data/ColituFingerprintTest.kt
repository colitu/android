package com.v2ray.ang.colitu.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituFingerprintTest {
    private val fixtures: JsonObject = JsonParser.parseString(
        javaClass.classLoader!!.getResourceAsStream("fp-fixtures.json")!!.readBytes().toString(Charsets.UTF_8),
    ).asJsonObject
    private val now = 1_800_000_000_000L
    private val node = "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b"

    @Test
    fun fnv1aFixtures() {
        val cases = fixtures.getAsJsonArray("fnv1a32")
        assertTrue(cases.size() > 0)
        for (c in cases) {
            val o = c.asJsonObject
            assertEquals(o.get("input").asString, o.get("hash").asLong, ColituFingerprint.fnv1a32(o.get("input").asString))
        }
    }

    @Test
    fun chooseFixtures() {
        val cases = fixtures.getAsJsonArray("choose")
        assertTrue(cases.size() > 0)
        for (c in cases) {
            val o = c.asJsonObject
            val policy = requireNotNull(ColituFingerprint.parsePolicy(o.getAsJsonObject("policy")))
            val state = o.getAsJsonObject("state").entrySet().associate { (name, v) ->
                val s = v.asJsonObject
                name to if (s.get("ok")?.asBoolean == true) ColituFingerprint.Entry(okAt = now - 1000)
                else ColituFingerprint.Entry(failAt = now - 1000, fails = 1)
            }
            // parsePolicy drops unknown names, as the spec says; the pick must still match the fixture.
            assertEquals(
                o.get("name").asString, o.get("expect").asString,
                ColituFingerprint.choose(policy, o.get("device_id").asString, o.get("network").asString, state, now),
            )
        }
    }

    @Test
    fun retryFixtures() {
        val cases = fixtures.getAsJsonArray("retry")
        assertTrue(cases.size() > 0)
        for (c in cases) {
            val o = c.asJsonObject
            val probe = o.get("probe")?.takeIf { !it.isJsonNull }?.asString
            assertEquals(
                o.get("name").asString, o.get("expect_retry").asBoolean,
                ColituFingerprint.shouldRetry(o.get("transport").asString, o.get("tcp_ok").asBoolean, probe, o.get("retries_used").asInt),
            )
        }
    }

    @Test
    fun failureIsRememberedFor24HoursAndClearedBySuccess() {
        val policy = ColituFingerprint.Policy(listOf("chrome", "edge"), emptySet(), 1)
        val state = ColituFingerprint.State()
        state.markFailed("RU-AS1", "chrome", now)
        assertEquals("edge", ColituFingerprint.choose(policy, "d", "RU-AS1", state.entries("RU-AS1"), now + 1000))
        // Older than 24 h: chrome is tried again.
        assertEquals("chrome", ColituFingerprint.choose(policy, "d", "RU-AS1", state.entries("RU-AS1"), now + ColituFingerprint.FAIL_MEMORY_MS + 1))
        state.markOk("RU-AS1", "chrome", now + 10)
        assertEquals("chrome", ColituFingerprint.choose(policy, "d", "RU-AS1", state.entries("RU-AS1"), now + 1000))
        // Another network is untouched.
        assertTrue(state.entries("RU-AS2").isEmpty())
    }

    @Test
    fun stateRoundTripsAndStaysBounded() {
        val state = ColituFingerprint.State()
        for (n in 1..12) state.markFailed("net$n", "chrome", now + n)
        for (name in listOf("chrome", "edge", "safari", "ios", "android", "firefox", "360", "qq", "random", "randomized")) state.markOk("net12", name, now)
        state.markFailed("net12", "firefox", now + 50)
        val back = ColituFingerprint.State.fromJson(state.toJson())
        assertEquals(state.toJson(), back.toJson())
        val raw = JsonParser.parseString(state.toJson()).asJsonObject
        assertEquals(ColituFingerprint.MAX_NETWORKS, raw.size())
        assertTrue(raw.getAsJsonObject("net12").size() <= ColituFingerprint.MAX_NAMES)
        assertTrue(back.entries("net1").isEmpty())
        assertEquals(1, back.entries("net12")["firefox"]!!.fails)
        assertTrue(ColituFingerprint.State.fromJson("garbage").entries("x").isEmpty())
        assertNull(ColituFingerprint.State.fromJson(null).entries("x")["chrome"])
    }

    @Test
    fun policyParse() {
        assertNull(ColituFingerprint.parsePolicy(null))
        val p = ColituFingerprint.parsePolicy(
            JsonParser.parseString("""{"order":["chrome","opera","Edge"],"blocked":["firefox"],"scope":"network"}""").asJsonObject,
        )!!
        assertEquals(listOf("chrome", "edge"), p.order)
        assertEquals(setOf("firefox"), p.blocked)
        assertEquals(ColituFingerprint.DEFAULT_SPREAD, p.spread)
        assertEquals("network", p.scope)
        assertEquals(p, ColituFingerprint.parsePolicy(p.toJson()))
    }

    @Test
    fun connectEventCarriesFingerprintOnlyWithFpEventsAndTlsTransports() {
        val withFlag = ColituAcEvents.connect(node, "vless-reality", true, null, 5, firstTry = true, auto = true, fingerprint = "edge", fpEvents = true)!!
        assertEquals("edge", withFlag.get("fingerprint").asString)
        val noFlag = ColituAcEvents.connect(node, "vless-reality", true, null, 5, firstTry = true, auto = true, fingerprint = "edge", fpEvents = false)!!
        assertFalse(noFlag.has("fingerprint"))
        val hysteria = ColituAcEvents.connect(node, "hysteria2", true, null, 5, firstTry = true, auto = true, fingerprint = "edge", fpEvents = true)!!
        assertFalse(hysteria.has("fingerprint"))
        val none = ColituAcEvents.connect(node, "trojan", true, null, 5, firstTry = true, auto = true, fingerprint = null, fpEvents = true)!!
        assertFalse(none.has("fingerprint"))
        // Remote flag off by default (no MMKV on the JVM).
        assertFalse(ColituFeatures.fpEvents(remote = false))
        assertTrue(ColituFeatures.fpEvents(remote = true))
    }

    @Test
    fun serverListParsesPolicyAndFeatureFlag() {
        val list = ColituServerListResponse.fromJson(
            JsonParser.parseString(
                """{"servers":[],"fingerprint_policy":{"order":["chrome","edge"],"blocked":[],"spread":2,"scope":"default"},
                    "features":{"fp_events":true}}""",
            ).asJsonObject,
        )
        assertNotNull(list.fingerprintPolicy)
        assertEquals(2, list.fingerprintPolicy!!.spread)
        assertEquals(true, list.features?.get("fp_events"))
        // An old panel sends neither: nothing changes.
        val old = ColituServerListResponse.fromJson(JsonParser.parseString("""{"servers":[]}""").asJsonObject)
        assertNull(old.fingerprintPolicy)
        assertNull(old.features)
    }

    @Test
    fun adapterRendersTheChosenFingerprintInTheFourTlsTransportsOnly() {
        val instant = Instant.parse("2026-09-11T10:00:00Z")
        fun fp(raw: String): String? {
            val stream = JsonParser.parseString(raw).asJsonObject.getAsJsonArray("outbounds")[0].asJsonObject.getAsJsonObject("streamSettings")
                ?: return null
            return (stream.getAsJsonObject("realitySettings") ?: stream.getAsJsonObject("tlsSettings"))?.get("fingerprint")?.asString
        }
        fun render(protocol: String, transport: String, security: String, creds: String = """{"uuid":"11111111-1111-4111-8111-111111111111"}""", host: String = "vpn.example.test"): ColituVpnConfig {
            val env = JsonParser.parseString(
                """{"revision":1,"expires_at":"2026-09-12T10:00:00Z","offline_grace_until":"2026-09-13T10:00:00Z","server":{"id":"s"},
                    "profile":{"format":"xray-mobile-v1","payload":{"schema_version":1,"protocol":"$protocol",
                    "endpoint":{"host":"$host","port":443},"credentials":$creds,"transport":$transport,"security":$security}}}""",
            ).asJsonObject
            return XrayMobileAdapter.render(env, instant)
        }
        val reality = render("vless-reality", """{"type":"tcp"}""", """{"type":"reality","server_name":"a.test","public_key":"p","short_id":"ab","fingerprint":"chrome"}""")
        val xhttp = render("vless-xhttp", """{"type":"xhttp","path":"/x"}""", """{"type":"reality","server_name":"a.test","public_key":"p","short_id":"ab"}""")
        val trojan = render("trojan", """{"type":"tcp"}""", """{"type":"tls","server_name":"a.test"}""", """{"password":"pw"}""")
        val ws = render("vless-ws", """{"type":"ws","path":"/w"}""", """{"type":"tls","server_name":"a.test","fingerprint":"firefox"}""")
        // Today's fixed values stay without a policy.
        assertEquals("chrome", fp(reality.rawConfig!!))
        assertEquals("chrome", fp(trojan.rawConfig!!))
        assertEquals("firefox", fp(ws.rawConfig!!))
        for (c in listOf(reality, xhttp, trojan, ws)) {
            val out = XrayMobileAdapter.withFingerprint(c.rawConfig!!, c.protocolType, "edge")
            assertEquals(c.protocolType, "edge", fp(out))
        }
        val hysteria = render("hysteria2", """{"type":"hysteria"}""", """{"type":"tls","server_name":"a.test"}""", """{"password":"pw"}""")
        assertEquals(hysteria.rawConfig, XrayMobileAdapter.withFingerprint(hysteria.rawConfig!!, "hysteria2", "edge"))
        val ss = render("shadowsocks", """{"type":"tcp"}""", """{"type":"none"}""", """{"method":"2022-blake3-aes-128-gcm","password":"AAAAAAAAAAAAAAAAAAAAAA=="}""")
        assertEquals(ss.rawConfig, XrayMobileAdapter.withFingerprint(ss.rawConfig!!, "shadowsocks", "edge"))
        // An unknown name never reaches the config.
        assertEquals(reality.rawConfig, XrayMobileAdapter.withFingerprint(reality.rawConfig!!, "vless-reality", "opera"))
    }
}
