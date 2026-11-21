package com.v2ray.ang.colitu.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.delay

/** One grip failure as the panel takes it (GECKO-G4-CONTRACTS.md §3). */
data class ColituGeckoReport(
    val entry: String, val skin: String, val rev: String, val cls: String, val bucket: String?, val at: Long,
    val rtt: String? = null, val rate: String? = null, val life: String? = null, val app: String? = null,
) {
    // The entry id names a server of the fleet: never in a log line.
    override fun toString() = "grip report (class $cls, bucket $bucket)"
}

/**
 * The slice labels of the contract (section 3): the value is cut into a bucket on the device and only the label
 * leaves it. Each bucket holds its lower bound and excludes its upper one ("50-100" is 50 up to, not
 * including, 100); a value that is negative or not a number has no label.
 */
object ColituGeckoBuckets {
    val RTT_LABELS = listOf("<50", "50-100", "100-200", "200-400", ">400")
    val RATE_LABELS = listOf("<1", "1-5", "5-20", "20-100", ">100")
    val LIFE_LABELS = listOf("<10s", "10-60s", "1-10m", ">10m")

    /** Round-trip time in milliseconds. */
    fun rtt(ms: Double): String? = pick(ms, doubleArrayOf(50.0, 100.0, 200.0, 400.0), RTT_LABELS)

    /** Rate in Mbit/s. */
    fun rate(mbit: Double): String? = pick(mbit, doubleArrayOf(1.0, 5.0, 20.0, 100.0), RATE_LABELS)

    /** A grip's lifetime in milliseconds. */
    fun life(ms: Long): String? = pick(ms / 1000.0, doubleArrayOf(10.0, 60.0, 600.0), LIFE_LABELS)

    /** Mbit/s of [bytes] carried in [durationMs]; null when either is unusable. */
    fun mbitPerSecond(bytes: Long, durationMs: Long): Double? =
        if (bytes < 0 || durationMs <= 0) null else bytes * 8.0 / 1_000_000.0 / (durationMs / 1000.0)

