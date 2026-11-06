package com.v2ray.ang.colitu.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Adaptive Connect 4 decision model ("Colitu LM"): the on-device scorer of the
 * automatic choice. A line-for-line port of the panel's reference
 * (`internal/acmodel/model.go`, `rng.go`); the shared vectors
 * (`ac-model-vectors.json`, unit test [ColituLmVectorsTest]) pin every value
 * to 1e-9. Only +, -, *, /, ln, 2^x, sqrt and cos, so every platform repeats
 * the same arithmetic. No Android state: the controller builds the [Input].
 */
object ColituLm {
    /** The model file's `version`; an unknown one keeps the Adaptive Connect 3 rules. */
    const val FORMAT_VERSION = 1
    const val DEFAULT_EXPLORE = 0.05
    const val DEFAULT_HALFLIFE_H = 72.0
    const val DEFAULT_LATENCY_CAP_MS = 1000.0
    /** Older than this a model is "stale": still used, and a refetch is tried. */
    const val STALE_AFTER_H = 6.0
    /** Server-level evidence in the priors (probes and hint votes). */
    const val ANY_PROTOCOL = "*"

    const val ENGINE_AC3 = "ac3"
    const val ENGINE_AC4 = "ac4"

    data class Weights(
        val latency: Double = 1.0,
        val own: Double = 1.5,
        val prior: Double = 1.0,
        val hint: Double = -2.0,
        val recommended: Double = 0.25,
        val load: Double = -0.5,
        val protocolFit: Double = 0.5,
        val whitelist: Double = 1.0,
    )

    /** Body of GET /client/ac-model. */
    data class Model(
        val version: Int,
        val generatedAt: String = "",
        val country: String = "",
        val weights: Weights = Weights(),
        /** country -> node id -> protocol (or "*") -> [alpha, beta]. */
        val priors: Map<String, Map<String, Map<String, DoubleArray>>> = emptyMap(),
        /** network type -> protocol -> 0..1. */
        val protocolFit: Map<String, Map<String, Double>> = emptyMap(),
        val explore: Double = DEFAULT_EXPLORE,
        val halflifeH: Double = DEFAULT_HALFLIFE_H,
        val latencyCapMs: Double = DEFAULT_LATENCY_CAP_MS,
        /** City whose priors are mixed in; "" when only the country's (journal only). */
        val city: String = "",
        /** country -> node id -> "city" / "country": where each server's prior comes from (journal only). */
        val priorScope: Map<String, Map<String, String>> = emptyMap(),
    ) {
        /** "city" or "country" for [nodeId], null when the model does not say. */
        fun scopeOf(country: String, nodeId: String): String? = priorScope[country.uppercase()]?.get(nodeId.lowercase())

        /** The protocol's own prior, else the server-level "*", else Beta(1, 1). */
        fun priorFor(country: String, nodeId: String, protocol: String): DoubleArray {
            val protocols = priors[country.uppercase()]?.get(nodeId.lowercase())
            if (protocols != null) {
                protocols[protocol]?.takeIf { it.size == 2 && it[0] > 0 && it[1] > 0 }?.let { return it }
                protocols[ANY_PROTOCOL]?.takeIf { it.size == 2 && it[0] > 0 && it[1] > 0 }?.let { return it }
            }
            return doubleArrayOf(1.0, 1.0)
        }

        fun fit(networkType: String, protocol: String): Double =
            protocolFit[networkType]?.get(protocol)?.let(::clamp01) ?: 0.5
    }

    /** One past outcome of this device on this network with a candidate, [ageH] hours ago. */
    data class Outcome(val ageH: Double, val ok: Boolean)

    /** (server or route) x protocol. A plain server enters and exits in its own country. */
    data class Candidate(
        val nodeId: String,
        val protocol: String,
        val entryCountry: String,
        val exitCountry: String,
        val whitelistOk: Boolean = false,
        val recommended: Boolean = false,
        val hintBlocked: Boolean = false,
        val load: String? = null,
        /** null = unknown. */
        val latencyMs: Double? = null,
        val history: List<Outcome> = emptyList(),
    )

