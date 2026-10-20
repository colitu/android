package com.v2ray.ang.colitu.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tencent.mmkv.MMKV
import com.v2ray.ang.colitu.api.ColituApiClient
import com.v2ray.ang.colitu.api.ColituTokenManager

/**
 * Usage statistics: aggregate counters, three histograms and the names of the
 * features used, kept on the device since the last successful send. The payload
 * adds no account, device, IP address or visited site. It is sent over the
 * app's normal signed-in connection (POST /client/metrics) like every request;
 * the server keeps only daily totals, not linked to the account or device
 * (same wording as the setting's text, "metrics.hint", and iOS/the extension).
 * This class is the plain data (unit tested); [ColituMetrics] stores and sends it.
 */
class ColituMetricsData {
    val counters = linkedMapOf<String, Int>()
    val histograms = linkedMapOf<String, IntArray>()
    val features = linkedSetOf<String>()

    /** Adds [n] (may be negative to take back an abandoned attempt) to a known counter, kept within 0..[MAX_COUNT]. */
    fun add(name: String, n: Int = 1) {
        if (name !in COUNTERS || n == 0) return
        val value = ((counters[name] ?: 0).toLong() + n).coerceIn(0L, MAX_COUNT.toLong()).toInt()
        if (value == 0) counters.remove(name) else counters[name] = value
    }

    /** Puts [ms] into the matching bucket of a known histogram. */
    fun observe(name: String, ms: Long) {
        if (name !in HISTOGRAMS || ms < 0) return
        val buckets = histograms.getOrPut(name) { IntArray(BUCKETS) }
        val i = bucketOf(ms)
        buckets[i] = minOf(buckets[i] + 1, MAX_COUNT)
    }

    fun feature(name: String) {
        if (name in FEATURES) features += name
    }

    val isEmpty: Boolean get() = counters.isEmpty() && histograms.isEmpty() && features.isEmpty()

    fun copy(): ColituMetricsData = ColituMetricsData().also { c ->
        c.counters.putAll(counters)
        histograms.forEach { (k, v) -> c.histograms[k] = v.copyOf() }
        c.features.addAll(features)
    }

    fun clear() {
        counters.clear()
        histograms.clear()
        features.clear()
    }

    /** Removes what was sent ([sent] is a [copy] taken before the request), keeping anything recorded since. */
    fun subtract(sent: ColituMetricsData) {
        sent.counters.forEach { (k, v) -> add(k, -v) }
        sent.histograms.forEach { (k, v) ->
            val mine = histograms[k] ?: return@forEach
            for (i in 0 until BUCKETS) mine[i] = (mine[i] - v[i]).coerceAtLeast(0)
            if (mine.all { it == 0 }) histograms.remove(k)
        }
        features.removeAll(sent.features)
    }

    /** The body of POST /client/metrics: non-zero counters and non-empty histograms only. */
    fun toRequest(platform: String, appVersion: String, killSwitch: Boolean): JsonObject = JsonObject().apply {
        addProperty("platform", platform)
        addProperty("app_version", appVersion)
        add("counters", JsonObject().also { o -> counters.forEach { (k, v) -> if (v > 0) o.addProperty(k, minOf(v, MAX_COUNT)) } })
        add(
            "histograms",
            JsonObject().also { o ->
                histograms.forEach { (k, v) ->
                    if (v.any { it > 0 }) o.add(k, JsonArray().also { a -> v.forEach { a.add(minOf(it, MAX_COUNT)) } })
                }
            },
        )
        addProperty("kill_switch", killSwitch)
        add("features", JsonArray().also { a -> features.forEach { a.add(it) } })
    }

    fun toJson(): String = JsonObject().apply {
        add("c", JsonObject().also { o -> counters.forEach { (k, v) -> o.addProperty(k, v) } })
        add("h", JsonObject().also { o -> histograms.forEach { (k, v) -> o.add(k, JsonArray().also { a -> v.forEach { a.add(it) } }) } })
        add("f", JsonArray().also { a -> features.forEach { a.add(it) } })
    }.toString()

    companion object {
        const val MAX_COUNT = 10_000
        const val BUCKETS = 9
        const val PLATFORM = "android"

        /** Upper bounds in ms of the first eight buckets; the ninth is slower than the last. */
        val BOUNDS_MS = longArrayOf(500, 1000, 2000, 3000, 5000, 8000, 15000, 30000)

        val COUNTERS = setOf(
            "connect_attempts", "connect_successes", "first_connect_attempts", "first_connect_successes",
            "sessions", "unexpected_disconnects", "functional_checks", "functional_ok",
            "recoveries", "recovery_successes", "protocol_switches", "protocol_switch_successes", "flaps",
        )
        val HISTOGRAMS = setOf("connect_ms", "recovery_ms", "detection_ms")
        val FEATURES = setOf(
            "split_tunnel", "shield", "ad_block", "advanced_mode", "multihop",
            "auto_connect", "kill_switch", "manual_server", "custom_dns",
        )

        /** The bucket of [ms]: the first bound it does not exceed, else the last (slower) one. */
        fun bucketOf(ms: Long): Int {
            val i = BOUNDS_MS.indexOfFirst { ms <= it }
            return if (i < 0) BUCKETS - 1 else i
        }

        fun fromJson(text: String?): ColituMetricsData {
            val data = ColituMetricsData()
            val root = runCatching { JsonParser.parseString(text.orEmpty()).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull() ?: return data
            runCatching {
                root.getAsJsonObject("c")?.entrySet()?.forEach { (k, v) -> data.add(k, v.asInt) }
                root.getAsJsonObject("h")?.entrySet()?.forEach { (k, v) ->
                    if (k !in HISTOGRAMS) return@forEach
                    val arr = v.asJsonArray
                    val buckets = IntArray(BUCKETS) { i -> if (i < arr.size()) arr[i].asInt.coerceIn(0, MAX_COUNT) else 0 }
                    if (buckets.any { it > 0 }) data.histograms[k] = buckets
                }
                root.getAsJsonArray("f")?.forEach { data.feature(it.asString) }
            }
            return data
        }
    }
}

/** What to do with the stored numbers after the server answered (or did not). */
enum class ColituMetricsOutcome { Reset, Keep }

/**
 * Local store and sender of the usage statistics (default on; off in the
 * F-Droid build, see COLITU_OPT_IN_REPORTING). With the
 * setting off nothing is collected or sent and the stored numbers are gone.
 * Sent at most every [SEND_INTERVAL_MS] while signed in and there is data;
 * never blocks the UI (the controller calls it from a coroutine).
 */
object ColituMetrics {
    const val SEND_INTERVAL_MS = 6L * 60 * 60 * 1000

