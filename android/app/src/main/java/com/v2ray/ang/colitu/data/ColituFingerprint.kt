package com.v2ray.ang.colitu.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Learned uTLS fingerprint per network (spec FP-SPEC sections 1-6). Pure: the choice, the retry rule, the policy
 * parse and the per-network state with its 24 h failure memory. The controller persists [State.toJson] and applies
 * the choice in [XrayMobileAdapter.withFingerprint].
 */
object ColituFingerprint {
    /** uTLS names Xray accepts; unknown names in a policy are ignored. */
    val NAMES = setOf("chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized")

    /** Transports that take a fingerprint (Hysteria2 and Shadowsocks have none). */
    val TLS_TRANSPORTS = setOf("vless-reality", "vless-xhttp", "trojan", "vless-ws")

    const val DEFAULT_SPREAD = 3
    const val FAIL_MEMORY_MS = 24L * 60 * 60 * 1000
    const val MAX_NETWORKS = 8
    const val MAX_NAMES = 10
    const val MAX_RETRIES_PER_CONNECT = 2

    /** `fingerprint_policy` of GET /servers. */
    data class Policy(val order: List<String>, val blocked: Set<String>, val spread: Int, val scope: String = "") {
        fun toJson(): JsonObject = JsonObject().apply {
            add("order", JsonArray().also { a -> order.forEach(a::add) })
            add("blocked", JsonArray().also { a -> blocked.forEach(a::add) })
            addProperty("spread", spread)
            addProperty("scope", scope)
        }
    }

    /** Null when the panel sent none (an old panel: the endpoint's own fingerprint stays). */
    fun parsePolicy(json: JsonObject?): Policy? {
        if (json == null) return null
        fun names(key: String): List<String> = json.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.trim()?.lowercase()?.takeIf { it in NAMES } }.orEmpty()
        val spread = json.get("spread")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt?.takeIf { it >= 1 } ?: DEFAULT_SPREAD
        val scope = json.get("scope")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        return Policy(names("order").distinct(), names("blocked").toSet(), spread, scope)
    }

    fun fnv1a32(text: String): Long {
        var h = 2166136261L
        for (b in text.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xff)
            h = (h * 16777619L) and 0xffffffffL
        }
        return h
    }

    /** Outcome memory of one fingerprint on one network (epoch ms; 0 = never). */
    data class Entry(val okAt: Long = 0, val failAt: Long = 0, val fails: Int = 0) {
        /** Failed on a TLS transport less than 24 h ago and not succeeded since. */
        fun failed(now: Long): Boolean = failAt > 0 && failAt >= okAt && now - failAt in 0 until FAIL_MEMORY_MS
        fun worked(now: Long): Boolean = okAt > 0 && !failed(now)
    }

    /** The fingerprint for this network; "" when the policy has no usable name (the endpoint's own stays). */
    fun choose(policy: Policy, deviceId: String, network: String, state: Map<String, Entry>, now: Long): String {
        val candidates = policy.order.filter { it in NAMES && it !in policy.blocked }
        if (candidates.isEmpty()) return ""
        candidates.firstOrNull { state[it]?.worked(now) == true }?.let { return it }
        val working = candidates.filter { state[it]?.failed(now) != true }.ifEmpty { candidates }
        val pool = working.take(maxOf(1, policy.spread))
        return pool[(fnv1a32("$deviceId|$network") % pool.size).toInt()]
    }

    /**
     * Section 5: retry the same transport on the same server with the next fingerprint. [probe] is the TLS probe
     * result of the server ("ok", "tls_rst", ...) or null without probe data.
     */
    fun shouldRetry(transport: String?, tcpOk: Boolean, probe: String?, retriesUsed: Int): Boolean =
        transport in TLS_TRANSPORTS && tcpOk && (probe == null || probe == "ok") && retriesUsed < MAX_RETRIES_PER_CONNECT

    /** Per-network fingerprint memory, at most [MAX_NETWORKS] networks of [MAX_NAMES] names. Not thread safe: guard outside. */
    class State(private val nets: LinkedHashMap<String, LinkedHashMap<String, Entry>> = LinkedHashMap()) {
        fun entries(network: String): Map<String, Entry> = nets[network].orEmpty()

        fun markOk(network: String, fp: String, now: Long) {
            val old = entries(network)[fp]
            put(network, fp, Entry(okAt = now, failAt = old?.failAt ?: 0, fails = 0))
        }

        fun markFailed(network: String, fp: String, now: Long) {
            val old = entries(network)[fp]
            put(network, fp, Entry(okAt = old?.okAt ?: 0, failAt = now, fails = (old?.fails ?: 0) + 1))
        }

        private fun put(network: String, fp: String, entry: Entry) {
            if (network.isBlank() || fp !in NAMES) return
            val names = nets.remove(network) ?: LinkedHashMap()
            names.remove(fp)
            names[fp] = entry
            while (names.size > MAX_NAMES) names.remove(names.keys.first())
            nets[network] = names // most recently touched last
            while (nets.size > MAX_NETWORKS) nets.remove(nets.keys.first())
        }

        fun toJson(): String = JsonObject().also { root ->
            nets.forEach { (net, names) ->
                root.add(net, JsonObject().also { o ->
                    names.forEach { (fp, e) -> o.add(fp, JsonArray().also { it.add(e.okAt); it.add(e.failAt); it.add(e.fails) }) }
                })
            }
        }.toString()

        companion object {
            fun fromJson(raw: String?): State {
                val state = State()
                val root = runCatching { com.google.gson.JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return state
                root.entrySet().forEach { (net, v) ->
                    val o = v.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                    o.entrySet().forEach { (fp, a) ->
                        val arr = a.takeIf { it.isJsonArray }?.asJsonArray ?: return@forEach
                        if (arr.size() < 3) return@forEach
                        runCatching { state.put(net, fp, Entry(arr[0].asLong, arr[1].asLong, arr[2].asInt)) }
                    }
                }
                return state
            }
        }
    }
}