    data class Input(
        /** null while none was fetched. */
        val model: Model?,
        val modelAgeH: Double,
        /** `features.ac_engine_4`. */
        val engineEnabled: Boolean,
        val country: String,
        val networkType: String,
        val whitelistMode: Boolean,
        /** uint32 seed (only the low 32 bits count). */
        val seed: Long,
        val candidates: List<Candidate>,
    )

    data class Features(
        val latency: Double,
        val own: Double,
        val prior: Double,
        val hint: Double,
        val recommended: Double,
        val load: Double,
        val protocolFit: Double,
        val whitelist: Double,
    )

    data class Scored(
        val features: Features,
        val alpha: Double,
        val beta: Double,
        val logOdds: Double,
        val score: Double,
        val eligible: Boolean,
        /** Whitelist mode: bridges 0, other whitelist servers 1; otherwise 0. */
        val tier: Int,
        /** Thompson sample, only when the decision explored. */
        val theta: Double? = null,
    )

    /** [engine] "ac3": use the Adaptive Connect 3 rules; every other field is then empty. */
    data class Decision(
        val engine: String,
        val stale: Boolean = false,
        val explored: Boolean = false,
        val whitelistNone: Boolean = false,
        val candidates: List<Scored> = emptyList(),
        /** Indexes of the eligible candidates, best first. */
        val ranking: List<Int> = emptyList(),
        /** Index of the candidate to connect first; null when none is eligible. */
        val choice: Int? = null,
    )

    // ── Arithmetic ────────────────────────────────────────────────────────────

    /** Weight of an outcome [ageH] hours old: 2^(-ageH / halflifeH). */
    fun decay(ageH: Double, halflifeH: Double): Double {
        if (halflifeH <= 0) return 1.0
        return Math.pow(2.0, -(if (ageH < 0) 0.0 else ageH) / halflifeH)
    }

    fun clamp01(v: Double): Double = if (v < 0) 0.0 else if (v > 1) 1.0 else v

    private fun b01(b: Boolean): Double = if (b) 1.0 else 0.0

    /** low 0, medium 0.5, high 1, unknown 0. */
    fun loadFeature(load: String?): Double = when (load) {
        "medium" -> 0.5
        "high" -> 1.0
        else -> 0.0
    }

    /** 1 - ms/cap clamped to 0..1; unknown 0. */
    fun latencyFeature(ms: Double?, capMs: Double): Double {
        if (ms == null || ms < 0) return 0.0
        val cap = if (capMs <= 0) DEFAULT_LATENCY_CAP_MS else capMs
        return clamp01(1 - ms / cap)
    }

    fun isBridge(c: Candidate): Boolean {
        val entry = c.entryCountry.uppercase()
        val exit = c.exitCountry.uppercase()
        return entry.isNotEmpty() && exit.isNotEmpty() && entry != exit
    }

    /** Traffic leaving in Russia is never chosen automatically outside whitelist mode (10-10 rule). */
    fun exitsInRussia(c: Candidate): Boolean = c.exitCountry.equals("RU", ignoreCase = true)

    private fun key(c: Candidate): String = c.nodeId.lowercase() + "\u0000" + c.protocol

    /**
     * xorshift32 (13, 17, 5) on 32 bits; seed 0 becomes 0x9E3779B9 and the
     * first 8 outputs are dropped. Kotlin's Int carries the uint32 bits; the
     * right shift is the unsigned [ushr].
     */
    class Rng(seed: Long) {
        private var state: Int = (seed and 0xFFFFFFFFL).toInt().let { if (it == 0) ZERO_SEED else it }

        init {
            repeat(WARMUP) { next() }
        }

        /** The next state as an unsigned 32-bit value. */
        fun next(): Long {
            var x = state
            x = x xor (x shl 13)
            x = x xor (x ushr 17)
            x = x xor (x shl 5)
            state = x
            return x.toLong() and 0xFFFFFFFFL
        }

        /** Top 24 bits / 2^24: [0, 1), exact in a double. */
        fun float(): Double = (next() ushr 8).toDouble() / 16777216.0

