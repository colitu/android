package com.v2ray.ang.colitu.data

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant

/** One weekly expedition goal as the server counts it. */
data class PassportWeekGoal(val id: String, val target: Int, val progress: Int, val done: Boolean)

/**
 * The passport as GET /client/passport returns it. The server is the only
 * source of truth (it counts the minutes from node traffic); the app keeps
 * the last answer as a cache for offline display.
 */
data class PassportState(
    val collectorNo: String,
    val nickname: String,
    val titleId: String?,
    val coverId: String?,
    val tzOffsetMin: Int?,
    val tester: Boolean,
    val premium: Boolean,
    val chapter2Open: Boolean,
    val companion: Boolean,
    val todayDay: String,
    val todayMinutes: Int,
    val todayPieces: Int,
    val nextPieceAtMinute: Int?,
    val runCountry: String?,
    val runMinutes: Int,
    /** Country code → progress; unlocked stamps only. */
    val stamps: Map<String, StampProgress>,
    /** Achievement id → earned at (epoch ms, 0 when unknown). */
    val achievements: Map<String, Long>,
    /** Collection id → earned at (epoch ms, 0 when unknown). */
    val collections: Map<String, Long>,
    val covers: List<String>,
    val week: String,
    val goals: List<PassportWeekGoal>,
    val expeditionsDone: Int,
    val unseenEvents: Int,
    val updatedAt: Long,
)

/**
 * One server event: its id (for the ack) and the reveal it plays. [event] is
 * null for kinds without a reveal: an admin taking something away
 * ([revoke], acknowledged with one short notice per batch) or a kind this
 * version does not know (acknowledged silently).
 */
data class PassportServerEvent(val id: Long, val event: StampEvent?, val revoke: Boolean = false)

/** What the passport's "next" line shows. */
sealed interface PassportGoalPick {
    /** No server state yet, or no "today" in it: a neutral line, never "collected". */
    data object Loading : PassportGoalPick
    /** No stamp yet. */
    data object First : PassportGoalPick
    /** [left] minutes to today's next piece (at minute [at]). */
    data class Today(val left: Int, val at: Int, val minutes: Int) : PassportGoalPick
    /** The stamp closest to its next level. */
    data class Level(val code: String) : PassportGoalPick
    /** Today's pieces are collected and no stamp is left to level up. */
    data object Collected : PassportGoalPick
}

/** What a v1 (on-device) passport held, sent once per device to POST /client/passport/import. */
data class PassportV1Snapshot(
    /** Country code → pieces and unlock time (epoch ms). */
    val stamps: Map<String, Pair<Int, Long>>,
    val achievements: List<String>,
    val collections: List<String>,
    val expeditionsDone: Int,
    val companion: Boolean,
    val nickname: String?,
    val titleId: String?,
    val coverId: String?,
) {
    /** Anything worth importing at all. */
    val hasProgress: Boolean get() = stamps.isNotEmpty() || companion || expeditionsDone > 0 || achievements.isNotEmpty()
}

/** Parsing and request bodies of the passport API; no Android or storage, so unit tests cover it. */
object PassportJson {