    private const val KEY_ENABLED = "metrics_enabled"
    private val DEFAULT_ENABLED = !com.v2ray.ang.BuildConfig.COLITU_OPT_IN_REPORTING
    private const val KEY_DATA = "metrics_data"
    private const val KEY_LAST_ATTEMPT = "metrics_last_attempt"
    private const val KEY_FIRST_DONE = "metrics_first_connect_done"

    private val store by lazy { MMKV.mmkvWithID("COLITU_SETTINGS", MMKV.MULTI_PROCESS_MODE) }
    private val lock = Any()
    private var data: ColituMetricsData? = null

    var enabled: Boolean
        get() = runCatching { store.decodeBool(KEY_ENABLED, DEFAULT_ENABLED) }.getOrDefault(DEFAULT_ENABLED)
        set(value) {
            runCatching { store.encode(KEY_ENABLED, value) }
            if (!value) synchronized(lock) {
                data = ColituMetricsData()
                runCatching { store.removeValueForKey(KEY_DATA) }
            }
            if (!value) ColituAcEvents.clear()
        }

    private fun current(): ColituMetricsData = data ?: ColituMetricsData.fromJson(runCatching { store.decodeString(KEY_DATA) }.getOrNull()).also { data = it }

    private fun mutate(block: (ColituMetricsData) -> Unit) {
        if (!enabled) return
        synchronized(lock) {
            val d = current()
            block(d)
            runCatching { store.encode(KEY_DATA, d.toJson()) }
        }
    }

    fun count(name: String, n: Int = 1) = mutate { it.add(name, n) }
    fun observe(name: String, ms: Long) = mutate { it.observe(name, ms) }
    fun features(names: Collection<String>) = mutate { d -> names.forEach { d.feature(it) } }

    /** True until the first connection attempt of this install has had its result. */
    fun isFirstConnect(): Boolean = !runCatching { store.decodeBool(KEY_FIRST_DONE, false) }.getOrDefault(true)
    fun markFirstDone() { runCatching { store.encode(KEY_FIRST_DONE, true) } }

    /** 2xx resets; 400 (bad data) also drops it; 429, 5xx, no answer or anything else keeps it for the next time. */
    fun outcomeFor(status: Int?): ColituMetricsOutcome = when (status) {
        in 200..299, 400 -> ColituMetricsOutcome.Reset
        else -> ColituMetricsOutcome.Keep
    }

    /** True when a report is due: enabled, signed in, something to send and 6 hours since the last try. */
    internal fun due(enabled: Boolean, signedIn: Boolean, hasData: Boolean, lastAttemptMs: Long, nowMs: Long): Boolean =
        enabled && signedIn && hasData && (lastAttemptMs <= 0L || nowMs - lastAttemptMs >= SEND_INTERVAL_MS || nowMs < lastAttemptMs)

    /** Sends the report when it is due. Safe to call often. [killSwitch]: Always-on with "block without VPN" is on. */
    suspend fun maybeSend(appVersion: String, killSwitch: () -> Boolean) {
        val now = System.currentTimeMillis()
        val snapshot = synchronized(lock) { current().copy() }
        val last = runCatching { store.decodeLong(KEY_LAST_ATTEMPT, 0L) }.getOrDefault(0L)
        if (!due(enabled, ColituTokenManager.isLoggedIn(), !snapshot.isEmpty, last, now)) return
        runCatching { store.encode(KEY_LAST_ATTEMPT, now) }
        val kill = runCatching { killSwitch() }.getOrDefault(false)
        if (kill) snapshot.feature("kill_switch")
        val body = snapshot.toRequest(ColituMetricsData.PLATFORM, appVersion, kill)
        val status = when (val result = runCatching { ColituApiClient.post("/client/metrics", body) }.getOrNull()) {
            is ColituApiClient.ApiResult.Success -> result.statusCode
            is ColituApiClient.ApiResult.Error -> result.code
            null -> null
        }
        if (outcomeFor(status) == ColituMetricsOutcome.Reset) {
            synchronized(lock) {
                val d = current()
                d.subtract(snapshot)
                runCatching { store.encode(KEY_DATA, d.toJson()) }
            }
        }
    }
}

/** Pure rules the controller uses for the statistics (unit tested). */
object ColituMetricsRules {
    const val FLAP_WINDOW_MS = 2L * 60 * 1000

    /**
     * A flap: the switch from [from] to [to] undoes the previous successful one
     * (to -> from) less than two minutes ago. [last] is (from, to, time) of that one.
     */
    fun isFlap(last: Triple<String, String, Long>?, from: String, to: String, nowMs: Long): Boolean =
        last != null && last.first == to && last.second == from && nowMs - last.third in 0..FLAP_WINDOW_MS
}
