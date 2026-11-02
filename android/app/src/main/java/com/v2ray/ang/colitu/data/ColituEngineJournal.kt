package com.v2ray.ang.colitu.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tencent.mmkv.MMKV

/**
 * The Adaptive Connect engine journal (Phase 4 preparation): the last
 * [CAPACITY] records of what the automatic choice did and saw, for the
 * silent support report. Separate from [ColituAcEvents] (anonymous
 * telemetry queue): this one stays on the device until a report takes it.
 *
 * Kinds: `decision` (engine, model version, candidates with features and
 * scores, choice, reason), `outcome` (how the chosen server did), `ping`
 * (round summary), `hidden` (servers the list left out), `whitelist`
 * (checks and state changes). Server ids are Colitu node ids; no address
 * or site is written. Pure ring buffer + JSON; [ColituEngineJournalStore]
 * keeps it in MMKV.
 */
class ColituEngineJournal(private val capacity: Int = CAPACITY) {
    private val records = ArrayDeque<JsonObject>()

    @Synchronized
    fun add(kind: String, at: Long, fill: JsonObject.() -> Unit = {}): JsonObject {
        val record = JsonObject().apply {
            addProperty("t", at)
            addProperty("kind", kind)
            fill()
        }
        records.addLast(record)
        while (records.size > capacity) records.removeFirst()
        return record
    }

    @Synchronized
    fun snapshot(): List<JsonObject> = records.toList()

    val size: Int @Synchronized get() = records.size

    @Synchronized
    fun clear() = records.clear()

    @Synchronized
    fun toJson(): String = JsonArray().apply { records.forEach { add(it) } }.toString()

    companion object {
        const val CAPACITY = 300
        /** Candidates written per decision (best first); the rest only counted. */
        const val MAX_CANDIDATES = 12

        fun fromJson(raw: String?, capacity: Int = CAPACITY): ColituEngineJournal = ColituEngineJournal(capacity).also { journal ->
            runCatching {
                if (raw.isNullOrBlank()) return@runCatching
                JsonParser.parseString(raw).asJsonArray.forEach { e ->
                    if (e.isJsonObject) {
                        journal.records.addLast(e.asJsonObject)
                        while (journal.records.size > capacity) journal.records.removeFirst()
                    }
                }
            }
        }

        private fun r4(v: Double): Double = Math.round(v * 10_000.0) / 10_000.0

        /** A [ColituLm] decision as a `decision` record's fields. */
        fun decisionFields(
            target: JsonObject,
            decision: ColituLm.Decision,
            candidates: List<ColituLm.Candidate>,
            modelVersion: Int?,
            whitelistMode: Boolean,
            reason: String,
            model: ColituLm.Model? = null,
            country: String = "",
        ) {
            target.addProperty("engine", decision.engine)
            modelVersion?.let { target.addProperty("model", it) }
            target.addProperty("stale", decision.stale)
            target.addProperty("explored", decision.explored)
            target.addProperty("whitelist", whitelistMode)
            target.addProperty("whitelist_none", decision.whitelistNone)
            target.addProperty("reason", reason)
            model?.city?.takeIf { it.isNotEmpty() }?.let { target.addProperty("model_city", it) }
            target.addProperty("candidates_total", candidates.size)
            target.addProperty("eligible", decision.ranking.size)
            decision.choice?.let { i ->
                target.addProperty("choice", candidates[i].nodeId)
                target.addProperty("protocol", candidates[i].protocol)
            }
            val shown = (listOfNotNull(decision.choice) + decision.ranking).distinct().take(MAX_CANDIDATES)
            target.add("candidates", JsonArray().apply {
                shown.forEach { i ->
                    val c = candidates[i]
                    val s = decision.candidates[i]
                    add(JsonObject().apply {
                        addProperty("node_id", c.nodeId)
                        addProperty("protocol", c.protocol)
                        if (c.entryCountry != c.exitCountry) addProperty("entry", c.entryCountry)
                        addProperty("country", c.exitCountry)
                        c.latencyMs?.let { addProperty("ms", it.toLong()) }
                        addProperty("history", c.history.size)
                        model?.scopeOf(country, c.nodeId)?.let { addProperty("prior_scope", it) }
                        add("f", JsonObject().apply {
                            addProperty("latency", r4(s.features.latency))
                            addProperty("own", r4(s.features.own))
                            addProperty("prior", r4(s.features.prior))
                            addProperty("hint", s.features.hint)
                            addProperty("recommended", s.features.recommended)
                            addProperty("load", s.features.load)
                            addProperty("protocol_fit", r4(s.features.protocolFit))
                            addProperty("whitelist", s.features.whitelist)
                        })
                        addProperty("alpha", r4(s.alpha))
                        addProperty("beta", r4(s.beta))
                        addProperty("score", r4(s.score))
                        addProperty("tier", s.tier)
                        s.theta?.let { addProperty("theta", r4(it)) }
                    })
                }
            })
        }
    }
}

/** The app's journal, kept in MMKV (saved after each record). */
object ColituEngineJournalStore {
    private const val STORE_ID = "COLITU_LM"
    private const val KEY = "engine_journal"

    private val store by lazy { MMKV.mmkvWithID(STORE_ID, MMKV.MULTI_PROCESS_MODE) }

    val journal: ColituEngineJournal by lazy { ColituEngineJournal.fromJson(runCatching { store.decodeString(KEY) }.getOrNull()) }

    fun add(kind: String, fill: JsonObject.() -> Unit = {}) {
        journal.add(kind, System.currentTimeMillis(), fill)
        runCatching { store.encode(KEY, journal.toJson()) }
    }

    /** The journal as JSON (for the support report, next step). */
    fun toJson(): String = journal.toJson()

    fun clear() {
        journal.clear()
        runCatching { store.removeValueForKey(KEY) }
    }
}
