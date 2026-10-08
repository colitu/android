package com.v2ray.ang.colitu.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ColituEndpointsTest {
    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/endpoints/$name.signed.json")!!.readBytes().toString(Charsets.UTF_8)

    private val builtinApi = listOf("https://api.colitu.com/api/v1", "https://mirror.example.test/capi/v1")
    private val builtinLists = listOf(
        "https://colitu.com/downloads/endpoints.json",
        "https://mirror.example.test/downloads/endpoints.json",
    )

    private fun newState(store: EndpointStore = MemoryStore()) = ColituEndpointState(store, builtinApi, builtinLists)

    private class MemoryStore : EndpointStore {
        val map = HashMap<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
    }

    private fun payload(schema: String = "1", version: String = "1", api: String = "[\"https://a.example\"]") =
        "{\"schema\":$schema,\"version\":$version,\"api\":$api," +
            "\"web\":[\"https://a.example\"],\"lists\":[\"https://a.example/x\"]}"

    // Signed list

    @Test fun validFixtureIsAccepted() {
        val list = ColituEndpointList.accept(fixture("valid"), null)
        assertNotNull(list)
        assertEquals(1000L, list!!.version)
        assertEquals(listOf("https://api.colitu.com/api/v1", "https://mirror.example.test/capi/v1"), list.api)
        assertEquals(2, list.lists.size)
    }

    @Test fun tamperedFixtureIsRejected() {
        assertNull(ColituEndpointList.accept(fixture("tampered"), null))
    }

    @Test fun wrongKeyIdIsRejected() {
        assertNull(ColituEndpointList.accept(fixture("wrong-key-id"), null))
    }

    @Test fun olderOrEqualVersionIsRejectedAfterNewerIsStored() {
        val state = newState()
        assertTrue(state.accept(fixture("valid")))
        assertEquals(1000L, state.storedVersion())
        // The same file (equal version) is no upgrade.
        assertFalse(state.accept(fixture("valid")))
        assertNull(ColituEndpointList.accept(fixture("valid"), 1001L))
        assertNotNull(ColituEndpointList.accept(fixture("valid"), 999L))
    }

    @Test fun garbageAndBadShapesAreRejected() {
        assertNull(ColituEndpointList.accept("not json", null))
        assertNull(ColituEndpointList.accept("{}", null))
        assertNotNull(ColituEndpointList.parsePayload(payload()))
        assertNull(ColituEndpointList.parsePayload(payload(schema = "2")))
        assertNull(ColituEndpointList.parsePayload(payload(api = "[]")))
        assertNull(ColituEndpointList.parsePayload(payload(api = "[\"http://a.example\"]")))
        assertNull(ColituEndpointList.parsePayload(payload(api = "[\"https://a.example/?q=1\"]")))
        assertNull(ColituEndpointList.parsePayload(payload(api = "[\"https://a.example/#f\"]")))
        assertNull(ColituEndpointList.parsePayload(payload(version = "\"1\"")))
        assertNull(ColituEndpointList.parsePayload(payload(version = "1.5")))
    }

    @Test fun storedListSurvivesAReloadAndUsesItsBases() {
        val store = MemoryStore()
        assertTrue(newState(store).accept(fixture("valid")))
        val reloaded = newState(store)
        assertEquals(1000L, reloaded.storedVersion())
        assertEquals(listOf("https://api.colitu.com/api/v1", "https://mirror.example.test/capi/v1"), reloaded.bases())
    }

    @Test fun refreshStopsAtTheFirstAcceptedFile() {
        val state = newState()
        val asked = ArrayList<String>()
        val ok = ColituEndpoints.refresh({ url ->
            asked += url
            if (url.startsWith("https://colitu.com")) null else fixture("valid")
        }, state)
        assertTrue(ok)
        assertEquals(builtinLists, asked)
        asked.clear()
        // A bad file is ignored and the next URL is asked.
        assertFalse(ColituEndpoints.refresh({ asked += it; fixture("tampered") }, state))
        assertEquals(2, asked.size)
    }

    @Test fun mirrorsFromTheBuildAreParsed() {
        assertEquals(listOf("https://m1.example.test", "https://m2.example.test"),
            ColituEndpoints.parseMirrors(" https://m1.example.test/ , http://bad.example.test,https://m2.example.test,https://m1.example.test"))
        assertEquals(emptyList<String>(), ColituEndpoints.parseMirrors(""))
    }

    // Base ordering

    @Test fun lastWorkingBaseComesFirstWithoutDuplicates() {
        val api = listOf("https://a.example/v1", "https://b.example/v1", "https://a.example/v1/", "https://c.example/v1")
        assertEquals(listOf("https://a.example/v1", "https://b.example/v1", "https://c.example/v1"),
            ColituEndpoints.orderBases(api, null))
        assertEquals(listOf("https://b.example/v1", "https://a.example/v1", "https://c.example/v1"),
            ColituEndpoints.orderBases(api, "https://b.example/v1"))
        assertEquals(listOf("https://c.example/v1", "https://a.example/v1", "https://b.example/v1"),
            ColituEndpoints.orderBases(api, "https://c.example/v1/"))
        // A remembered base that is no longer listed is ignored.
        assertEquals(listOf("https://a.example/v1", "https://b.example/v1", "https://c.example/v1"),
            ColituEndpoints.orderBases(api, "https://gone.example/v1"))
    }

    @Test fun stateRemembersTheWorkingBase() {
        val state = newState()
        assertEquals(builtinApi, state.bases())
        state.markWorked("https://mirror.example.test/capi/v1")
        assertEquals(listOf("https://mirror.example.test/capi/v1", "https://api.colitu.com/api/v1"), state.bases())
    }

    // Failover classification

    @Test fun networkErrorMovesToTheNextBase() {
        val tried = ArrayList<String>()
        val (base, value) = ColituEndpoints.failover(listOf("a", "b"), "GET") {
            tried += it
            if (it == "a") throw UnknownHostException("a") else "ok"
        }
        assertEquals("b", base)
        assertEquals("ok", value)
        assertEquals(listOf("a", "b"), tried)
    }

    @Test fun httpErrorIsAnAnswerAndStopsFailover() {
        val tried = ArrayList<String>()
        // A 500 is a returned response, not an exception: the first base is final.
        val (base, status) = ColituEndpoints.failover(listOf("a", "b"), "GET") { tried += it; 500 }
        assertEquals("a", base)
        assertEquals(500, status)
        assertEquals(listOf("a"), tried)
    }

    @Test fun everyBaseIsTriedOnceAndTheLastErrorIsThrown() {
        val tried = ArrayList<String>()
        try {
            ColituEndpoints.failover(listOf("a", "b"), "GET") { tried += it; throw ConnectException(it) }
            fail("expected an exception")
        } catch (e: ConnectException) {
            assertEquals("b", e.message)
        }
        assertEquals(listOf("a", "b"), tried)
    }

    @Test fun postAfterSendingIsNotRetried() {
        // Reset after the body may have been written, and a read timeout, stay with this base.
        for (e in listOf<IOException>(SocketException("Connection reset"), SocketTimeoutException("timeout"))) {
            val tried = ArrayList<String>()
            try {
                ColituEndpoints.failover(listOf("a", "b"), "POST") { tried += it; throw e }
                fail("expected an exception")
            } catch (_: IOException) {
            }
            assertEquals(listOf("a"), tried)
        }
    }

    @Test fun classification() {
        val beforeSend = listOf<IOException>(
            UnknownHostException("x"), ConnectException("refused"), SSLHandshakeException("handshake"),
            SocketTimeoutException("failed to connect to x after 15000ms"),
        )
        for (e in beforeSend) {
            assertTrue(e.toString(), ColituEndpoints.isFailoverEligible(e, "POST"))
            assertTrue(e.toString(), ColituEndpoints.isFailoverEligible(e, "GET"))
        }
        val reset = SocketException("Connection reset")
        assertFalse(ColituEndpoints.isFailoverEligible(reset, "POST"))
        assertTrue(ColituEndpoints.isFailoverEligible(reset, "GET"))
        assertTrue(ColituEndpoints.isFailoverEligible(SocketTimeoutException("timeout"), "GET"))
        assertFalse(ColituEndpoints.isFailoverEligible(SocketTimeoutException("timeout"), "PUT"))
        assertFalse(ColituEndpoints.isFailoverEligible(IOException("Canceled"), "GET"))
    }
}
