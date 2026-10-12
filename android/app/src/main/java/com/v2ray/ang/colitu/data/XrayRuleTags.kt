package com.v2ray.ang.colitu.data

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser

/**
 * Xray 26.10 refuses to start ("app/router: duplicate ruleTag") when two
 * routing rules share a `ruleTag`. Several Colitu stages tag a group of rules
 * with one tag on purpose (Russian sites direct: a geosite and a geoip rule;
 * split tunnel) and find them again by that tag, so the tags stay shared in
 * the stored config and become unique only in the copy handed to the core:
 * the first rule keeps its tag, later ones get `-2`, `-3`, ...
 */
object XrayRuleTags {

    fun unique(raw: String): String {
        val json = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return raw
        val rules = json.get("routing")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray ?: return raw
        if (!hasDuplicate(rules)) return raw
        val taken = rules.mapNotNull { tagOf(it) }.toMutableSet()
        val seen = HashSet<String>()
        for (element in rules) {
            val tag = tagOf(element) ?: continue
            if (seen.add(tag)) continue
            var n = 2
            while ("$tag-$n" in taken) n++
            val renamed = "$tag-$n"
            taken += renamed
            element.asJsonObject.addProperty("ruleTag", renamed)
        }
        return json.toString()
    }

    private fun hasDuplicate(rules: JsonArray): Boolean {
        val seen = HashSet<String>()
        return rules.any { rule -> tagOf(rule)?.let { !seen.add(it) } == true }
    }

    private fun tagOf(element: JsonElement): String? =
        element.takeIf { it.isJsonObject }?.asJsonObject?.get("ruleTag")
            ?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }
}
