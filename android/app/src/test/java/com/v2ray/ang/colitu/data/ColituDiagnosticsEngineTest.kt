package com.v2ray.ang.colitu.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.colitu.repository.ColituSupportRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The report's `engine` section: DiagnosticsEngine schema, bounds, masking. */
class ColituDiagnosticsEngineTest {
    private val mask: (String) -> String = { SiteRedaction.apply(ColituSupportRepository.redact(it), listOf("de1.colitu-nodes.net")) }

    private fun journal(): ColituEngineJournal = ColituEngineJournal()

    private fun decision(j: ColituEngineJournal, i: Int, padding: Int = 0) = j.add("decision", i.toLong()) {
        addProperty("engine", "ac4")
        addProperty("choice", "node-$i")
        addProperty("reason", "best_score")
        add("candidates", JsonArray().apply {
            repeat(3) { c -> add(JsonObject().apply { addProperty("node_id", "node-$c"); addProperty("pad", "x".repeat(padding)) }) }
        })
    }

    @Test
    fun followsTheSchemaWithOutcomesOnTheirDecision() {
        val j = journal()
        decision(j, 1)
        j.add("outcome", 2L) { addProperty("node_id", "node-1"); addProperty("ok", false) }
        decision(j, 3)
        j.add("outcome", 4L) { addProperty("node_id", "node-3"); addProperty("ok", true) }
        j.add("ping", 5L) { addProperty("ok", 10) }
        j.add("whitelist", 6L) { addProperty("flag", "on:f1/5:d3/4") }
        j.add("hidden", 7L) { addProperty("unreachable", 1) }
        val hidden = listOf(JsonObject().apply { addProperty("node_id", "node-9"); addProperty("reason", "unreachable") })
        val engine = ColituDiagnosticsEngine.build(j.snapshot(), hidden, "1 2026-11-02T03:00:00Z", mapOf("ac_engine_4" to true, "client_country" to "RU", "none" to null), mask)

        assertEquals(setOf("model_version", "flags", "decisions", "ping_rounds", "hidden_servers", "whitelist_checks"), engine.keySet())
        val decisions = engine.getAsJsonArray("decisions")
        assertEquals(2, decisions.size())
        // Newest first, each with its own outcome.
        assertEquals("node-3", decisions[0].asJsonObject.get("choice").asString)
        assertTrue(decisions[0].asJsonObject.getAsJsonArray("outcomes")[0].asJsonObject.get("ok").asBoolean)
        assertFalse(decisions[1].asJsonObject.getAsJsonArray("outcomes")[0].asJsonObject.get("ok").asBoolean)
        assertEquals(1, engine.getAsJsonArray("ping_rounds").size())
        assertEquals("on:f1/5:d3/4", engine.getAsJsonArray("whitelist_checks")[0].asJsonObject.get("flag").asString)
        assertEquals("node-9", engine.getAsJsonArray("hidden_servers")[0].asJsonObject.get("node_id").asString)
        val flags = engine.getAsJsonObject("flags")
        assertTrue(flags.get("ac_engine_4").asBoolean)
        assertEquals("RU", flags.get("client_country").asString)
        assertFalse(flags.has("none"))
        assertEquals("1 2026-11-02T03:00:00Z", engine.get("model_version").asString)
    }

    @Test
    fun keepsTheNewestWithinTheCounts() {
        val j = ColituEngineJournal(capacity = 1000)
        repeat(320) { decision(j, it) }
        repeat(70) { j.add("ping", it.toLong()) { addProperty("n", it) } }
        repeat(120) { j.add("whitelist", it.toLong()) { addProperty("n", it) } }
        val hidden = List(250) { JsonObject().apply { addProperty("node_id", "n$it") } }
        val flags = (0 until 80).associate { "f$it" to true }
        val engine = ColituDiagnosticsEngine.build(j.snapshot(), hidden, null, flags, mask)
        val decisions = engine.getAsJsonArray("decisions")
        assertEquals(300, decisions.size())
        assertEquals("node-319", decisions[0].asJsonObject.get("choice").asString)
        assertEquals("node-20", decisions[299].asJsonObject.get("choice").asString)
        assertEquals(60, engine.getAsJsonArray("ping_rounds").size())
        assertEquals(10, engine.getAsJsonArray("ping_rounds")[0].asJsonObject.get("n").asInt)
        assertEquals(100, engine.getAsJsonArray("whitelist_checks").size())
        assertEquals(200, engine.getAsJsonArray("hidden_servers").size())
        assertEquals(64, engine.getAsJsonObject("flags").size())
        assertFalse(engine.has("model_version"))
    }

