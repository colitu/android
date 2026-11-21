package com.v2ray.ang.colitu.api

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIMatcher
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.StandardConstants
import kotlin.concurrent.thread

class ColituRelayTest {
    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/endpoints/$name.signed.json")!!.readBytes().toString(Charsets.UTF_8)

    private class MemoryStore : EndpointStore {
        val map = HashMap<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
    }

    private val api = listOf("https://api.colitu.com/api/v1", "https://mirror.example.test/capi/v1")
    private val lists = listOf("https://colitu.com/downloads/endpoints.json")
    private val ru = relay("ru1", region = "RU")
    private val bgs = relay("bgs1", region = "RU", whitelist = true)
    private val de = relay("de1", region = "DE")

    private fun relay(id: String, region: String? = null, whitelist: Boolean = false, host: String = "203.0.113.10", port: Int = 8444) =
        ApiRelay(id, host, port, "front.example.test", "api.colitu.com", "/api/v1", region, whitelist)

    private fun state(relays: List<ApiRelay> = listOf(ru, bgs, de), store: MemoryStore = MemoryStore()) =
        ColituEndpointState(store, api, lists, relays)

    private fun relayJson(fields: String) = JsonParser.parseString("{$fields}").asJsonObject

    // Parser

    @Test fun aWellFormedRelayIsAccepted() {
        val r = ColituEndpointList.parseRelay(relayJson(
            "\"id\":\"ru1\",\"addr\":\"203.0.113.10:8444\",\"sni\":\"Front.Example.test\",\"verify\":\"api.colitu.com\"," +
                "\"prefix\":\"/api/v1/\",\"region\":\"ru\",\"whitelist\":true,\"future\":{\"x\":1}"))
        assertEquals(ApiRelay("ru1", "203.0.113.10", 8444, "front.example.test", "api.colitu.com", "/api/v1", "RU", true), r)
        // A host name is accepted too; whitelist defaults to false.
        val h = ColituEndpointList.parseRelay(relayJson(
            "\"id\":\"bgs1\",\"addr\":\"cdn.example.test:443\",\"sni\":\"a.example.test\",\"verify\":\"api.colitu.com\",\"prefix\":\"/api/v1\""))
        assertEquals("cdn.example.test", h?.host)
        assertFalse(h!!.whitelist)
    }

    @Test fun badRelaysAreRefused() {
        val ok = "\"id\":\"r\",\"sni\":\"a.example.test\",\"verify\":\"api.colitu.com\",\"prefix\":\"/api/v1\""
        for (addr in listOf("203.0.113.10", "203.0.113.10:0", "203.0.113.10:65536", "203.0.113.10:x", "[2001:db8::1]:443", ":443", "300.1.1.1:443", "bad_host:443")) {
            assertNull(addr, ColituEndpointList.parseRelay(relayJson("$ok,\"addr\":\"$addr\"")))
        }
        val base = "\"id\":\"r\",\"addr\":\"203.0.113.10:443\""
        // verify must be exactly api.colitu.com: the list cannot weaken the certificate check.
        for (verify in listOf("evil.example.test", "API.colitu.com", "*.colitu.com", "")) {
            assertNull(verify, ColituEndpointList.parseRelay(relayJson("$base,\"sni\":\"a.example.test\",\"verify\":\"$verify\",\"prefix\":\"/api/v1\"")))
        }
        assertNull(ColituEndpointList.parseRelay(relayJson("$base,\"sni\":\"a.example.test\",\"prefix\":\"/api/v1\"")))
        assertNull(ColituEndpointList.parseRelay(relayJson("$base,\"sni\":\"203.0.113.9\",\"verify\":\"api.colitu.com\",\"prefix\":\"/api/v1\"")))
        assertNull(ColituEndpointList.parseRelay(relayJson("$base,\"sni\":\"localhost\",\"verify\":\"api.colitu.com\",\"prefix\":\"/api/v1\"")))
        for (prefix in listOf("api/v1", "/api/../x", "/api?x", "//evil.example")) {
            assertNull(prefix, ColituEndpointList.parseRelay(relayJson("$base,\"sni\":\"a.example.test\",\"verify\":\"api.colitu.com\",\"prefix\":\"$prefix\"")))
        }
        assertNull(ColituEndpointList.parseRelay(relayJson("\"id\":\"Bad Id\",\"addr\":\"203.0.113.10:443\",\"sni\":\"a.example.test\",\"verify\":\"api.colitu.com\",\"prefix\":\"/api/v1\"")))
    }

