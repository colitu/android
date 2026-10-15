package com.v2ray.ang.colitu.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tencent.mmkv.MMKV
import com.v2ray.ang.colitu.api.ColituApiClient
import com.v2ray.ang.colitu.api.ColituTokenManager

/**
 * Adaptive Connect telemetry: one small event per connect, recovery, protocol
 * switch, drop, latency probe and automatic choice, queued on the device and
 * sent at most hourly to POST /client/ac-events. Only collected while the
 * usage-statistics switch is on (the same switch as the counters). Events name
 * nodes (UUIDs of the servers list) and protocols, with times and the
 * network's country and provider as the signed network_token (and the network
 * type); the payload adds no address or visited site. Like the counters it is
 * sent over the app's signed-in connection; the server keeps only daily
 * totals, not linked to the account or device ("metrics.hint" says the same).
 */
object ColituAcEvents {
    const val MAX_QUEUE = 1000
    const val MAX_BATCH = 300
    const val MAX_MS = 600_000L
    const val SEND_INTERVAL_MS = 60L * 60 * 1000
    const val PROBE_WINDOW_MS = 60L * 60 * 1000
    const val FLAP_WINDOW_MS = 2L * 60 * 1000

    val PROTOCOLS = setOf("vless-reality", "vless-xhttp", "hysteria2", "trojan", "shadowsocks", "tuic")
    val REASONS = setOf(
        "timeout", "reset", "tls", "dns", "refused", "handshake", "blocked", "no_network",
        "core_crash", "auth", "quota", "network_change", "other",
    )
    private val NETWORK_TYPES = setOf("wifi", "cellular", "ethernet", "other")

    // ── Event builders (null when a required field is missing or invalid) ───────

    private fun node(id: String?): String? = id?.trim()?.lowercase()?.takeIf { it.length in 8..64 }
    private fun protocol(p: String?): String? = p?.trim()?.lowercase()?.takeIf { it in PROTOCOLS }
    private fun clampMs(ms: Long): Long = ms.coerceIn(0L, MAX_MS)
    private fun outcome(ok: Boolean) = if (ok) "ok" else "fail"

    /**
     * Failure code of the app (the strings of `colituErrorMessage` / `fail()`) to the telemetry reason:
     * TIMEOUT, TUNNEL_TIMEOUT, UNREACHABLE (nothing came up in time) -> timeout; IO_ERROR -> reset;
     * NETWORK_ERROR (phone offline) -> no_network; VERIFY_FAILED (a transport came up but carried
     * nothing: DPI freeze or throttling) -> blocked; ENGINE_FAILED, TUN_FAILED -> core_crash;
     * AUTH_EXPIRED, UNAUTHORIZED, DEVICE_*, ENTITLEMENT_*, PREMIUM_REQUIRED, SERVER_NOT_ALLOWED -> auth;
     * QUOTA_EXCEEDED, FREE_DAILY_LIMIT_REACHED -> quota; NETWORK_CHANGE -> network_change;
     * a code that already is a reason (dns, tls, refused, handshake, reset, ...) is kept;
     * everything else (panel errors, missing config, no servers) -> other.
     */
    fun reasonOf(code: String?): String {
        val raw = code.orEmpty().trim().lowercase()
        if (raw in REASONS) return raw
        return when (raw.uppercase()) {
            "TIMEOUT", "TUNNEL_TIMEOUT", "UNREACHABLE" -> "timeout"
            "IO_ERROR" -> "reset"
            "NETWORK_ERROR" -> "no_network"
            "VERIFY_FAILED" -> "blocked"
            "ENGINE_FAILED", "TUN_FAILED" -> "core_crash"
            "AUTH_EXPIRED", "UNAUTHORIZED", "401", "DEVICE_REVOKED", "USER_DISABLED", "DEVICE_OVER_LIMIT",
            "ENTITLEMENT_INACTIVE", "ENTITLEMENT_EXPIRED", "PREMIUM_REQUIRED", "SERVER_NOT_ALLOWED" -> "auth"
            "QUOTA_EXCEEDED", "FREE_DAILY_LIMIT_REACHED" -> "quota"
            "NETWORK_CHANGE" -> "network_change"
            else -> "other"
        }
    }

