package com.v2ray.ang.colitu.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayPolicySanitizerTest {
    private fun obj(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private fun rules(routing: JsonObject) = routing.getAsJsonArray("rules").map { it.asJsonObject }

    @Test
    fun keepsProxyRulesAndLocalNetworkDirectRules() {
        val routing = XrayPolicySanitizer.routing(obj(
            """{"domainStrategy":"IPIfNonMatch","rules":[
              {"type":"field","domain":["example.test"],"outboundTag":"proxy"},
              {"type":"field","ip":["geoip:private","10.0.0.0/8","192.168.1.0/24","100.64.0.0/10","fe80::/10"],"outboundTag":"direct"},
              {"type":"field","domain":["geosite:private"],"outboundTag":"direct"},
              {"type":"field","domain":["geosite:category-ru"],"ip":["geoip:ru"],"outboundTag":"direct"}
            ]}""",
        ))
        assertEquals("IPIfNonMatch", routing.get("domainStrategy").asString)
        assertEquals(listOf("proxy", "direct", "direct", "direct"), rules(routing).map { it.get("outboundTag").asString })
    }

    @Test
    fun dropsDirectRulesForPublicTraffic() {
        val routing = XrayPolicySanitizer.routing(obj(
            """{"rules":[
              {"type":"field","network":"tcp,udp","outboundTag":"direct"},
              {"type":"field","port":"0-65535","outboundTag":"direct"},
              {"type":"field","ip":["0.0.0.0/0"],"outboundTag":"direct"},
              {"type":"field","ip":["10.0.0.0/1"],"outboundTag":"direct"},
              {"type":"field","ip":["geoip:private","8.8.8.8"],"outboundTag":"direct"},
              {"type":"field","domain":["example.test"],"outboundTag":"direct"},
              {"type":"field","domain":["regexp:.*"],"outboundTag":"direct"},
              {"type":"field","domain":["geosite:google"],"outboundTag":"direct"},
              {"type":"field","ip":["ext:custom.dat:private"],"outboundTag":"direct"}
            ]}""",
        ))
        assertTrue(rules(routing).isEmpty())
    }

    @Test
    fun dropsBalancersBlockAndUnknownTargets() {
        val routing = XrayPolicySanitizer.routing(obj(
            """{"domainStrategy":"Bogus","balancers":[{"tag":"b","selector":["direct"]}],"rules":[
              {"type":"field","domain":["example.test"],"balancerTag":"b"},
              {"type":"field","domain":["example.test"],"outboundTag":"proxy","balancerTag":"b"},
              {"type":"field","domain":["example.test"],"outboundTag":"block"},
              {"type":"field","domain":["example.test"],"outboundTag":"attacker"},
              {"type":"chinasites","domain":["example.test"],"outboundTag":"proxy"},
              {"type":"field","domain":"example.test","outboundTag":"proxy"},
              "not an object"
            ]}""",
        ))
        assertFalse(routing.has("balancers"))
        assertFalse(routing.has("domainStrategy"))
        assertTrue(rules(routing).isEmpty())
    }

    @Test
    fun copiesOnlyKnownRuleKeys() {
        val routing = XrayPolicySanitizer.routing(obj(
            """{"rules":[{"type":"field","ip":["10.0.0.0/8"],"outboundTag":"direct","ruleTag":"lan","extra":{"x":1}}]}""",
        ))
        assertEquals(setOf("type", "ip", "outboundTag", "ruleTag"), rules(routing).single().keySet())
    }

    @Test
    fun dnsKeepsDohAndIpServersOnly() {
        val dns = XrayPolicySanitizer.dns(obj(
            """{"hosts":{"example.test":"203.0.113.9"},"clientIp":"203.0.113.1","queryStrategy":"UseIPv4","servers":[
              "https://dns.example.test/dns-query",
              "1.1.1.1",
              "2001:db8::1",
              "localhost",
              "https+local://dns.example.test/dns-query",
              "tcp://1.1.1.1",
              "udp://1.1.1.1:53",
              "dns.example.test",
              "https://user@dns.example.test/dns-query",
              "fakedns",
              {"address":"https://dns.example.test/dns-query","port":443,"domains":["example.test"],"expectIPs":["geoip:ru"]},
              {"address":"localhost"}
            ]}""",
        ))
        assertFalse(dns.has("hosts"))
        assertFalse(dns.has("clientIp"))
        assertEquals("UseIPv4", dns.get("queryStrategy").asString)
        val servers = dns.getAsJsonArray("servers")
        assertEquals(4, servers.size())
        assertEquals("https://dns.example.test/dns-query", servers[0].asString)
        assertEquals("1.1.1.1", servers[1].asString)
        assertEquals("2001:db8::1", servers[2].asString)
        val objectServer = servers[3].asJsonObject
        assertEquals(setOf("address", "port", "domains"), objectServer.keySet())
    }

    @Test
    fun missingOrEmptyPoliciesStayEmpty() {
        assertEquals(JsonObject(), XrayPolicySanitizer.routing(null))
        assertEquals(JsonObject(), XrayPolicySanitizer.dns(null))
        assertEquals(JsonObject(), XrayPolicySanitizer.routing(JsonObject()))
        assertEquals(JsonObject(), XrayPolicySanitizer.dns(JsonObject()))
    }

    @Test
    fun adapterAppliesTheSanitizer() {
        val source = obj(
            """{
              "revision":42,
              "expires_at":"2026-09-12T10:00:00Z",
              "offline_grace_until":"2026-09-13T10:00:00Z",
              "server":{"id":"server-1"},
              "profile":{"format":"xray-mobile-v1","payload":{
                "schema_version":1,"protocol":"trojan",
                "endpoint":{"host":"vpn.example.test","port":443},
                "credentials":{"password":"fixture-password"},
                "transport":{"type":"tcp"},
                "security":{"type":"tls","server_name":"vpn.example.test"},
                "dns_policy":{"hosts":{"example.test":"203.0.113.9"},"servers":["https://dns.example.test/dns-query"]},
                "routing_policy":{"balancers":[{"tag":"b"}],"rules":[{"type":"field","network":"tcp,udp","outboundTag":"direct"}]}
              }}
            }""",
        )
        val runtime = obj(requireNotNull(XrayMobileAdapter.render(source, java.time.Instant.parse("2026-09-11T10:00:00Z")).rawConfig))
        assertNull(runtime.getAsJsonObject("dns").get("hosts"))
        assertEquals(1, runtime.getAsJsonObject("dns").getAsJsonArray("servers").size())
        assertFalse(runtime.getAsJsonObject("routing").has("balancers"))
        assertTrue(runtime.getAsJsonObject("routing").getAsJsonArray("rules").isEmpty)
    }

    @Test
    fun addressRanges() {
        assertTrue(ColituAddressRanges.isNonPublicCidr("172.16.0.0/12"))
        assertTrue(ColituAddressRanges.isNonPublicCidr("127.0.0.1"))
        assertFalse(ColituAddressRanges.isNonPublicCidr("172.0.0.0/8"))
        assertFalse(ColituAddressRanges.isNonPublicCidr("203.0.113.0/24"))
        assertFalse(ColituAddressRanges.isNonPublicCidr("geoip:private"))
        assertNull(ColituAddressRanges.parseLiteral("dns.example.test"))
        assertNull(ColituAddressRanges.parseLiteral("256.1.1.1"))
        assertTrue(ColituAddressRanges.isNonPublic(requireNotNull(ColituAddressRanges.parseLiteral("100.64.1.1"))))
        assertTrue(ColituAddressRanges.isNonPublic(requireNotNull(ColituAddressRanges.parseLiteral("::1"))))
        assertTrue(ColituAddressRanges.isNonPublic(requireNotNull(ColituAddressRanges.parseLiteral("fd00::1"))))
        assertFalse(ColituAddressRanges.isNonPublic(requireNotNull(ColituAddressRanges.parseLiteral("203.0.113.5"))))
    }
}