    @Test fun aBadEntryIsSkippedNotTheList() {
        val payload = "{\"schema\":1,\"version\":5,\"api\":[\"https://a.example\"],\"web\":[\"https://a.example\"]," +
            "\"lists\":[\"https://a.example/x\"],\"unknown\":[1,2],\"relays\":[" +
            "{\"id\":\"ru1\",\"addr\":\"203.0.113.10:8444\",\"sni\":\"a.example.test\",\"verify\":\"api.colitu.com\",\"prefix\":\"/api/v1\"}," +
            "{\"id\":\"bad\",\"addr\":\"203.0.113.11:8444\",\"sni\":\"a.example.test\",\"verify\":\"evil.example.test\",\"prefix\":\"/api/v1\"}," +
            "\"not an object\"," +
            "{\"id\":\"ru1\",\"addr\":\"203.0.113.12:8444\",\"sni\":\"b.example.test\",\"verify\":\"api.colitu.com\",\"prefix\":\"/api/v1\"}]}"
        val list = ColituEndpointList.parsePayload(payload)
        assertNotNull(list)
        assertEquals(listOf("ru1"), list!!.relays!!.map { it.id })
        assertEquals("203.0.113.10", list.relays!!.single().host)
        // No key: null (the built-in relays apply); a non-array: none.
        assertNull(ColituEndpointList.parsePayload(payload.replace(Regex(",\"relays\":\\[.*]}$"), "}"))!!.relays)
        assertEquals(emptyList<ApiRelay>(), ColituEndpointList.parsePayload(payload.replace(Regex(",\"relays\":\\[.*]}$"), ",\"relays\":7}"))!!.relays)
    }

    @Test fun relaysFromTheBuildAreParsed() {
        val r = ColituEndpointList.parseBuildRelays(" ru1|203.0.113.10:8444|front.example.test|RU|0 ; bgs1|cdn.example.test:443|wl.example.test|RU|1;broken|x;")
        assertEquals(listOf("ru1", "bgs1"), r.map { it.id })
        assertTrue(r[1].whitelist)
        assertEquals("/api/v1", r[0].prefix)
        assertEquals(emptyList<ApiRelay>(), ColituEndpointList.parseBuildRelays(""))
    }

    // Order

    @Test fun orderPutsRelaysWhereTheNetworkNeedsThem() {
        val s = state()
        assertEquals(api + listOf("relay:de1", "relay:ru1", "relay:bgs1"), s.bases(RouteContext.NONE))
        assertEquals(listOf(api[0], "relay:ru1", "relay:bgs1", api[1], "relay:de1"), s.bases(RouteContext(country = "ru")))
        assertEquals(listOf("relay:bgs1", "relay:ru1") + api + "relay:de1", s.bases(RouteContext(country = "RU", whitelist = true)))
        // The last working path still comes first.
        s.markWorked("relay:ru1")
        assertEquals(listOf("relay:ru1") + api + listOf("relay:de1", "relay:bgs1"), s.bases(RouteContext.NONE))
        assertEquals("relay:ru1", ColituEndpoints.apiPathOf(s))
        s.markWorked(api[1])
        assertEquals("mirror", ColituEndpoints.apiPathOf(s))
        s.markWorked(api[0])
        assertEquals("primary", ColituEndpoints.apiPathOf(s))
    }

    @Test fun relaysOfTheSignedListReplaceTheBuiltInOnes() {
        val s = state()
        assertTrue(s.accept(fixture("valid")))
        // The fixture has no relays key: the built-in relays stay.
        assertEquals(listOf("ru1", "bgs1", "de1"), s.relays().map { it.id })
    }