    /** The app version as the contract takes it: 1 to 32 characters of 0-9 A-Z a-z . + -. */
    fun app(version: String?): String? =
        version?.takeIf { it.length in 1..32 && it.all { c -> c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z' || c == '.' || c == '+' || c == '-' } }

    private fun pick(value: Double, upperBounds: DoubleArray, labels: List<String>): String? {
        if (value.isNaN() || value < 0) return null
        upperBounds.forEachIndexed { i, bound -> if (value < bound) return labels[i] }
        return labels.last()
    }
}

/**
 * The grip failures gecko-client reports, kept in memory and sent to the
 * panel in batches (`POST /gecko/reports`): at most every 60 s, at most 100
 * per call, at most 300 waiting (the oldest go first when more arrive).
 * Nothing is written to disk or to the log: the entry id names a server of
 * the fleet. A 404 (Gecko not offered) stops sending until the next offer, a
 * 429 pauses for ten minutes, a 400 drops the batch, any other failure keeps
 * it for the next round. Same rules as the Windows app.
 */
class ColituGeckoReportQueue(private val app: String? = ColituGeckoBuckets.app(com.v2ray.ang.BuildConfig.VERSION_NAME)) {
    private val lock = Any()
    private val lastOk = HashMap<String, Long>()
    private val waiting = ArrayDeque<ColituGeckoReport>()
    private var notBefore = Long.MIN_VALUE
    private var off = false

    val size: Int get() = synchronized(lock) { waiting.size }

    /** Takes a parsed `grip` event ([nowMs] is the app's clock); anything else, or one without the fields, is ignored. */
    fun add(event: ColituGeckoEvent, nowMs: Long): Boolean {
        if (event.kind != "grip") return false
        val report = ColituGeckoReport(
            entry = event.entry ?: return false,
            skin = event.skin ?: return false,
            rev = event.rev ?: return false,
            cls = event.cls ?: return false,
            bucket = event.bucket ?: return false,
            at = nowMs / 1000,
            app = app,
        )
        synchronized(lock) {
            waiting.addLast(report)
            while (waiting.size > MAX_WAITING) waiting.removeFirst()
        }
        return true
    }

    /**
     * Takes a working grip ("ok"); false when this entry already had one in the last [OK_INTERVAL_MS].
     * The numbers are cut into labels here and dropped; a value that is not known is left out.
     */
    fun addOk(
        entry: String, skin: String, rev: String, nowMs: Long,
        rttMs: Double? = null, rateMbit: Double? = null, lifeMs: Long? = null, bucket: String? = null,
    ): Boolean {
        synchronized(lock) {
            val last = lastOk[entry]
            if (last != null && nowMs >= last && nowMs - last < OK_INTERVAL_MS) return false
            lastOk[entry] = nowMs
            waiting.addLast(
                ColituGeckoReport(
                    entry, skin, rev, "ok", bucket, nowMs / 1000,
                    rtt = rttMs?.let { ColituGeckoBuckets.rtt(it) },
                    rate = rateMbit?.let { ColituGeckoBuckets.rate(it) },
                    life = lifeMs?.let { ColituGeckoBuckets.life(it) },
                    app = app,
                ),
            )
            while (waiting.size > MAX_WAITING) waiting.removeFirst()
        }
        return true
    }

    /**
     * Takes a parsed `grip_end` event. Only class "ok" counts (a failure was already reported by its `grip`
     * event); the exact figures are cut into labels here: rtt when measured (> 0), rate from the larger of
     * received and sent bytes over the lifetime (at least 1 s), life from the lifetime.
     */
    fun addEnd(event: ColituGeckoEvent, nowMs: Long): Boolean {
        if (event.kind != "grip_end" || event.cls != "ok") return false
        val entry = event.entry ?: return false
        val skin = event.skin ?: return false
        val rev = event.rev ?: return false
        val rtt = event.rttMs?.takeIf { it > 0 }?.toDouble()
        val life = event.lifeMs
        // The busier direction over the lifetime. "goodput" is the anchor's reading of what we sent, so it says nothing
        // about a download and is not used.
        val bytes = maxOf(event.received ?: 0L, event.sent ?: 0L)
        val rate = if (life != null && life >= 1000 && bytes > 0) bytes * 8.0 / (life / 1000.0) / 1_000_000.0 else null
        return addOk(entry, skin, rev, nowMs, rttMs = rtt, rateMbit = rate, lifeMs = life?.takeIf { it >= 0 })
    }

    /** Sending may resume: the panel offered Gecko again. */
    fun resume() {
        synchronized(lock) { off = false }
    }

    /**
     * Sends one batch if one is due. [post] sends a body and returns the HTTP
     * status (-1 or a throw for a network failure). Returns the number of
     * reports the panel took.
     */
    suspend fun flush(post: suspend (String) -> Int, nowMs: Long): Int {
        val batch: List<ColituGeckoReport>
        synchronized(lock) {
            if (off || nowMs < notBefore || waiting.isEmpty()) return 0
            batch = waiting.take(MAX_PER_CALL)
            repeat(batch.size) { waiting.removeFirst() }
            notBefore = nowMs + INTERVAL_MS
        }
        val status = runCatching { post(body(batch)) }.getOrDefault(-1)
        synchronized(lock) {
            when (status) {
                200, 204 -> return batch.size
                404 -> {
                    off = true
                    waiting.clear()
                    return 0
                }
                // The panel will not take this batch however often it comes.
                400 -> return 0
                429 -> notBefore = nowMs + PAUSE_AFTER_TOO_MANY_MS
            }
            // Back to the front, in order, within the cap.
            batch.asReversed().forEach { waiting.addFirst(it) }
            while (waiting.size > MAX_WAITING) waiting.removeFirst()
            return 0
        }
    }

    /**
     * Sends what is waiting every [tickMs] (a batch at most every 60 s) while
     * [running] holds, and once more when it stops (a session that ends on a
     * failure is when reports matter most). Never throws.
     */
    suspend fun pump(running: () -> Boolean, post: suspend (String) -> Int, tickMs: Long = 5_000L, clock: () -> Long = System::currentTimeMillis) {
        try {
            while (running()) {
                delay(tickMs)
                flush(post, clock())
            }
            synchronized(lock) { notBefore = Long.MIN_VALUE }
            flush(post, clock())
        } catch (_: Exception) {
            // Reports are best effort; they never take the connection with them.
        }
    }

    companion object {
        const val MAX_WAITING = 300
        const val MAX_PER_CALL = 100
        const val INTERVAL_MS = 60_000L
        const val PAUSE_AFTER_TOO_MANY_MS = 10 * 60_000L

        /** At most one "ok" report per entry in this time, so working grips do not flood the queue. */
        const val OK_INTERVAL_MS = 10 * 60_000L

        /** The request body for a batch: `{"v":1,"reports":[…]}`, `rev` as a number when it is one. */
        fun body(batch: List<ColituGeckoReport>): String = JsonObject().apply {
            addProperty("v", 1)
            add("reports", JsonArray().apply {
                batch.forEach { r ->
                    add(JsonObject().apply {
                        addProperty("entry", r.entry)
                        addProperty("skin", r.skin)
                        r.rev.toLongOrNull()?.let { addProperty("rev", it) } ?: addProperty("rev", r.rev)
                        addProperty("class", r.cls)
                        r.bucket?.let { addProperty("bucket", it) }
                        addProperty("at", r.at)
                        r.rtt?.let { addProperty("rtt", it) }
                        r.rate?.let { addProperty("rate", it) }
                        r.life?.let { addProperty("life", it) }
                        r.app?.let { addProperty("app", it) }
                    })
                }
            })
        }.toString()
    }
}

/** The VPN process's queue (one gecko-client at a time). */
val ColituGeckoReports = ColituGeckoReportQueue()