    fun connect(nodeId: String?, protocol: String?, ok: Boolean, code: String?, ms: Long, firstTry: Boolean, auto: Boolean): JsonObject? {
        val n = node(nodeId) ?: return null
        val p = protocol(protocol) ?: return null
        return JsonObject().apply {
            addProperty("kind", "connect")
            addProperty("node_id", n)
            addProperty("protocol", p)
            addProperty("outcome", outcome(ok))
            if (!ok) addProperty("reason", reasonOf(code))
            addProperty("ms", clampMs(ms))
            addProperty("first_try", firstTry)
            addProperty("mode", if (auto) "auto" else "manual")
        }
    }

    fun recovery(nodeId: String?, protocol: String?, toProtocol: String?, ok: Boolean, code: String?, ms: Long, detectMs: Long?, spare: Boolean): JsonObject? {
        val n = node(nodeId) ?: return null
        val p = protocol(protocol) ?: return null
        return JsonObject().apply {
            addProperty("kind", "recovery")
            addProperty("node_id", n)
            addProperty("protocol", p)
            protocol(toProtocol)?.let { addProperty("to_protocol", it) }
            addProperty("outcome", outcome(ok))
            if (!ok) addProperty("reason", reasonOf(code))
            addProperty("ms", clampMs(ms))
            detectMs?.let { addProperty("detect_ms", clampMs(it)) }
            addProperty("spare", spare)
        }
    }

    fun switch(from: String?, to: String?, ok: Boolean, flap: Boolean): JsonObject? {
        val p = protocol(from) ?: return null
        // The panel needs both ends and refuses a whole report over one invalid event: a
        // recovery that reached no protocol is told by its recovery event alone.
        val target = protocol(to) ?: return null
        return JsonObject().apply {
            addProperty("kind", "switch")
            addProperty("protocol", p)
            addProperty("to_protocol", target)
            addProperty("outcome", outcome(ok))
            addProperty("flap", flap)
        }
    }

    fun drop(nodeId: String?, protocol: String?, code: String?, minutes: Long): JsonObject? {
        val n = node(nodeId) ?: return null
        val p = protocol(protocol) ?: return null
        return JsonObject().apply {
            addProperty("kind", "drop")
            addProperty("node_id", n)
            addProperty("protocol", p)
            addProperty("reason", reasonOf(code))
            addProperty("minutes", minutes.coerceIn(0L, 100_000L))
        }
    }

    /** A latency measurement; a failed probe carries no time. */
    fun probe(nodeId: String?, ms: Int?): JsonObject? {
        val n = node(nodeId) ?: return null
        return JsonObject().apply {
            addProperty("kind", "probe")
            addProperty("node_id", n)
            addProperty("outcome", if (ms != null) "ok" else "fail")
            if (ms != null) addProperty("ms", clampMs(ms.toLong()))
        }
    }

    /**
     * An automatic choice: the node picked, its measured ping, and the node with the lowest
     * measured ping among [candidates] (id to fresh ping, null = none). Null when the chosen
     * node has no measurement or nothing was measured, so best_ms <= ms always holds.
     */
    fun choice(chosenId: String?, candidates: Map<String, Int?>): JsonObject? {
        val chosen = node(chosenId) ?: return null
        val ms = candidates[chosenId]?.takeIf { it >= 0 } ?: return null
        val best = candidates.entries.filter { it.value != null && it.value!! >= 0 }.minByOrNull { it.value!! } ?: return null
        val bestMs = minOf(best.value!!, ms)
        val bestId = if (best.value!! <= ms) best.key else chosenId!!
        return JsonObject().apply {
            addProperty("kind", "choice")
            addProperty("node_id", chosen)
            addProperty("ms", clampMs(ms.toLong()))
            addProperty("best_node_id", node(bestId) ?: chosen)
            addProperty("best_ms", clampMs(bestMs.toLong()))
            addProperty("mode", "auto")
        }
    }

