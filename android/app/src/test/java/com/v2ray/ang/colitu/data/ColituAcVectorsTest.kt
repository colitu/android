package com.v2ray.ang.colitu.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared Adaptive Connect test vectors (canonical copy in the colitu-csl
 * repo, engine/testdata/ac-vectors; the same files run in the iOS, Windows and
 * Linux apps and in the Go engine). Every case must give exactly its "expect".
 */
class ColituAcVectorsTest {
    private fun load(name: String): JsonObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("ac-vectors/$name")
            ?: error("missing test resource ac-vectors/$name")
        return stream.reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
    }

    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { !it.isJsonNull }?.asString

    @Test
    fun ranking() {
        val doc = load("ranking.json")
        val now = doc.get("now_ms").asLong
        val defaultNetwork = doc.get("network").asString
        val failures = mutableListOf<String>()
        for (element in doc.getAsJsonArray("cases")) {
            val case = element.asJsonObject
            val servers = case.getAsJsonArray("servers").map { it.asJsonObject }.map { s ->
                val id = s.get("id").asString
                ColituServer(
                    id = id,
                    displayName = id,
                    countryCode = s.text("country"),
                    city = null,
                    isRecommended = false,
                    isAvailable = s.get("available")?.asBoolean ?: true,
                )
            }
            val pings = case.getAsJsonObject("pings").entrySet().associate { (id, value) ->
                val p = value.asJsonObject
                id to ColituPing(
                    ms = p.get("ms")?.takeIf { !it.isJsonNull }?.asInt,
                    measuredAt = now - p.get("age_s").asLong * 1000,
                    network = p.text("network") ?: defaultNetwork,
                )
            }
            val penaltyAt = case.getAsJsonArray("penalized").map { it.asJsonObject }
                .associate { it.get("id").asString to now - it.get("age_s").asLong * 1000 }
            val got = ColituServerRanking.rank(
                servers, pings, defaultNetwork, case.text("last_good"), penaltyAt.keys, case.text("client_country"), now, penaltyAt,
            ).map { it.id }
            val expect = case.getAsJsonArray("expect").map { it.asString }
            if (got != expect) failures += "${case.get("name").asString}: expected $expect, got $got"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
