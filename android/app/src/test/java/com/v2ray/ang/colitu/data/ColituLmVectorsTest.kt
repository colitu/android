package com.v2ray.ang.colitu.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The shared Colitu LM vectors (panel internal/acmodel, docs/ac-model-vectors.json):
 * RNG outputs, Beta samples and 28 decisions must match to the file's tolerance (1e-9).
 */
class ColituLmVectorsTest {
    private val doc: JsonObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("ac-model-vectors.json") ?: error("missing ac-model-vectors.json")
        stream.reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
    }
    private val tolerance: Double get() = doc.get("tolerance").asDouble

    private fun JsonElement?.isNull() = this == null || this.isJsonNull

    private fun near(failures: MutableList<String>, what: String, expected: Double, actual: Double) {
        if (abs(expected - actual) > tolerance) failures += "$what: expected $expected, got $actual"
    }

    @Test
    fun rngMatches() {
        for (v in doc.getAsJsonArray("rng")) {
            val o = v.asJsonObject
            val seed = o.get("seed").asLong
            val next = ColituLm.Rng(seed)
            o.getAsJsonArray("next").forEach { assertEquals("next seed $seed", it.asLong, next.next()) }
            val floats = ColituLm.Rng(seed)
            o.getAsJsonArray("floats").forEach { assertEquals("float seed $seed", it.asDouble, floats.float(), tolerance) }
        }
    }

    @Test
    fun betaSamplesMatch() {
        for (v in doc.getAsJsonArray("beta_samples")) {
            val o = v.asJsonObject
            val rng = ColituLm.Rng(o.get("seed").asLong)
            val alpha = o.get("alpha").asDouble
            val beta = o.get("beta").asDouble
            o.getAsJsonArray("expected").forEach { assertEquals("beta($alpha,$beta)", it.asDouble, ColituLm.sampleBeta(rng, alpha, beta), tolerance) }
        }
    }

    private fun input(o: JsonObject): ColituLm.Input = ColituLm.Input(
        model = o.get("model").takeUnless { it.isNull() }?.let { ColituLm.modelFromJson(it.asJsonObject) },
        modelAgeH = o.get("model_age_h").asDouble,
        engineEnabled = o.get("engine_enabled").asBoolean,
        country = o.get("country")?.takeUnless { it.isNull() }?.asString.orEmpty(),
        networkType = o.get("network_type")?.takeUnless { it.isNull() }?.asString.orEmpty(),
        whitelistMode = o.get("whitelist_mode").asBoolean,
        seed = o.get("seed").asLong,
        candidates = o.getAsJsonArray("candidates").map { e ->
            val c = e.asJsonObject
            ColituLm.Candidate(
                nodeId = c.get("node_id").asString,
                protocol = c.get("protocol").asString,
                entryCountry = c.get("entry_country").asString,
                exitCountry = c.get("exit_country").asString,
                whitelistOk = c.get("whitelist_ok").asBoolean,
                recommended = c.get("recommended").asBoolean,
                hintBlocked = c.get("hint_blocked").asBoolean,
                load = c.get("load")?.takeUnless { it.isNull() }?.asString,
                latencyMs = c.get("latency_ms")?.takeUnless { it.isNull() }?.asDouble,
                history = c.get("history")?.takeUnless { it.isNull() }?.asJsonArray?.map { h ->
                    ColituLm.Outcome(h.asJsonObject.get("age_h").asDouble, h.asJsonObject.get("ok").asBoolean)
                }.orEmpty(),
            )
        },
    )

    @Test
    fun all29DecisionCasesMatch() {
        val cases = doc.getAsJsonArray("cases")
        assertEquals(29, cases.size())
        val failures = mutableListOf<String>()
        for (element in cases) {
            val case = element.asJsonObject
            val name = case.get("name").asString
            val expected = case.getAsJsonObject("expected")
            val d = ColituLm.decide(input(case.getAsJsonObject("input")))
            if (d.engine != expected.get("engine").asString) failures += "$name: engine ${d.engine}"
            if (d.stale != expected.get("stale").asBoolean) failures += "$name: stale ${d.stale}"
            if (d.explored != expected.get("explored").asBoolean) failures += "$name: explored ${d.explored}"
            if (d.whitelistNone != expected.get("whitelist_none").asBoolean) failures += "$name: whitelist_none ${d.whitelistNone}"
            val ranking = expected.get("ranking").takeUnless { it.isNull() }?.asJsonArray?.map { it.asInt }.orEmpty()
            if (d.ranking != ranking) failures += "$name: ranking ${d.ranking} != $ranking"
            val choice = expected.get("choice").takeUnless { it.isNull() }?.asInt
            if (d.choice != choice) failures += "$name: choice ${d.choice} != $choice"
            val cands = expected.get("candidates").takeUnless { it.isNull() }?.asJsonArray?.map { it.asJsonObject }.orEmpty()
            if (cands.size != d.candidates.size) {
                failures += "$name: ${d.candidates.size} candidates, expected ${cands.size}"
                continue
            }
            cands.forEachIndexed { i, e ->
                val s = d.candidates[i]
                val f = e.getAsJsonObject("features")
                val p = "$name[$i]"
                near(failures, "$p latency", f.get("latency").asDouble, s.features.latency)
                near(failures, "$p own", f.get("own").asDouble, s.features.own)
                near(failures, "$p prior", f.get("prior").asDouble, s.features.prior)
                near(failures, "$p hint", f.get("hint").asDouble, s.features.hint)
                near(failures, "$p recommended", f.get("recommended").asDouble, s.features.recommended)
                near(failures, "$p load", f.get("load").asDouble, s.features.load)
                near(failures, "$p protocol_fit", f.get("protocol_fit").asDouble, s.features.protocolFit)
                near(failures, "$p whitelist", f.get("whitelist").asDouble, s.features.whitelist)
                near(failures, "$p alpha", e.get("alpha").asDouble, s.alpha)
                near(failures, "$p beta", e.get("beta").asDouble, s.beta)
                near(failures, "$p log_odds", e.get("log_odds").asDouble, s.logOdds)
                near(failures, "$p score", e.get("score").asDouble, s.score)
                if (e.get("eligible").asBoolean != s.eligible) failures += "$p eligible ${s.eligible}"
                if (e.get("tier").asInt != s.tier) failures += "$p tier ${s.tier}"
                val theta = e.get("theta").takeUnless { it.isNull() }?.asDouble
                when {
                    theta == null && s.theta != null -> failures += "$p theta ${s.theta}, expected none"
                    theta != null && s.theta == null -> failures += "$p theta missing"
                    theta != null -> near(failures, "$p theta", theta, s.theta!!)
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