    @Test
    fun itemsStayUnder4KiBAndTheWholeUnder192KiBDroppingOldestDecisions() {
        val j = ColituEngineJournal()
        // Each decision ~3.3 KiB: 300 of them are ~1 MB, far over the total.
        repeat(300) { decision(j, it, padding = 1100) }
        val big = j.add("ping", 999L) { addProperty("pad", "y".repeat(5000)) } // over 4 KiB, no candidates: left out
        val engine = ColituDiagnosticsEngine.build(j.snapshot(), emptyList(), "1", emptyMap(), mask)
        assertTrue(ColituDiagnosticsEngine.bytes(engine) <= ColituDiagnosticsEngine.MAX_TOTAL_BYTES)
        val decisions = engine.getAsJsonArray("decisions")
        assertTrue(decisions.size() in 40..60)
        // The newest survive.
        assertEquals("node-299", decisions[0].asJsonObject.get("choice").asString)
        decisions.forEach { assertTrue(ColituDiagnosticsEngine.bytes(it) <= ColituDiagnosticsEngine.MAX_ITEM_BYTES) }
        assertEquals(0, engine.getAsJsonArray("ping_rounds").size())
        assertTrue(ColituDiagnosticsEngine.bytes(big) > ColituDiagnosticsEngine.MAX_ITEM_BYTES)
        // A decision over 4 KiB loses candidates instead of being dropped.
        val huge = JsonObject().apply {
            addProperty("choice", "a")
            add("candidates", JsonArray().apply { repeat(10) { add(JsonObject().apply { addProperty("pad", "z".repeat(1000)) }) } })
        }
        val fitted = ColituDiagnosticsEngine.fit(huge)!!
        assertTrue(fitted.get("candidates_trimmed").asBoolean)
        assertTrue(fitted.getAsJsonArray("candidates").size() in 1..4)
        assertTrue(ColituDiagnosticsEngine.bytes(fitted) <= ColituDiagnosticsEngine.MAX_ITEM_BYTES)
        assertNull(ColituDiagnosticsEngine.fit(JsonObject().apply { addProperty("pad", "q".repeat(5000)) }))
    }

    @Test
    fun stringsAreMaskedLikeTheReport() {
        val j = journal()
        j.add("decision", 1L) {
            addProperty("choice", "00000000-0000-4000-8000-0000000000de")
            addProperty("note", "dial tcp:example.org:443 from 203.0.113.7 user me@example.com via vless://secret@de1.colitu-nodes.net:8443")
            addProperty("api", "https://api.colitu.com/v1")
            addProperty("ms", 80)
        }
        val engine = ColituDiagnosticsEngine.build(j.snapshot(), emptyList(), null, mapOf("client_country" to "RU"), mask)
        val d = engine.getAsJsonArray("decisions")[0].asJsonObject
        assertEquals("00000000-0000-4000-8000-0000000000de", d.get("choice").asString)
        val note = d.get("note").asString
        assertFalse(note, note.contains("example.org"))
        assertFalse(note, note.contains("203.0.113.7"))
        assertFalse(note, note.contains("me@example.com"))
        assertFalse(note, note.contains("secret"))
        assertEquals("https://api.colitu.com/v1", d.get("api").asString)
        assertEquals(80, d.get("ms").asInt)
    }

    @Test
    fun uploadBodyCarriesTheEngineOnlyWhenGiven() {
        val without = ColituDiagnosticsUpload.body("r", "3.0.0 (1)", "Android 14")
        assertFalse(without.has("engine"))
        val with = ColituDiagnosticsUpload.body("r", "3.0.0 (1)", "Android 14", JsonObject().apply { add("decisions", JsonArray()) })
        assertTrue(with.getAsJsonObject("engine").has("decisions"))
    }
}