    /** `{"data":{...}}` or the bare object → state; null when it is not a passport. */
    fun parseState(root: JsonObject?): PassportState? = runCatching {
        val d = root?.obj("data") ?: root ?: return null
        if (!d.has("stamps") && !d.has("collector_no")) return null
        val today = d.obj("today")
        val run = d.obj("run")
        val weekly = d.obj("weekly")
        val stamps = d.arr("stamps")?.mapNotNull { e ->
            val o = e.asObj() ?: return@mapNotNull null
            val code = normalizeCountry(o.str("country")) ?: return@mapNotNull null
            val unlocked = time(o.str("unlocked_at")).takeIf { it > 0 } ?: 1L
            code to StampProgress(o.int("pieces").coerceIn(0, StampProgress.PIECES * StampLevel.entries.size), 0, null, unlocked)
        }?.toMap().orEmpty()
        PassportState(
            collectorNo = d.str("collector_no").orEmpty(),
            nickname = d.str("nickname").orEmpty(),
            titleId = d.str("title_id")?.let(::normalizeId),
            coverId = d.str("cover_id"),
            tzOffsetMin = d.intOrNull("tz_offset_min"),
            tester = d.bool("tester"),
            premium = d.bool("premium"),
            chapter2Open = d.bool("chapter2_open"),
            companion = d.bool("companion"),
            todayDay = today?.str("day").orEmpty(),
            todayMinutes = today?.int("minutes") ?: 0,
            todayPieces = today?.int("pieces") ?: 0,
            nextPieceAtMinute = today?.intOrNull("next_piece_at_minute"),
            runCountry = normalizeCountry(run?.str("country")),
            runMinutes = run?.int("minutes") ?: 0,
            stamps = stamps,
            achievements = earned(d.arr("achievements")),
            collections = earned(d.arr("collections")),
            covers = d.arr("covers")?.mapNotNull { runCatching { it.asString }.getOrNull() }.orEmpty(),
            week = weekly?.str("week").orEmpty(),
            goals = weekly?.arr("goals")?.mapNotNull { e ->
                val o = e.asObj() ?: return@mapNotNull null
                val id = o.str("id") ?: return@mapNotNull null
                PassportWeekGoal(id, o.int("target").coerceAtLeast(1), o.int("progress").coerceAtLeast(0), o.bool("done"))
            }.orEmpty(),
            expeditionsDone = d.int("expeditions_done"),
            unseenEvents = d.int("unseen_events"),
            updatedAt = time(d.str("updated_at")),
        )
    }.getOrNull()

    /** Event kinds an admin uses to take something away; no animation. */
    val REVOKE_KINDS = setOf("stamp_revoked", "achievement_revoked", "collection_revoked", "chapter_closed", "companion_revoked", "passport_reset")

    /**
     * Today's piece first (while one is still open), else the stamp closest
     * to its next level. "Collected" only with a loaded day: two pieces
     * (non-testers) or no next piece announced.
     */
    fun goal(state: PassportState?): PassportGoalPick {
        if (state == null) return PassportGoalPick.Loading
        if (state.stamps.isEmpty()) return PassportGoalPick.First
        if (state.todayDay.isBlank()) return PassportGoalPick.Loading
        val next = state.nextPieceAtMinute
        val collected = (!state.tester && state.todayPieces >= DAILY_PIECES) || next == null
        if (!collected && next != null && state.todayMinutes < next) {
            return PassportGoalPick.Today(next - state.todayMinutes, next, state.todayMinutes)
        }
        val best = state.stamps.entries.filter { !it.value.mastered }.minByOrNull { it.value.toNext }
        return when {
            best != null -> PassportGoalPick.Level(best.key)
            collected -> PassportGoalPick.Collected
            else -> PassportGoalPick.Loading
        }
    }

    const val DAILY_PIECES = 2

    /** While connected and visible, the app reads the passport again this often. */
    const val FOREGROUND_REFRESH_MS = 120_000L

    /**
     * Whether the app (visible, polled every minute) should fetch the
     * passport again: only while connected (no traffic, no new rewards), when
     * the last fetch is [FOREGROUND_REFRESH_MS] old or the clock went back.
     */
    fun foregroundRefreshDue(now: Long, last: Long, connected: Boolean): Boolean =
        connected && (now - last >= FOREGROUND_REFRESH_MS || last > now)

    /**
     * Whether a queued reveal still matches [state]: an admin may have taken
     * the stamp, title or set away before it played. Weekly visas stay.
     */
    fun stillHolds(event: StampEvent, state: PassportState?): Boolean {
        val s = state ?: return false
        return when (event) {
            is StampEvent.Unlocked, is StampEvent.Piece, is StampEvent.LevelUp, is StampEvent.Mastered -> event.code in s.stamps
            is StampEvent.Achieved -> event.code in s.achievements
            is StampEvent.Collected -> event.code in s.collections
            is StampEvent.Special -> s.companion
            is StampEvent.Chapter -> event.code != "2" || s.chapter2Open
            is StampEvent.Expedition -> true
        }
    }

