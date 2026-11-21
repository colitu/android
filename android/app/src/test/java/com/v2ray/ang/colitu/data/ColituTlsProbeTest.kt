package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import java.io.EOFException
import java.net.ConnectException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituTlsProbeTest {
    private val node = "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b"
    private val target = ColituTlsProbe.Target("de6.example.com", 4443, "www.cloudflare.com")

    private fun classify(e: Throwable) = ColituTlsProbe.classifyHandshakeError(e)

    @Test
    fun resetsAndEofAreTlsRst() {
        assertEquals(ColituTlsProbe.Result.TlsRst, classify(SocketException("Connection reset")))
        assertEquals(ColituTlsProbe.Result.TlsRst, classify(SSLException("Read error: ssl=0x1: I/O error during system call, Connection reset by peer")))
        assertEquals(ColituTlsProbe.Result.TlsRst, classify(SSLHandshakeException("Connection closed by peer")))
        assertEquals(ColituTlsProbe.Result.TlsRst, classify(SSLHandshakeException("Handshake failed").apply { initCause(EOFException()) }))
        assertEquals(ColituTlsProbe.Result.TlsRst, classify(SSLException("SSL handshake terminated")))
        assertEquals(ColituTlsProbe.Result.TlsRst, classify(EOFException("Unexpected end of stream")))
    }

    @Test
    fun readTimeoutIsTimeoutEvenInsideASslException() {
        assertEquals(ColituTlsProbe.Result.Timeout, classify(SocketTimeoutException("Read timed out")))
        assertEquals(ColituTlsProbe.Result.Timeout, classify(SSLException("Read error").apply { initCause(SocketTimeoutException("timeout")) }))
    }

    @Test
    fun tlsFailuresAfterServerBytesAreOk() {
        assertEquals(ColituTlsProbe.Result.Ok, classify(SSLHandshakeException("Chain validation failed")))
        assertEquals(ColituTlsProbe.Result.Ok, classify(SSLPeerUnverifiedException("Hostname not verified")))
        assertEquals(ColituTlsProbe.Result.Ok, classify(java.security.cert.CertificateException("expired")))
        assertEquals(ColituTlsProbe.Result.Ok, classify(SSLException("Fatal alert received: handshake_failure")))
    }

    @Test
    fun unknownErrorsNeverCountAsAReset() {
        assertEquals(ColituTlsProbe.Result.Timeout, classify(IllegalStateException("boom")))
    }

    private class FakeIo(val connect: () -> Socket, val handshake: () -> Unit) : ColituTlsProbe.Io {
        override fun connect(target: ColituTlsProbe.Target, timeoutMs: Int): Socket = connect()
        override fun handshake(socket: Socket, target: ColituTlsProbe.Target, readTimeoutMs: Int) = handshake()
    }

    @Test
    fun probeMapsTheTwoSteps() = runBlocking {
        val up = { Socket() }
        assertEquals(ColituTlsProbe.Result.Ok, ColituTlsProbe.probe(target, FakeIo(up) {}).result)
        assertEquals(ColituTlsProbe.Result.TlsRst, ColituTlsProbe.probe(target, FakeIo(up) { throw SocketException("Connection reset") }).result)
        assertEquals(ColituTlsProbe.Result.Timeout, ColituTlsProbe.probe(target, FakeIo(up) { throw SocketTimeoutException() }).result)
        assertEquals(ColituTlsProbe.Result.TcpFail, ColituTlsProbe.probe(target, FakeIo({ throw ConnectException("refused") }) {}).result)
        assertEquals(ColituTlsProbe.Result.TcpFail, ColituTlsProbe.probe(target, FakeIo({ throw SocketTimeoutException() }) {}).result)
    }

    @Test
    fun localAddressesAreNeverProbed() = runBlocking {
        val io = FakeIo({ error("must not connect") }) {}
        assertEquals(ColituTlsProbe.Result.TcpFail, ColituTlsProbe.probe(ColituTlsProbe.Target("192.168.1.1", 443, "x"), io).result)
        assertEquals(ColituTlsProbe.Result.TcpFail, ColituTlsProbe.probe(ColituTlsProbe.Target("localhost", 443, "x"), io).result)
        assertFalse(ColituTlsProbe.hostAllowed("de6.example.com", 0))
        assertTrue(ColituTlsProbe.hostAllowed("de6.example.com", 4443))
    }

    @Test
    fun probeAllRunsTargetsTogether() = runBlocking {
        val io = FakeIo({ Socket() }) {}
        val outcomes = ColituTlsProbe.probeAll(listOf(target, target.copy(port = 443)), io, 8_000)
        assertEquals(listOf(4443, 443), outcomes.map { it.target.port })
    }

    @Test
    fun eventShapeFollowsTheFixture() {
        val cases = fixture().getAsJsonArray("events")
        assertTrue(cases.size() >= 4)
        cases.forEach { c ->
            val o = c.asJsonObject
            val result = ColituTlsProbe.Result.of(o.get("tls").asString)!!
            val event = ColituAcEvents.tlsProbe(node, "vless-xhttp", result, 123, o.get("port").asInt, o.get("measure").asBoolean, enabled = true)!!
            val expect = o.getAsJsonObject("expect")
            expect.entrySet().forEach { (k, v) -> assertEquals("${o.get("name").asString}: $k", v, event.get(k)) }
            assertEquals(node, event.get("node_id").asString)
            assertEquals("vless-xhttp", event.get("protocol").asString)
            assertEquals(123, event.get("ms").asInt)
        }
        assertNull(ColituAcEvents.tlsProbe(null, "vless-xhttp", ColituTlsProbe.Result.Ok, 1, 443, true, enabled = true))
        assertNull(ColituAcEvents.tlsProbe(node, "nope", ColituTlsProbe.Result.Ok, 1, 443, true, enabled = true))
    }

    @Test
    fun noPanelFlagMeansNoTlsProbeEvents() {
        // An older panel rejects the whole report on the unknown fields: the flag gates every TLS probe event.
        ColituTlsProbe.Result.entries.forEach { r ->
            assertNull(ColituAcEvents.tlsProbe(node, "trojan", r, 5, 443, measure = false, enabled = false))
            assertNull(ColituAcEvents.tlsProbe(node, "trojan", r, 5, 443, measure = true, enabled = false))
        }
        assertFalse(ColituFeatures.tlsProbeEvents(remote = false))
        assertTrue(ColituFeatures.tlsProbeEvents(remote = true))
    }

    @Test
    fun queueKeepsOneTlsProbePerPortAndPurpose() {
        val q = ColituAcEvents.Queue()
        fun add(e: com.google.gson.JsonObject?, at: Long) = q.add(e!!, "t", "wifi", at)
        add(ColituAcEvents.probe(node, 20), 1)
        add(ColituAcEvents.tlsProbe(node, "vless-xhttp", ColituTlsProbe.Result.TlsRst, 5, 443, false, enabled = true), 2)
        add(ColituAcEvents.tlsProbe(node, "trojan", ColituTlsProbe.Result.TlsRst, 5, 4433, false, enabled = true), 3)
        add(ColituAcEvents.tlsProbe(node, "vless-xhttp", ColituTlsProbe.Result.Ok, 5, 443, false, enabled = true), 4)
        assertEquals(3, q.entries.size)
    }

    private fun fixture() = JsonParser.parseReader(
        javaClass.classLoader!!.getResourceAsStream("tls-rst-fixtures.json")!!.reader(),
    ).asJsonObject
}
