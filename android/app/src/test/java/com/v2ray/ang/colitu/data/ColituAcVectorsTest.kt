package com.v2ray.ang.colitu.data

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.colitu.app.ColituTransportOrder
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

    private fun JsonObject.strings(key: String): List<String> = getAsJsonArray(key).map { it.asString }

    private fun JsonObject.stringOrNull(key: String): String? = text(key)

    private fun config(protocol: String) = ColituVpnConfig("s", null, "xray-mobile-v1", protocol, "{}", null, false)

    private fun check(file: String, section: String, run: (JsonObject) -> Pair<Any?, Any?>) {
        val failures = mutableListOf<String>()
        for (element in load(file).getAsJsonArray(section)) {
            val case = element.asJsonObject
            val (got, expect) = run(case)
            if (got != expect) failures += "$section/${case.get("name").asString}: expected $expect, got $got"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun transportBaseOrder() = check("transport.json", "base_order") { case ->
        val got = ColituTransportOrder.byRank(case.strings("offered").map(::config), case.strings("stalled").toSet()).map { it.protocolType }
        got to case.strings("expect")
    }

    @Test
    fun transportHintedStart() = check("transport.json", "hinted_start") { case ->
        val stalled = case.strings("stalled").toSet()
        val got = ColituNetworkHintsPolicy.hintedStart(
            case.strings("offered").map(::config), { it.protocolType }, case.strings("preferred"), stalled,
            case.stringOrNull("last_good"), { ColituTransportOrder.byRank(it, stalled) },
        )?.map { it.protocolType }
        val expect = case.get("expect").takeIf { !it.isJsonNull }?.asJsonArray?.map { it.asString }
        got to expect
    }

    @Test
    fun transportMarksIgnored() = check("transport.json", "marks_ignored") { case ->
        val offered = case.strings("offered")
        val stalled = case.strings("stalled").toSet()
        // The round-level view: ignored marks leave only what failed in this connect (none here).
        val viaEffective = ColituTransportOrder.effectiveStalled(offered, stalled, emptySet()).isEmpty() && (offered.toSet() intersect stalled).isNotEmpty()
        val direct = ColituTransportOrder.marksIgnored(offered, stalled)
        (direct to viaEffective) to (case.get("expect").asBoolean to case.get("expect").asBoolean)
    }

    private fun JsonElement.list(): JsonArray? = takeIf { !it.isJsonNull }?.asJsonArray

    @Test
    fun hintsParse() = check("hints.json", "parse") { case ->
        val json = JsonObject()
        case.get("blocked").list()?.let { json.add("blocked", it) }
        case.get("preferred").list()?.let { json.add("preferred", it) }
        val hints = ColituNetworkHints.fromJson(json)!!
        (hints.blocked.toList() to hints.preferred) to (case.strings("expect_blocked") to case.strings("expect_preferred"))
    }

    @Test
    fun recoveryDecide() = check("recovery.json", "decide") { case ->
        val use = ColituRecoverySet.shouldUse(case.get("automatic").asBoolean, case.get("api_unreachable").asBoolean, case.get("cache_usable").asBoolean) &&
            !case.get("multihop").asBoolean
        val got = when {
            !use -> "none"
            case.get("expired").asBoolean -> "expired"
            else -> "use"
        }
        got to case.get("expect").asString
    }

    @Test
    fun recoveryRefreshDue() {
        val doc = load("recovery.json")
        val now = java.time.Instant.parse(doc.get("now").asString)
        check("recovery.json", "refresh_due") { case ->
            fun age(key: String): Long? = case.get(key)?.takeIf { !it.isJsonNull }?.asLong
            val stored = age("generated_age_min")?.let {
                ColituRecoverySetData(now.minusSeconds(it * 60), now.plusSeconds(86_400), emptyList())
            }
            val attempt = age("attempt_age_min")?.let { now.toEpochMilli() - it * 60_000 } ?: 0L
            ColituRecoverySet.refreshDue(stored, attempt, now.toEpochMilli()) to case.get("expect").asBoolean
        }
    }

    private fun envelope(id: JsonElement): JsonObject = JsonObject().apply {
        if (!id.isJsonNull) add("server", JsonObject().apply { add("id", id) })
        add("profile", JsonObject())
    }

    @Test
    fun recoverySetParse() = check("recovery.json", "set_parse") { case ->
        val raw = JsonObject().apply {
            case.get("generated_at").takeIf { !it.isJsonNull }?.let { add("generated_at", it) }
            case.get("recovery_until").takeIf { !it.isJsonNull }?.let { add("recovery_until", it) }
            add("configs", JsonArray().apply { case.getAsJsonArray("servers").forEach { add(envelope(it)) } })
        }.toString()
        val got = ColituRecoverySet.parse(raw)?.configs?.map { ColituRecoverySet.serverId(it) }
        got to case.get("expect").list()?.map { it.asString }
    }

    @Test
    fun recoveryNextServer() = check("recovery.json", "next_server") { case ->
        val set = ColituRecoverySetData(
            java.time.Instant.EPOCH, java.time.Instant.EPOCH,
            case.strings("servers").map { envelope(com.google.gson.JsonPrimitive(it)) },
        )
        val got = ColituRecoverySet.serversToTry(set, case.strings("failed")).firstOrNull()?.let { ColituRecoverySet.serverId(it) }
        got to case.get("expect").takeIf { !it.isJsonNull }?.asString
    }
}