    /** The link type for the request: wifi / cellular / ethernet / other, else empty (none, unknown). */
    fun networkTypeOf(link: String?): String = link?.trim()?.lowercase()?.takeIf { it in NETWORK_TYPES } ?: ""

    // ── Queue ───────────────────────────────────────────────────────────────────

    /** One queued event with the network it happened on and when. */
    class Entry(val event: JsonObject, val token: String, val type: String, val at: Long)

    /** FIFO queue capped at [cap]; the oldest events are dropped first. Pure, so it is unit tested. */
    class Queue(val cap: Int = MAX_QUEUE) {
        val entries = ArrayList<Entry>()

        fun add(event: JsonObject, token: String, type: String, at: Long) {
            // At most the last probe of a node per hour.
            if (event.get("kind")?.asString == "probe") {
                val id = event.get("node_id")?.asString
                entries.removeAll { it.event.get("kind")?.asString == "probe" && it.event.get("node_id")?.asString == id && at - it.at in 0..PROBE_WINDOW_MS }
            }
            entries += Entry(event, token, type, at)
            while (entries.size > cap) entries.removeAt(0)
        }

        val isEmpty: Boolean get() = entries.isEmpty()

        /** The next request: the oldest event's network, up to [MAX_BATCH] events of that network in order. */
        fun nextBatch(): List<Entry> {
            val first = entries.firstOrNull() ?: return emptyList()
            return entries.filter { it.token == first.token && it.type == first.type }.take(MAX_BATCH)
        }

        fun remove(sent: Collection<Entry>) {
            val ids = sent.toSet()
            entries.removeAll { it in ids }
        }

        fun toJson(): String = JsonArray().also { a ->
            entries.forEach { e ->
                a.add(JsonObject().apply {
                    add("e", e.event)
                    addProperty("t", e.token)
                    addProperty("n", e.type)
                    addProperty("a", e.at)
                })
            }
        }.toString()

        companion object {
            fun fromJson(text: String?, cap: Int = MAX_QUEUE): Queue {
                val q = Queue(cap)
                runCatching {
                    JsonParser.parseString(text.orEmpty()).takeIf { it.isJsonArray }?.asJsonArray?.forEach { item ->
                        val o = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                        val event = o.get("e")?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                        q.entries += Entry(event, o.get("t")?.asString.orEmpty(), o.get("n")?.asString.orEmpty(), o.get("a")?.asLong ?: 0L)
                    }
                }
                while (q.entries.size > cap) q.entries.removeAt(0)
                return q
            }
        }
    }

    /** The body of POST /client/ac-events for [batch] (all of one network). */
    fun requestBody(appVersion: String, token: String, type: String, batch: List<Entry>): JsonObject = JsonObject().apply {
        addProperty("platform", "android")
        addProperty("app_version", appVersion)
        addProperty("network_token", token)
        addProperty("network_type", networkTypeOf(type))
        add("events", JsonArray().also { a -> batch.take(MAX_BATCH).forEach { a.add(it.event) } })
    }

    /** 2xx clears what was sent; 400 drops it too (bad data); 429, 5xx, no answer: keep for next time. */
    fun outcomeFor(status: Int?): ColituMetricsOutcome = ColituMetrics.outcomeFor(status)

    fun due(enabled: Boolean, signedIn: Boolean, hasEvents: Boolean, lastAttemptMs: Long, nowMs: Long): Boolean =
        enabled && signedIn && hasEvents && (lastAttemptMs <= 0L || nowMs - lastAttemptMs >= SEND_INTERVAL_MS || nowMs < lastAttemptMs)

    // ── Storage and sending ─────────────────────────────────────────────────────

    private const val KEY_QUEUE = "ac_events"
    private const val KEY_LAST_ATTEMPT = "ac_events_last_attempt"
    private val store by lazy { MMKV.mmkvWithID("COLITU_SETTINGS", MMKV.MULTI_PROCESS_MODE) }
    private val lock = Any()
    private var queue: Queue? = null

