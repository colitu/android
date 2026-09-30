package com.v2ray.ang.colitu

import com.google.gson.JsonParser
import com.google.gson.JsonObject
import com.v2ray.ang.colitu.data.ColituServer
import com.v2ray.ang.colitu.data.ColituServerListResponse
import com.v2ray.ang.colitu.data.ColituVpnConfig
import com.v2ray.ang.colitu.util.ColituErrorCode
import com.v2ray.ang.colitu.util.ColituErrorMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituParserTest {

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private val serverJson =
        """{"id":"s1","name":"Istanbul 1","country":"TR","city":"Istanbul","region":"eu","status":"online","load":"low","protocols":["vless-reality"]}"""

    private val configInner =
        """{"serverId":"s1","configType":"vless","subscriptionUrl":"https://example.com/sub"}"""

    // ── Canonical server contract ─────────────────────────────────────────────────

    @Test
    fun serverList_parsesCanonicalResponseAndPreservesBackendOrder() {
        val json = JsonParser.parseString(
            """{"servers":[$serverJson]}"""
        ).asJsonObject
        val result = ColituServerListResponse.fromJson(json)
        assertEquals(1, result.servers.size)
        assertEquals("s1", result.servers[0].id)
        assertTrue(result.servers[0].isRecommended)
        assertEquals(listOf("vless-reality"), result.servers[0].protocols)
    }

    @Test
    fun serverList_emptyOnMissingServersKey() {
        val json = JsonParser.parseString("""{"data":{"servers":[$serverJson]}}""").asJsonObject
        val result = ColituServerListResponse.fromJson(json)
        assertTrue(result.servers.isEmpty())
    }

    @Test
    fun serverList_skipsInvalidServerEntries() {
        val json = JsonParser.parseString(
            // one valid, one missing id → only one parsed
            """{"servers":[$serverJson,{"name":"No ID"}]}"""
        ).asJsonObject
        val result = ColituServerListResponse.fromJson(json)
        assertEquals(1, result.servers.size)
    }

    @Test
    fun server_rejectsLegacyAliasesAndNonOnlineRows() {
        assertNull(ColituServer.fromJson(JsonParser.parseString(
            """{"serverId":"s1","displayName":"Legacy","countryCode":"TR"}"""
        ).asJsonObject))
        val offline = JsonParser.parseString(serverJson).asJsonObject.apply {
            addProperty("status", "offline")
        }
        assertNull(ColituServer.fromJson(offline))
    }

    @Test
    fun server_acceptsLiveNodeWithEmptyRegionAndCity() {
        // Shape of the production Estonia node: no region, empty city, no load yet.
        val json = JsonParser.parseString(
            """{"id":"ee1","name":"Estonya","country":"EE","city":"","region":"","status":"online","protocols":["vless-reality","hysteria2","trojan","shadowsocks","tuic"]}"""
        ).asJsonObject
        val server = requireNotNull(ColituServer.fromJson(json))
        assertEquals("EE", server.countryCode)
        assertNull(server.city)
        assertNull(server.load)
        assertEquals(listOf("vless-reality", "hysteria2", "trojan", "shadowsocks"), server.protocols)
    }

    @Test
    fun server_readsServicesVerifiedFromTheNode() {
        val json = JsonParser.parseString(
            """{"id":"ee1","name":"Estonya","country":"EE","status":"online","protocols":["hysteria2"],"services":["chatgpt","gemini","netflix"]}"""
        ).asJsonObject
        val server = requireNotNull(ColituServer.fromJson(json))
        assertEquals(listOf("chatgpt", "gemini", "netflix"), server.services)
        assertTrue(server.opensAi)
        assertTrue(server.opensStreaming)
        assertFalse(requireNotNull(ColituServer.fromJson(JsonParser.parseString(serverJson).asJsonObject)).opensAi)
        // ChatGPT alone (the Estonia node today) is not enough for the AI filter.
        val chatOnly = JsonParser.parseString(
            """{"id":"ee2","name":"Estonya","country":"EE","status":"online","protocols":["hysteria2"],"services":["chatgpt","claude"]}"""
        ).asJsonObject
        assertFalse(requireNotNull(ColituServer.fromJson(chatOnly)).opensAi)
    }

    @Test
    fun server_rejectsNodeWithOnlyUnsupportedTransports() {
        val json = JsonParser.parseString(
            """{"id":"t1","name":"TUIC only","country":"DE","status":"online","protocols":["tuic"]}"""
        ).asJsonObject
        assertNull(ColituServer.fromJson(json))
    }

    @Test
    fun server_nullOnMissingId() {
        val json = JsonParser.parseString("""{"name":"No ID Server"}""").asJsonObject
        assertNull(ColituServer.fromJson(json))
    }

    @Test
    fun config_rejectsLegacyAndUnversionedProfiles() {
        for (wrapper in listOf("config", "vpnConfig", "data", "result")) {
            val json = JsonObject().apply { add(wrapper, JsonParser.parseString(configInner)) }
            assertNull("Legacy wrapper $wrapper", ColituVpnConfig.fromJson(json))
        }
        for (key in listOf("rawConfig", "raw_config", "wireguardConfig", "outbound", "subscriptionUrl", "config_url")) {
            val json = JsonObject().apply { addProperty(key, "https://example.test/config") }
            assertNull("Legacy field $key", ColituVpnConfig.fromJson(json))
        }
        assertNull(ColituVpnConfig.fromJson(JsonObject()))
    }
    // ── Error code mapping ────────────────────────────────────────────────────────

    @Test
    fun error_503_mapsToServerUnavailable() {
        assertEquals(ColituErrorCode.SERVER_UNAVAILABLE, ColituErrorMapper.toErrorCode("503"))
        assertEquals(ColituErrorCode.SERVER_UNAVAILABLE, ColituErrorMapper.toErrorCode("server_unavailable"))
        assertEquals(ColituErrorCode.SERVER_UNAVAILABLE, ColituErrorMapper.toErrorCode("service_unavailable"))
        assertEquals(ColituErrorCode.SERVER_UNAVAILABLE, ColituErrorMapper.toErrorCode("502"))
    }

    @Test
    fun error_timeout_mapsToTimeout() {
        assertEquals(ColituErrorCode.TIMEOUT, ColituErrorMapper.toErrorCode("timeout"))
        // case-insensitive
        assertEquals(ColituErrorCode.TIMEOUT, ColituErrorMapper.toErrorCode("TIMEOUT"))
    }

    @Test
    fun error_networkError_mapsToNetworkError() {
        assertEquals(ColituErrorCode.NETWORK_ERROR, ColituErrorMapper.toErrorCode("network_error"))
        assertEquals(ColituErrorCode.NETWORK_ERROR, ColituErrorMapper.toErrorCode("io_error"))
    }

    @Test
    fun error_authExpired_mapsCorrectly() {
        assertEquals(ColituErrorCode.AUTH_EXPIRED, ColituErrorMapper.toErrorCode("auth_expired"))
        assertEquals(ColituErrorCode.AUTH_EXPIRED, ColituErrorMapper.toErrorCode("unauthorized"))
        assertEquals(ColituErrorCode.AUTH_EXPIRED, ColituErrorMapper.toErrorCode("401"))
    }

    @Test
    fun error_configNotReady_isCaseInsensitive() {
        // Repository emits uppercase "CONFIG_NOT_READY"; mapper must handle it
        assertEquals(ColituErrorCode.CONFIG_NOT_READY, ColituErrorMapper.toErrorCode("CONFIG_NOT_READY"))
        assertEquals(ColituErrorCode.CONFIG_NOT_READY, ColituErrorMapper.toErrorCode("config_not_ready"))
        assertEquals(ColituErrorCode.CONFIG_NOT_READY, ColituErrorMapper.toErrorCode("server_not_found"))
    }

    @Test
    fun error_premiumRequired_mapsCorrectly() {
        assertEquals(ColituErrorCode.PREMIUM_REQUIRED, ColituErrorMapper.toErrorCode("premium_required"))
        assertEquals(ColituErrorCode.PREMIUM_REQUIRED, ColituErrorMapper.toErrorCode("server_not_allowed"))
    }

    @Test
    fun error_noServerSelected_mapsCorrectly() {
        assertEquals(ColituErrorCode.NO_SERVER_SELECTED, ColituErrorMapper.toErrorCode("no_server_selected"))
    }

    @Test
    fun error_unknown_mapsToGeneric() {
        assertEquals(ColituErrorCode.GENERIC, ColituErrorMapper.toErrorCode("some_unknown_error_xyz"))
        assertEquals(ColituErrorCode.GENERIC, ColituErrorMapper.toErrorCode(""))
        assertEquals(ColituErrorCode.GENERIC, ColituErrorMapper.toErrorCode("parse_error"))
        assertEquals(ColituErrorCode.GENERIC, ColituErrorMapper.toErrorCode("api_error"))
    }

    // Context-free message content tests (no Android Context needed)

    @Test
    fun error_contextFree_503_mentionsUnavailable() {
        val msg = ColituErrorMapper.toUserMessage("server_unavailable")
        assertTrue("Should mention unavailability", msg.lowercase().contains("unavailable"))
    }

    @Test
    fun error_contextFree_timeout_mentionsTimedOut() {
        val msg = ColituErrorMapper.toUserMessage("timeout")
        assertTrue("Should mention time", msg.lowercase().contains("timed out"))
    }

    @Test
    fun error_contextFree_unknown_mentionsNetwork() {
        val msg = ColituErrorMapper.toUserMessage("completely_unknown_garbage")
        assertTrue("Should mention network", msg.lowercase().contains("network"))
    }

    @Test
    fun error_contextFree_authExpired_mentionsSession() {
        val msg = ColituErrorMapper.toUserMessage("auth_expired")
        assertTrue("Should mention session", msg.lowercase().contains("session"))
    }

    @Test
    fun error_contextFree_premiumRequired_mentionsPremium() {
        val msg = ColituErrorMapper.toUserMessage("premium_required")
        assertTrue("Should mention premium", msg.lowercase().contains("premium"))
    }

    @Test
    fun error_contextFree_noServer_mentionsLocation() {
        val msg = ColituErrorMapper.toUserMessage("no_server_selected")
        assertTrue("Should mention location", msg.lowercase().contains("location"))
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

}
