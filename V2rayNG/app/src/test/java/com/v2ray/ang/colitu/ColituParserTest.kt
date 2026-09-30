package com.v2ray.ang.colitu

import com.google.gson.JsonParser
import com.google.gson.JsonObject
import com.v2ray.ang.colitu.data.ColituServer
import com.v2ray.ang.colitu.data.ColituServerListResponse
import com.v2ray.ang.colitu.data.ColituVpnConfig
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
    // ── Helpers ──────────────────────────────────────────────────────────────────

}