        private companion object {
            const val ZERO_SEED = 0x9E3779B9.toInt()
            const val WARMUP = 8
        }
    }

    /** Normal approximation of Beta(alpha, beta), clamped; always exactly two draws. */
    fun sampleBeta(rng: Rng, alpha: Double, beta: Double): Double {
        val u1 = 1 - rng.float()
        val u2 = rng.float()
        val z = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2)
        val n = alpha + beta
        val mean = alpha / n
        val sd = Math.sqrt(alpha * beta / (n * n * (n + 1)))
        return clamp01(mean + sd * z)
    }

    // ── Decision ──────────────────────────────────────────────────────────────

    /**
     * [model] when it was built for the network's country, else null (Adaptive Connect 3 rules until
     * the right one comes): another country's priors miss and every server scores alike. Both empty
     * count as equal.
     */
    fun modelFor(model: Model?, networkCountry: String?): Model? =
        model?.takeIf { it.country.trim().uppercase() == networkCountry.orEmpty().trim().uppercase() }

    fun decide(input: Input): Decision {
        val m = input.model
        if (!input.engineEnabled || m == null || m.version != FORMAT_VERSION) return Decision(ENGINE_AC3)
        val halflife = if (m.halflifeH <= 0) DEFAULT_HALFLIFE_H else m.halflifeH
        val cands = input.candidates

        // Own feature: decayed successes and failures with the server (all its protocols).
        val ownOk = HashMap<String, Double>()
        val ownFail = HashMap<String, Double>()
        for (c in cands) {
            val node = c.nodeId.lowercase()
            for (o in c.history) {
                val target = if (o.ok) ownOk else ownFail
                target[node] = (target[node] ?: 0.0) + decay(o.ageH, halflife)
            }
        }

        var whitelistNone = false
        val whitelistAny = input.whitelistMode && cands.any { it.whitelistOk }
        if (input.whitelistMode) whitelistNone = !whitelistAny
        val useWhitelist = input.whitelistMode && whitelistAny

        val w = m.weights
        val scored = cands.map { c ->
            val node = c.nodeId.lowercase()
            val prior = m.priorFor(input.country, c.nodeId, c.protocol)
            val ok = ownOk[node] ?: 0.0
            val fail = ownFail[node] ?: 0.0
            val f = Features(
                latency = latencyFeature(c.latencyMs, m.latencyCapMs),
                own = (ok + 1) / (ok + fail + 2),
                prior = prior[0] / (prior[0] + prior[1]),
                hint = b01(c.hintBlocked),
                recommended = b01(c.recommended),
                load = loadFeature(c.load),
                protocolFit = m.fit(input.networkType, c.protocol),
                whitelist = b01(input.whitelistMode && c.whitelistOk),
            )
            var alpha = prior[0]
            var beta = prior[1]
            for (o in c.history) {
                if (o.ok) alpha += decay(o.ageH, halflife) else beta += decay(o.ageH, halflife)
            }
            val logOdds = Math.log(alpha) - Math.log(beta)
            val score = w.latency * f.latency + w.own * f.own + w.prior * f.prior + w.hint * f.hint + w.recommended * f.recommended +
                w.load * f.load + w.protocolFit * f.protocolFit + w.whitelist * f.whitelist + logOdds
            val eligible: Boolean
            val tier: Int
            if (useWhitelist) {
                eligible = c.whitelistOk
                tier = if (isBridge(c)) 0 else 1
            } else {
                eligible = !exitsInRussia(c)
                tier = 0
            }
            Scored(f, alpha, beta, logOdds, score, eligible, tier)
        }.toMutableList()

        val byKey = Comparator<Int> { a, b -> key(cands[a]).compareTo(key(cands[b])) }
        val ranking = scored.indices.filter { scored[it].eligible }.sortedWith(
            Comparator<Int> { a, b ->
                val x = scored[a]
                val y = scored[b]
                when {
                    x.tier != y.tier -> x.tier.compareTo(y.tier)
                    x.score != y.score -> if (x.score > y.score) -1 else 1
                    else -> byKey.compare(a, b)
                }
            },
        )
        val stale = input.modelAgeH > STALE_AFTER_H
        if (ranking.isEmpty()) return Decision(ENGINE_AC4, stale, false, whitelistNone, scored, ranking, null)
        var choice = ranking[0]

        // One uniform draw decides; exploring, every candidate of the best tier takes a
        // Thompson sample in key order and the highest wins (ties: the first).
        val rng = Rng(input.seed)
        var explored = false
        if (rng.float() < m.explore) {
            explored = true
            val top = scored[choice].tier
            val pool = ranking.filter { scored[it].tier == top }.sortedWith(byKey)
            var best = -1.0
            for (i in pool) {
                val theta = sampleBeta(rng, scored[i].alpha, scored[i].beta)
                scored[i] = scored[i].copy(theta = theta)
                if (theta > best) {
                    best = theta
                    choice = i
                }
            }
        }
        return Decision(ENGINE_AC4, stale, explored, whitelistNone, scored, ranking, choice)
    }

    // ── Model file ────────────────────────────────────────────────────────────

    /**
     * Parses the model file; null when it is not a JSON object or has no
     * numeric `version` (an unknown version parses and then decides "ac3").
     * Missing numbers read as Go's zero values, like the reference.
     */
    fun modelFromJson(json: JsonObject?): Model? = runCatching {
        json ?: return null
        val version = json.num("version")?.toInt() ?: return null
        val w = json.obj("weights")
        val weights = if (w == null) Weights() else Weights(
            latency = w.num("latency") ?: 0.0,
            own = w.num("own") ?: 0.0,
            prior = w.num("prior") ?: 0.0,
            hint = w.num("hint") ?: 0.0,
            recommended = w.num("recommended") ?: 0.0,
            load = w.num("load") ?: 0.0,
            protocolFit = w.num("protocol_fit") ?: 0.0,
            whitelist = w.num("whitelist") ?: 0.0,
        )
        val priors = json.obj("priors")?.entrySet()?.mapNotNull { (country, nodes) ->
            val nodeMap = nodes.asObj()?.entrySet()?.mapNotNull { (node, protocols) ->
                val protoMap = protocols.asObj()?.entrySet()?.mapNotNull { (protocol, pair) ->
                    val arr = pair.takeIf { it.isJsonArray }?.asJsonArray?.takeIf { it.size() == 2 } ?: return@mapNotNull null
                    val a = arr[0].numOrNull() ?: return@mapNotNull null
                    val b = arr[1].numOrNull() ?: return@mapNotNull null
                    protocol to doubleArrayOf(a, b)
                }?.toMap() ?: return@mapNotNull null
                node to protoMap
            }?.toMap() ?: return@mapNotNull null
            country to nodeMap
        }?.toMap().orEmpty()
        val fit = json.obj("protocol_fit")?.entrySet()?.mapNotNull { (type, protocols) ->
            val map = protocols.asObj()?.entrySet()?.mapNotNull { (p, v) -> v.numOrNull()?.let { p to it } }?.toMap() ?: return@mapNotNull null
            type to map
        }?.toMap().orEmpty()
        val scopes = json.obj("prior_scope")?.entrySet()?.mapNotNull { (country, nodes) ->
            val map = nodes.asObj()?.entrySet()?.mapNotNull { (node, v) ->
                v.asObj()?.get("scope")?.takeIf { it.isJsonPrimitive }?.asString?.let { node to it }
            }?.toMap() ?: return@mapNotNull null
            country to map
        }?.toMap().orEmpty()
        Model(
            version = version,
            generatedAt = json.get("generated_at")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
            country = json.get("country")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
            weights = weights,
            priors = priors,
            protocolFit = fit,
            explore = json.num("explore") ?: 0.0,
            halflifeH = json.num("halflife_h") ?: 0.0,
            latencyCapMs = json.num("latency_cap_ms") ?: 0.0,
            city = json.get("city")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
            priorScope = scopes,
        )
    }.getOrNull()

    private fun JsonElement.asObj(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.asObj()
    private fun JsonElement.numOrNull(): Double? =
        takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
    private fun JsonObject.num(key: String): Double? = get(key)?.numOrNull()
}