    @Test fun aRelayRequestTargetsTheApiHostAndCarriesTheRelay() {
        val s = state()
        val template = Request.Builder().url("https://api.colitu.com/api/v1/servers?x=1").post(okhttp3.RequestBody.create(null, "{}")).build()
        val viaRelay = ColituEndpoints.requestFor(template, "relay:ru1", "/servers?x=1", target = s)
        assertEquals("https://api.colitu.com/api/v1/servers?x=1", viaRelay.url.toString())
        assertEquals(ru, viaRelay.tag(ApiRelay::class.java))
        assertEquals("POST", viaRelay.method)
        val health = ColituEndpoints.requestFor(template, "relay:ru1", "/health", originPath = true, target = s)
        assertEquals("https://api.colitu.com/health", health.url.toString())
        val mirror = ColituEndpoints.requestFor(viaRelay, api[1], "/health", originPath = true, target = s)
        assertEquals("https://mirror.example.test/health", mirror.url.toString())
        assertNull(mirror.tag(ApiRelay::class.java))
        try {
            ColituEndpoints.requestFor(template, "relay:gone", "/x", target = s)
            fail()
        } catch (e: IOException) {
            assertTrue(ColituEndpoints.isConnectLevel(e))
        }
    }

    @Test fun failoverWalksIntoTheRelays() {
        val s = state()
        val tried = ArrayList<String>()
        val (worked, _) = ColituEndpoints.failover(s.bases(RouteContext(country = "RU")), "GET") { base ->
            tried += base
            if (base != "relay:bgs1") throw java.net.ConnectException("refused") else "ok"
        }
        assertEquals("relay:bgs1", worked)
        assertEquals(listOf(api[0], "relay:ru1", "relay:bgs1"), tried)
    }

    @Test fun theListIsRefreshedThroughARelay() {
        val s = state()
        val asked = ArrayList<String>()
        val ok = ColituEndpoints.refresh({ req ->
            val relay = req.tag(ApiRelay::class.java)
            asked += relay?.base ?: req.url.toString()
            if (relay?.id == "bgs1") fixture("valid") else null
        }, s)
        assertTrue(ok)
        assertEquals(lists + listOf("relay:ru1", "relay:bgs1"), asked)
    }

    // Self-heal

    @Test fun healerWaitsTenMinutesOffTheFirstBase() {
        val h = ApiPathHealer(intervalMs = 600_000)
        assertFalse(h.claim(0, offFirst = false))
        assertFalse(h.claim(1_000, offFirst = true)) // the period starts now
        assertFalse(h.claim(600_999, offFirst = true))
        assertTrue(h.claim(601_000, offFirst = true))
        assertFalse(h.claim(601_001, offFirst = true))
        assertTrue(h.claim(1_201_000, offFirst = true))
        // Back on the first base: the timer stops.
        assertFalse(h.claim(1_300_000, offFirst = false))
        assertFalse(h.claim(1_400_000, offFirst = true))
    }

    @Test fun healGoesBackToThePrimaryOnceItAnswers() {
        val s = state()
        s.markWorked("relay:ru1")
        val probed = ArrayList<String>()
        var primaryUp = false
        val probe: (Request) -> Boolean = { req ->
            probed += req.tag(ApiRelay::class.java)?.base ?: req.url.toString()
            req.url.toString() == "https://api.colitu.com/health" && req.tag(ApiRelay::class.java) == null && primaryUp
        }
        // Still down: only the bases before the current one are probed; nothing changes.
        assertNull(ColituEndpoints.heal(s, RouteContext.NONE, all = false, probe = probe))
        assertEquals(listOf("https://api.colitu.com/health", "https://mirror.example.test/health", "relay:de1"), probed)
        assertEquals("relay:ru1", s.lastWorking())
        primaryUp = true
        probed.clear()
        assertEquals(api[0], ColituEndpoints.heal(s, RouteContext.NONE, all = false, probe = probe))
        assertEquals(listOf("https://api.colitu.com/health"), probed)
        assertEquals(api[0], s.lastWorking())
    }

    @Test fun networkChangeChoosesThePathFromTheStart() {
        val s = state()
        s.markWorked(api[0])
        // On the new network only the whitelist relay answers.
        val back = ColituEndpoints.heal(s, RouteContext(country = "RU", whitelist = true), all = true) { req ->
            req.tag(ApiRelay::class.java)?.id == "bgs1"
        }
        assertEquals("relay:bgs1", back)
        assertEquals("relay:bgs1", s.lastWorking())
    }

    // Relay dial: a local TLS server