    /** Launcher icons that are passport rewards, and whether [state] still allows [key]. */
    fun iconAllowed(key: String, state: PassportState?): Boolean = when (key) {
        "aurora" -> state?.collections?.containsKey("nordic") == true
        "gold" -> state?.collections?.containsKey("gold_rush") == true
        "diamond" -> state?.achievements?.containsKey("diamond") == true
        "sunset" -> state?.collections?.containsKey("pacific") == true
        "stamp" -> state?.achievements?.containsKey("origin") == true
        else -> true
    }

    fun parseState(raw: String?): PassportState? =
        raw?.takeIf { it.isNotBlank() }?.let { s -> runCatching { JsonParser.parseString(s).asJsonObject }.getOrNull() }?.let(::parseState)

    /** GET /client/passport/events → events sorted by id (oldest first), duplicates dropped. */
    fun parseEvents(root: JsonObject?): List<PassportServerEvent> = runCatching {
        val list = root?.arr("data") ?: root?.obj("data")?.arr("events") ?: return emptyList()
        list.mapNotNull { e ->
            val o = e.asObj() ?: return@mapNotNull null
            val id = o.longOrNull("id") ?: return@mapNotNull null
            val kind = o.str("kind")
            PassportServerEvent(id, event(kind, o.obj("payload") ?: JsonObject()), kind in REVOKE_KINDS)
        }.distinctBy { it.id }.sortedBy { it.id }
    }.getOrDefault(emptyList())

    /** One event kind and payload → the reveal it plays (payloads agreed with iOS). */
    fun event(kind: String?, p: JsonObject): StampEvent? {
        val country = normalizeCountry(p.str("country"))
        return when (kind) {
            "stamp_unlocked" -> country?.let { StampEvent.Unlocked(it) }
            "piece" -> country?.let { StampEvent.Piece(it, levelPieces(p.int("pieces"))) }
            "level_up" -> country?.let { c -> StampLevel.entries.firstOrNull { it.key == p.str("level")?.lowercase() }?.let { StampEvent.LevelUp(c, it) } }
            "mastered" -> country?.let { StampEvent.Mastered(it) }
            "achievement" -> p.str("id")?.let { StampEvent.Achieved(normalizeId(it)) }
            "collection" -> p.str("id")?.let { StampEvent.Collected(normalizeId(it)) }
            "chapter_opened" -> p.str("chapter")?.let { StampEvent.Chapter(it) }
            "weekly_done" -> p.str("goal_id")?.let { StampEvent.Expedition(it) }
            "companion" -> StampEvent.Special(PassportSocial.COMPANION)
            else -> null
        }
    }

    /** Slot of a piece inside its level's 12-piece puzzle (1..12), from the stamp's total after the piece. */
    fun levelPieces(total: Int): Int {
        if (total >= StampProgress.PIECES * StampLevel.entries.size) return StampProgress.PIECES
        val slot = total % StampProgress.PIECES
        return if (slot == 0) StampProgress.PIECES else slot
    }

    /** PUT /client/passport/profile body: only the fields that changed; "" clears one. */
    fun profileBody(nickname: String? = null, titleId: String? = null, coverId: String? = null, tzOffsetMin: Int? = null): JsonObject =
        JsonObject().apply {
            nickname?.let { addProperty("nickname", it) }
            titleId?.let { addProperty("title_id", it) }
            coverId?.let { addProperty("cover_id", it) }
            tzOffsetMin?.let { addProperty("tz_offset_min", it) }
        }

    /** POST /client/passport/import body. */
    fun importBody(deviceId: String, v1: PassportV1Snapshot): JsonObject = JsonObject().apply {
        addProperty("device_id", deviceId)
        add(
            "stamps",
            JsonArray().apply {
                v1.stamps.toSortedMap().forEach { (code, value) ->
                    add(
                        JsonObject().apply {
                            addProperty("country", code)
                            addProperty("pieces", value.first)
                            if (value.second > 0) addProperty("unlocked_at", Instant.ofEpochMilli(value.second).toString())
                        },
                    )
                }
            },
        )
        add("achievements", JsonArray().apply { v1.achievements.distinct().forEach { add(it) } })
        add("collections", JsonArray().apply { v1.collections.distinct().forEach { add(it) } })
        addProperty("expeditions_done", v1.expeditionsDone)
        addProperty("companion", v1.companion)
        v1.nickname?.takeIf { it.isNotBlank() }?.let { addProperty("nickname", it) }
        v1.titleId?.let { addProperty("title_id", it) }
        v1.coverId?.let { addProperty("cover_id", it) }
    }

