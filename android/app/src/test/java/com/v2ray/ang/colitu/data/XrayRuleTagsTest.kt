package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class XrayRuleTagsTest {

    private fun tags(raw: String) = JsonParser.parseString(raw).asJsonObject
        .getAsJsonObject("routing").getAsJsonArray("rules")
        .map { it.asJsonObject.get("ruleTag")?.asString }

    @Test
    fun `repeated tags become unique, first keeps its name`() {
        val raw = """{"routing":{"rules":[
            {"type":"field","domain":["geosite:category-ru"],"outboundTag":"direct","ruleTag":"colitu-ru-direct"},
            {"type":"field","ip":["geoip:ru"],"outboundTag":"direct","ruleTag":"colitu-ru-direct"},
            {"type":"field","network":"tcp,udp","outboundTag":"proxy"},
            {"type":"field","ip":["10.0.0.0/8"],"outboundTag":"direct","ruleTag":"colitu-ru-direct-2"},
            {"type":"field","domain":["example.test"],"outboundTag":"direct","ruleTag":"colitu-ru-direct"}]}}"""
        assertEquals(
            listOf("colitu-ru-direct", "colitu-ru-direct-3", null, "colitu-ru-direct-2", "colitu-ru-direct-4"),
            tags(XrayRuleTags.unique(raw)),
        )
    }

    @Test
    fun `config without duplicates is returned untouched`() {
        val raw = """{"routing":{"rules":[{"type":"field","outboundTag":"direct","ruleTag":"a"},{"type":"field","outboundTag":"proxy","ruleTag":"b"}]}}"""
        assertSame(raw, XrayRuleTags.unique(raw))
        val noRouting = """{"outbounds":[]}"""
        assertSame(noRouting, XrayRuleTags.unique(noRouting))
        assertSame("not json", XrayRuleTags.unique("not json"))
    }

    @Test
    fun `RU bypass output is accepted by the core after the pass`() {
        val base = """{"inbounds":[{"tag":"socks","protocol":"socks"}],"routing":{"rules":[{"type":"field","network":"tcp,udp","outboundTag":"proxy"}]}}"""
        val configured = ColituRuBypass.apply(base)
        val unique = tags(XrayRuleTags.unique(configured)).filterNotNull()
        assertEquals(unique.size, unique.toSet().size)
    }
}
