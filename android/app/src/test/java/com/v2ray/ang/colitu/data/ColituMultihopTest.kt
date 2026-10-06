package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Panel contract of 2026-10-06, section 3: multihop routes (VLESS only). */
class ColituMultihopTest {
    private val list = """
        {
          "servers": [
            {"id":"node-fi","name":"Helsinki","country":"FI","city":"Helsinki","status":"online","load":"low","latency_host":"fi.example","latency_port":443,
             "protocols":["vless-reality","hysteria2"]}
          ],
          "multihop": [
            {"id":"route-1","route_slug":"fi-de","multihop":true,"name":"Helsinki → Frankfurt",
             "country":"DE","city":"Frankfurt",
             "entry":{"node_id":"node-fi","name":"Helsinki","country":"FI","city":"Helsinki"},
             "exit":{"node_id":"node-de","name":"Frankfurt","country":"de","city":"Frankfurt"},
             "status":"online","load":"medium","protocols":["vless-reality","vless-xhttp"],
             "latency_host":"fi.example","latency_port":443,"latency_note":"entry_only_estimate"},
            {"id":"route-2","multihop":true,"name":"Broken","status":"online"},
            {"id":"","multihop":true,"entry":{"country":"SE"},"exit":{"country":"NL"}},
            {"id":"route-3","multihop":true,"entry":{"country":"SE"},"exit":{"country":"NL"},"status":"offline"},
            {"id":"route-4","multihop":true,"entry":{"country":"SE"},"exit":{"country":"NL"},"protocols":["hysteria2","trojan"]},
            {"id":"route-5","multihop":false,"entry":{"country":"SE"},"exit":{"country":"NL"}}
          ]
        }
    """.trimIndent()

    private fun config(protocol: String?) = ColituVpnConfig(
        serverId = "s", configType = "xray-mobile-v1", protocolType = protocol, rawConfig = "{}", expiresAt = null, unlimited = false, revision = 1,
    )

    @Test
    fun routesAreParsedFromTheServerList() {
        val response = ColituServerListResponse.fromJson(JsonParser.parseString(list).asJsonObject)

        // Broken items (no ends, no id, offline, no VLESS, multihop=false) are skipped.
        assertEquals(listOf("route-1"), response.multihop.map { it.id })
        val route = response.multihop.single()
        assertTrue(route.isMultihop)
        assertEquals("fi-de", route.route?.slug)
        assertEquals("FI", route.route?.entry?.country)
        assertEquals("node-fi", route.route?.entry?.nodeId)
        assertEquals("DE", route.route?.exit?.country)
        assertEquals("Frankfurt", route.route?.exit?.label)
        // The flag and the filters follow the exit; the ping host is the entry's.
        assertEquals("DE", route.countryCode)
        assertEquals("fi.example", route.latencyHost)
        assertEquals(443, route.latencyPort)
        assertEquals("Helsinki → Frankfurt", route.displayName)
        assertEquals(listOf("vless-reality", "vless-xhttp"), route.protocols)
        assertEquals("medium", route.load)
    }

    @Test
    fun routesStayOutOfTheNodeList() {
        val response = ColituServerListResponse.fromJson(JsonParser.parseString(list).asJsonObject)

        assertEquals(listOf("node-fi"), response.servers.map { it.id })
        assertFalse(response.servers.single().isMultihop)
        assertTrue(response.servers.none { it.id in response.multihop.map { r -> r.id } })
    }

    @Test
    fun anOlderPanelHasNoMultihop() {
        val response = ColituServerListResponse.fromJson(
            JsonParser.parseString("""{"servers":[{"id":"a","country":"FI","status":"online"}]}""").asJsonObject,
        )

        assertEquals(1, response.servers.size)
        assertTrue(response.multihop.isEmpty())
    }

    @Test
    fun theDedicatedListIsReadFromServers() {
        val json = JsonParser.parseString(
            """{"servers":[{"id":"r","entry":{"name":"A","country":"fi"},"exit":{"name":"B","country":"de"}}]}""",
        ).asJsonObject

        val routes = ColituMultihop.parseRoutes(json, key = "servers")

        assertEquals(1, routes.size)
        // Without a name the route is named by its ends.
        assertEquals("A → B", routes.single().displayName)
        assertEquals("DE", routes.single().countryCode)
    }

    @Test
    fun endpointLabelFallsBackFromCityToNameToCountry() {
        assertEquals("Helsinki", ColituRouteEndpoint("n", "Node", "FI", "Helsinki").label)
        assertEquals("Node", ColituRouteEndpoint("n", "Node", "FI", null).label)
        assertEquals("FI", ColituRouteEndpoint(null, null, "FI", null).label)
        assertNull(ColituRouteEndpoint.fromJson(null))
        assertEquals("Helsinki", ColituRouteEndpoint.fromJson(JsonParser.parseString("\"Helsinki\""))?.name)
    }

    @Test
    fun vlessFilteringDropsHysteria2AndTheOtherTransports() {
        val all = listOf("hysteria2", "vless-reality", "trojan", "vless-xhttp", "shadowsocks").map(::config)

        val vless = ColituMultihop.restrictToVless(all)

        assertEquals(listOf("vless-reality", "vless-xhttp"), vless.map { it.protocolType })
    }

    @Test
    fun aNodeWithoutVlessLeavesNothing() {
        assertTrue(ColituMultihop.restrictToVless(listOf(config("hysteria2"), config("trojan"), config(null))).isEmpty())
        assertTrue(ColituMultihop.restrictToVless(emptyList()).isEmpty())
    }

    @Test
    fun onlyTheTwoVlessTransportsCount() {
        assertTrue(ColituMultihop.isVless("vless-reality"))
        assertTrue(ColituMultihop.isVless("vless-xhttp"))
        assertFalse(ColituMultihop.isVless("hysteria2"))
        assertFalse(ColituMultihop.isVless(null))
    }
}