    /** Server ids use '_' ("nordic_master"); an older spelling with '-' maps to the same thing. */
    fun normalizeId(id: String): String = id.trim().replace('-', '_')

    fun normalizeCountry(code: String?): String? =
        code?.trim()?.uppercase()?.let { if (it == "UK") "GB" else it }?.takeIf { it.length == 2 }

    private fun earned(array: JsonArray?): Map<String, Long> = array?.mapNotNull { e ->
        val o = e.asObj()
        val id = o?.str("id") ?: runCatching { e.asString }.getOrNull() ?: return@mapNotNull null
        normalizeId(id) to time(o?.str("earned_at"))
    }?.toMap().orEmpty()

    private fun time(value: String?): Long = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

    private fun JsonElement.asObj(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.arr(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
    private fun JsonObject.intOrNull(key: String): Int? = get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
    private fun JsonObject.int(key: String): Int = intOrNull(key) ?: 0
    private fun JsonObject.longOrNull(key: String): Long? = get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }
    private fun JsonObject.bool(key: String): Boolean = get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
}

/** Whether the one-time import is finished after an answer with HTTP [status] (-1: network failure). */
object PassportImportPolicy {
    /**
     * Done (never sent again from this device) on success, on 409
     * PASSPORT_ALREADY_IMPORTED and on any other permanent 4xx; tried again
     * later on network errors, 401 (session being renewed), 408, 429 and 5xx.
     * A 404/405 without a passport error code means the endpoint is not
     * there yet (older server): tried again too.
     */
    fun finished(status: Int, errorCode: String? = null): Boolean = when {
        status in 200..299 -> true
        status == 401 || status == 408 || status == 429 -> false
        status == 404 || status == 405 -> errorCode?.startsWith("PASSPORT_") == true
        status in 400..499 -> true
        else -> false
    }
}

/**
 * Plays server events in id order and works out how far to ack: after an
 * event's reveal, everything before the next queued event is acknowledged,
 * including kinds this version cannot show.
 */
class PassportRevealQueue {
    private val items = ArrayDeque<PassportServerEvent>()
    private var batchMax = 0L

    /** Highest id queued so far (later fetches only add newer events). */
    var lastQueued = 0L
        private set

    /** Highest id acknowledged (or handed to [ackThrough]). */
    var acked = 0L
        private set

    val current: PassportServerEvent? get() = items.firstOrNull { it.event != null }

    val isEmpty: Boolean get() = current == null

    /** Whether [events] hold a take-away not seen before (checked before [offer]); one notice per batch. */
    fun hasNewRevoke(events: List<PassportServerEvent>): Boolean =
        events.any { it.revoke && it.id > lastQueued && it.id > acked }

    /** Adds the new events of a fetch; returns the id to ack right away when the fetch held nothing to show. */
    fun offer(events: List<PassportServerEvent>): Long? {
        val fresh = events.filter { it.id > lastQueued && it.id > acked }.sortedBy { it.id }
        if (fresh.isEmpty()) return null
        items.addAll(fresh)
        lastQueued = fresh.last().id
        batchMax = maxOf(batchMax, lastQueued)
        if (current != null) return null
        items.clear()
        return ackTo(batchMax)
    }

    /** The current reveal finished; returns the id to ack through, or null when nothing new is acknowledged. */
    fun done(): Long? {
        items.firstOrNull { it.event != null }?.let { items.remove(it) }
        // Unknown kinds at the front are acknowledged with the shown ones around them.
        while (items.isNotEmpty() && items.first().event == null) items.removeFirst()
        return ackTo(items.firstOrNull()?.id?.minus(1) ?: batchMax)
    }

    private fun ackTo(through: Long): Long? {
        if (through <= acked) return null
        acked = through
        return through
    }

    /** Called with the server's acknowledged position after a restart (e.g. 0). */
    fun reset() {
        items.clear()
        batchMax = 0
        lastQueued = 0
        acked = 0
    }
}