    private class FakeRelay(cert: HeldCertificate, root: HeldCertificate) : AutoCloseable {
        @Volatile var sni: String? = null
        @Volatile var requestHead: String? = null
        private val server: SSLServerSocket
        val port: Int

        init {
            val certs = HandshakeCertificates.Builder().heldCertificate(cert, root.certificate).build()
            server = certs.sslContext().serverSocketFactory.createServerSocket(0, 5, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            server.sslParameters = server.sslParameters.apply {
                sniMatchers = listOf(object : SNIMatcher(StandardConstants.SNI_HOST_NAME) {
                    override fun matches(serverName: SNIServerName): Boolean {
                        sni = SNIHostName(serverName.encoded).asciiName
                        return true
                    }
                })
            }
            port = server.localPort
            thread(isDaemon = true) {
                runCatching {
                    while (true) {
                        val socket = server.accept()
                        runCatching {
                            socket.soTimeout = 5_000
                            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                            val head = StringBuilder()
                            while (true) {
                                val line = input.readLine() ?: break
                                if (line.isEmpty()) break
                                head.append(line).append('\n')
                            }
                            requestHead = head.toString()
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                            socket.getOutputStream().flush()
                        }
                        socket.close()
                    }
                }
            }
        }

        override fun close() = server.close()
    }

    private val root = HeldCertificate.Builder().certificateAuthority(0).commonName("Test Root").build()
    private fun leaf(name: String) = HeldCertificate.Builder().addSubjectAlternativeName(name).signedBy(root).build()
    private fun baseClient(): OkHttpClient {
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build()
        return OkHttpClient.Builder()
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
    private val loopback: (String) -> List<InetAddress> = { listOf(InetAddress.getByName("127.0.0.1")) }

    @Test fun relayDialSendsTheSniAndVerifiesTheApiName() {
        FakeRelay(leaf("api.colitu.com"), root).use { server ->
            val r = ApiRelay("t1", "127.0.0.1", server.port, "front.example.test", "api.colitu.com", "/api/v1", "RU", false)
            val request = Request.Builder().url("https://api.colitu.com/api/v1/ping").build()
            ColituRelayTransport.client(baseClient(), r, loopback).newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("ok", response.body.string())
            }
            assertEquals("front.example.test", server.sni)
            val head = server.requestHead!!
            assertTrue(head, head.startsWith("GET /api/v1/ping HTTP/1.1\n"))
            assertTrue(head, head.lines().any { it.equals("Host: api.colitu.com", ignoreCase = true) })
        }
    }

    @Test fun aCertificateForAnotherNameIsRefused() {
        // Valid chain, but for the SNI's name, not for api.colitu.com.
        FakeRelay(leaf("front.example.test"), root).use { server ->
            val r = ApiRelay("t2", "127.0.0.1", server.port, "front.example.test", "api.colitu.com", "/api/v1", null, false)
            val request = Request.Builder().url("https://api.colitu.com/api/v1/ping").build()
            try {
                ColituRelayTransport.client(baseClient(), r, loopback).newCall(request).execute().close()
                fail("a certificate without api.colitu.com must be refused")
            } catch (e: IOException) {
                assertTrue(e.toString(), e is javax.net.ssl.SSLPeerUnverifiedException)
                assertTrue(ColituEndpoints.isConnectLevel(e))
            }
            // The handshake ends on the server side before the client checks the name: it may see the
            // connection close (an empty head), but never a request line.
            assertTrue("no request may reach a wrong certificate", server.requestHead.isNullOrEmpty())
        }
    }

    @Test fun anUntrustedChainIsRefused() {
        val otherRoot = HeldCertificate.Builder().certificateAuthority(0).commonName("Other Root").build()
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("api.colitu.com").signedBy(otherRoot).build()
        FakeRelay(cert, otherRoot).use { server ->
            val r = ApiRelay("t3", "127.0.0.1", server.port, "front.example.test", "api.colitu.com", "/api/v1", null, false)
            try {
                ColituRelayTransport.client(baseClient(), r, loopback)
                    .newCall(Request.Builder().url("https://api.colitu.com/api/v1/ping").build()).execute().close()
                fail()
            } catch (e: IOException) {
                assertTrue(e.toString(), e is javax.net.ssl.SSLHandshakeException)
            }
        }
    }
}