    private fun current(): Queue = queue ?: Queue.fromJson(runCatching { store.decodeString(KEY_QUEUE) }.getOrNull()).also { queue = it }
    private fun persist(q: Queue) { runCatching { store.encode(KEY_QUEUE, q.toJson()) } }

    private const val KEY_JOURNAL = "connection_journal"
    private const val JOURNAL_LINES = 300

    /** One journal line: local time and the event's fields ("connect outcome=fail reason=tls …"). */
    fun journalLine(event: JsonObject, type: String, nowMs: Long): String {
        val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(nowMs))
        val fields = event.entrySet().filter { it.key != "kind" && !it.value.isJsonNull && it.value.toString() != "\"\"" }
            .joinToString(" ") { "${it.key}=${if (it.value.isJsonPrimitive) it.value.asString else it.value}" }
        val net = networkTypeOf(type).ifEmpty { "?" }
        return "$time ${event.get("kind")?.asString ?: "?"} net=$net $fields".trimEnd()
    }

    /**
     * The last connection events on this device (newest last), kept locally
     * for the support connection report; never sent on their own and kept
     * whatever the statistics switch says.
     */
    fun journal(): List<String> = synchronized(lock) {
        runCatching { store.decodeString(KEY_JOURNAL) }.getOrNull().orEmpty().lines().filter { it.isNotBlank() }
    }

    private fun appendJournal(event: JsonObject, type: String, nowMs: Long) {
        // Server ping rounds (one line per server) and the automatic choice
        // would push the connection events out of the journal.
        if (event.get("kind")?.asString in setOf("probe", "choice")) return
        synchronized(lock) {
            val lines = journal() + journalLine(event, type, nowMs)
            runCatching { store.encode(KEY_JOURNAL, lines.takeLast(JOURNAL_LINES).joinToString("\n")) }
        }
    }

    /** Queues [event] (null is ignored) unless the statistics switch is off; the local journal keeps it either way. */
    fun record(event: JsonObject?, token: String, type: String, nowMs: Long = System.currentTimeMillis()) {
        if (event == null) return
        appendJournal(event, type, nowMs)
        if (!ColituMetrics.enabled) return
        synchronized(lock) {
            val q = current()
            q.add(event, token, networkTypeOf(type), nowMs)
            persist(q)
        }
    }

    /** Forgets the local journal (sign-out or another account). */
    fun clearJournal() {
        synchronized(lock) { runCatching { store.removeValueForKey(KEY_JOURNAL) } }
    }

    /** Forgets every queued event (the statistics switch was turned off). */
    fun clear() {
        synchronized(lock) {
            queue = Queue()
            runCatching { store.removeValueForKey(KEY_QUEUE) }
        }
    }

    /** Sends the queued events when due (hourly). Safe to call often; never throws. */
    suspend fun maybeSend(appVersion: String) {
        val now = System.currentTimeMillis()
        val hasEvents = synchronized(lock) { !current().isEmpty }
        val last = runCatching { store.decodeLong(KEY_LAST_ATTEMPT, 0L) }.getOrDefault(0L)
        if (!due(ColituMetrics.enabled, ColituTokenManager.isLoggedIn(), hasEvents, last, now)) return
        runCatching { store.encode(KEY_LAST_ATTEMPT, now) }
        // One request per network, a few at most per run; the rest follows the next hour.
        repeat(3) {
            val batch = synchronized(lock) { current().nextBatch() }
            if (batch.isEmpty()) return
            val body = requestBody(appVersion, batch.first().token, batch.first().type, batch)
            val status = when (val result = runCatching { ColituApiClient.post("/client/ac-events", body) }.getOrNull()) {
                is ColituApiClient.ApiResult.Success -> result.statusCode
                is ColituApiClient.ApiResult.Error -> result.code
                null -> null
            }
            if (outcomeFor(status) != ColituMetricsOutcome.Reset) return
            synchronized(lock) {
                val q = current()
                q.remove(batch)
                persist(q)
            }
        }
    }
}
