package com.v2ray.ang.colitu.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tencent.mmkv.MMKV
import java.time.LocalDate

/** One passport stamp: the country it stands for and its printed number. */
data class ColituStamp(val code: String, val number: Int)

/** Stamp material; every level is one 12-piece puzzle. */
enum class StampLevel(val key: String) { Bronze("bronze"), Silver("silver"), Gold("gold"), Diamond("diamond") }

/** What the device knows about one stamp. Unlocked once [unlockedAt] is set. */
data class StampProgress(val pieces: Int, val minutes: Int, val city: String?, val unlockedAt: Long) {
    val unlocked: Boolean get() = unlockedAt > 0
    val level: StampLevel? get() = if (!unlocked) null else StampLevel.entries[(pieces / PIECES).coerceAtMost(StampLevel.entries.size - 1)]

    /** Diamond with its puzzle full: nothing left to collect. */
    val mastered: Boolean get() = pieces >= PIECES * StampLevel.entries.size

    /** Filled pieces (0-12) of the current level's puzzle. */
    val levelPieces: Int get() = if (mastered) PIECES else pieces % PIECES

    val toNext: Int get() = PIECES - levelPieces

    companion object {
        const val PIECES = 12
    }
}

/** A reward waiting for the app to show it (the service earns them while the app is closed). */
sealed interface StampEvent {
    val code: String
    data class Unlocked(override val code: String) : StampEvent
    data class Piece(override val code: String, val levelPieces: Int) : StampEvent
    data class LevelUp(override val code: String, val level: StampLevel) : StampEvent
    data class Mastered(override val code: String) : StampEvent

    /** A hidden or visible achievement (see [PassportGoals]); [code] is its id. */
    data class Achieved(override val code: String) : StampEvent

    /** A collection set completed; [code] is its id. */
    data class Collected(override val code: String) : StampEvent

    /** A chapter opened; [code] is its number ("2"). */
    data class Chapter(override val code: String) : StampEvent

    /** A weekly expedition done; [code] is the goal id. */
    data class Expedition(override val code: String) : StampEvent

    /** A special stamp outside the chapters (the Companion stamp); [code] is its id. */
    data class Special(override val code: String) : StampEvent
}

/**
 * Colitu Passport. Made and kept only on this device; nothing about it is
 * sent anywhere. The VPN service calls [tick] once a minute while the tunnel
 * runs ([ColituPassport] is shared through multi-process MMKV), the app shows
 * the queued rewards with their animations.
 *
 * Rules: five minutes in a row in a country unlock its Bronze stamp. Every day
 * the 10th and the 60th minute of use each add one puzzle piece to the stamp
 * of the country connected at that minute, at most two a day across all
 * servers. Tester accounts (they see the panel's alpha nodes) get a piece
 * every ten minutes without the daily cap.
 */
object ColituPassport {
    const val UNLOCK_MINUTES = 5
    val PIECE_MINUTES = listOf(10, 60)
    private const val TESTER_EVERY = 10

    /** Chapter 01 — Origin. Other countries are recorded too, for the next chapter. */
    val stamps = listOf("SE", "FI", "DE", "NL", "GB", "PL", "LV", "FR", "US", "JP")
        .mapIndexed { i, code -> ColituStamp(code, i + 1) }

    /** Chapter 02 — Horizons; opens once Chapter 01 is complete. No Russia or Kazakhstan (owner decision). */
    val chapter2 = listOf("NO", "EE", "LT", "CH", "CA", "TR", "SG", "HK", "BR", "AU")
        .mapIndexed { i, code -> ColituStamp(code, i + 11) }

    val allStamps = stamps + chapter2

    fun stampOf(code: String): ColituStamp? = allStamps.firstOrNull { it.code == code }

    /** Chapter 02 is open: every Chapter 01 stamp collected. */
    val chapter2Open: Boolean get() = stamps.all { it.code in progress }

    internal val store by lazy { MMKV.mmkvWithID("COLITU_PASSPORT_V2", MMKV.MULTI_PROCESS_MODE) }

    /** Country code → progress, unlocked stamps of this chapter only. Compose state, see [reload]. */
    var progress: Map<String, StampProgress> by mutableStateOf(emptyMap())
        private set

    /** Minutes of use today and pieces already earned today. Compose state. */
    var today by mutableStateOf(0 to 0)
        private set

    /** A passport notification was tapped; the shell opens the passport and clears it. */
    var openRequested by mutableStateOf(false)

    /** Chapter 01 stamps collected (the passport's main count). */
    val count: Int get() = stamps.count { it.code in progress }
    val complete: Boolean get() = count == stamps.size

    fun countIn(chapter: List<ColituStamp>): Int = chapter.count { it.code in progress }

    fun progressOf(code: String): StampProgress = progress[code] ?: read(code)

    fun stampFor(code: String?): ColituStamp? = normalize(code)?.let { c -> allStamps.firstOrNull { it.code == c } }

    var tester: Boolean
        get() = runCatching { store.decodeBool("tester", false) }.getOrDefault(false)
        set(value) {
            runCatching { store.encode("tester", value) }
        }

    /** Test build: passport animations run even with system animations off (demo recordings). */
    var demoMotion: Boolean by mutableStateOf(runCatching { store.decodeBool("demo_motion", false) }.getOrDefault(false))
        private set

    fun updateDemoMotion(value: Boolean) {
        demoMotion = value
        runCatching { store.encode("demo_motion", value) }
    }

    var notify: Boolean
        get() = runCatching { store.decodeBool("notify", true) }.getOrDefault(true)
        set(value) {
            runCatching { store.encode("notify", value) }
        }

    /** Re-reads what the service wrote. Cheap; the app calls it while the passport is visible. */
    fun reload() {
        runCatching {
            progress = allStamps.mapNotNull { s -> read(s.code).takeIf { it.unlocked }?.let { s.code to it } }.toMap()
            val day = LocalDate.now().toString()
            today = if (store.decodeString("day") == day) store.decodeInt("day_min", 0) to store.decodeInt("day_pieces", 0) else 0 to 0
        }
        PassportGoals.refresh()
        PassportWeekly.refresh()
        PassportSocial.refresh()
    }

    /** The app remembers the city shown under a stamp (the service only knows the country). */
    fun rememberCity(countryCode: String?, city: String?) {
        val code = normalize(countryCode) ?: return
        if (!city.isNullOrBlank()) runCatching { store.encode("city_$code", city) }
    }

    /**
     * One minute of a running tunnel to [countryCode]. Called by the VPN
     * service; returns the rewards it produced (also queued for the app).
     */
    @Synchronized
    fun tick(countryCode: String?, now: Long = System.currentTimeMillis()): List<StampEvent> {
        val code = normalize(countryCode) ?: return emptyList()
        val events = mutableListOf<StampEvent>()
        runCatching {
            val day = LocalDate.now().toString()
            if (store.decodeString("day") != day) {
                store.encode("day", day)
                store.encode("day_min", 0)
                store.encode("day_pieces", 0)
            }
            // Minutes in a row in one country; a gap over 90 s or another country starts over.
            val last = store.decodeLong("last_tick", 0L)
            val sameRun = store.decodeString("run_cc") == code && now - last in 1..90_000
            val run = if (sameRun) store.decodeInt("run_min", 0) + 1 else 1
            store.encode("run_cc", code)
            store.encode("run_min", run)
            store.encode("last_tick", now)
            store.encode("m_$code", store.decodeInt("m_$code", 0) + 1)

            if (store.decodeLong("t_$code", 0L) == 0L && run >= UNLOCK_MINUTES) {
                store.encode("t_$code", now)
                events += StampEvent.Unlocked(code)
                weeklyUnlock(code)
            }

            val minutes = store.decodeInt("day_min", 0) + 1
            store.encode("day_min", minutes)
            countForAchievements(code, minutes, now)
            if (minutes == 10) PassportWeekly.bump(ExpeditionGoal.Days10)
            val earns = if (tester) minutes % TESTER_EVERY == 0 else minutes in PIECE_MINUTES
            if (earns) {
                // The piece goes to this country, or to the last unlocked one when this stamp is still locked.
                val target = if (store.decodeLong("t_$code", 0L) > 0) code else store.decodeString("last_unlocked")
                if (target != null) {
                    events += addPiece(target)
                    store.encode("day_pieces", store.decodeInt("day_pieces", 0) + 1)
                }
            }
            if (store.decodeLong("t_$code", 0L) > 0) store.encode("last_unlocked", code)
            if (events.isNotEmpty()) enqueue(events)
        }
        return events
    }

    /**
     * Counters behind the time-based achievements: night (00-05) and early
     * (05-07) minutes per day, the countries used in the last seven days and
     * the days that reached 60 minutes.
     */
    private fun countForAchievements(code: String, minutes: Int, now: Long) {
        val today = LocalDate.now()
        val hour = java.time.LocalTime.now().hour
        val day = today.toString()
        fun dayCounter(key: String) {
            if (store.decodeString("${key}_day") != day) {
                store.encode("${key}_day", day)
                store.encode("${key}_min", 0)
            }
            val n = store.decodeInt("${key}_min", 0) + 1
            store.encode("${key}_min", n)
            if (n >= 10 && store.decodeLong("a_$key", 0L) == 0L) store.encode("a_$key", now)
        }
        if (hour < 5) dayCounter("night") else if (hour < 7) dayCounter("early")

        store.encode("d_$code", today.toEpochDay())
        val codes = (store.decodeString("codes").orEmpty().split(',').filter { it.isNotEmpty() } + code).distinct()
        store.encode("codes", codes.joinToString(","))
        val recent = codes.count { today.toEpochDay() - store.decodeLong("d_$it", -100L) < 7 }
        if (recent >= 5 && store.decodeLong("a_globe", 0L) == 0L) store.encode("a_globe", now)

        if (minutes == 60) {
            val days = store.decodeInt("days60", 0) + 1
            store.encode("days60", days)
            if (days >= 7 && store.decodeLong("a_longhaul", 0L) == 0L) store.encode("a_longhaul", now)
        }
    }

    /** Every country with a stamp record, this chapter or not (collections span chapters). */
    fun unlockedAnywhere(code: String): Boolean = runCatching { store.decodeLong("t_$code", 0L) > 0 }.getOrDefault(false)

    /** Test build: one more piece for [code] (unlocking it first), through the same queue as real rewards. */
    @Synchronized
    fun debugPiece(code: String, now: Long = System.currentTimeMillis()) {
        runCatching {
            val events = mutableListOf<StampEvent>()
            if (store.decodeLong("t_$code", 0L) == 0L) {
                store.encode("t_$code", now)
                events += StampEvent.Unlocked(code)
                weeklyUnlock(code)
            } else {
                events += addPiece(code)
            }
            enqueue(events)
        }
        reload()
    }

    /** Test build: a demo passport with every level, or an empty one. */
    fun debugCycle(now: Long = System.currentTimeMillis()) {
        runCatching {
            val keep = tester to notify
            val demo = demoMotion
            val collector = store.decodeString("collector_no")
            if (count > 0) {
                store.clearAll()
            } else {
                val set = mapOf(
                    "SE" to 48, "FI" to 40, "DE" to 28, "NL" to 37, "GB" to 15, "PL" to 12, "LV" to 11, "FR" to 2, "US" to 0,
                    // Next chapter's countries: Nordic complete, Baltic 2 of 3.
                    "NO" to 3, "EE" to 1,
                )
                store.encode("a_night", now)
                set.forEach { (code, pieces) ->
                    store.encode("t_$code", now)
                    store.encode("p_$code", pieces)
                    store.encode("m_$code", 30 + pieces * 40)
                }
            }
            tester = keep.first
            notify = keep.second
            updateDemoMotion(demo)
            collector?.let { store.encode("collector_no", it) }
        }
        reload()
    }

    /** Rewards earned since the app last looked, oldest first; clears the queue. */
    @Synchronized
    fun takePending(): List<StampEvent> = runCatching {
        val raw = store.decodeString("pending").orEmpty()
        store.removeValueForKey("pending")
        raw.split(';').mapNotNull { decode(it) }
    }.getOrDefault(emptyList())

    private fun weeklyUnlock(code: String) {
        PassportWeekly.bump(ExpeditionGoal.NewCountry)
        if (PassportCollection.entries.any { code in it.codes }) PassportWeekly.bump(ExpeditionGoal.SetStamp)
    }

    private fun addPiece(code: String): List<StampEvent> {
        PassportWeekly.bump(ExpeditionGoal.Pieces)
        val before = read(code)
        val pieces = before.pieces + 1
        store.encode("p_$code", pieces)
        val after = before.copy(pieces = pieces)
        return buildList {
            add(StampEvent.Piece(code, if (after.levelPieces == 0) StampProgress.PIECES else after.levelPieces))
            when {
                after.mastered && !before.mastered -> add(StampEvent.Mastered(code))
                after.level != before.level -> add(StampEvent.LevelUp(code, after.level!!))
            }
        }
    }

    private fun enqueue(events: List<StampEvent>) {
        if (events.isEmpty()) return
        val old = store.decodeString("pending").orEmpty()
        val added = events.joinToString(";") { encode(it) }
        store.encode("pending", if (old.isEmpty()) added else "$old;$added")
    }

    private fun encode(e: StampEvent): String = when (e) {
        is StampEvent.Unlocked -> "U|${e.code}"
        is StampEvent.Piece -> "P|${e.code}|${e.levelPieces}"
        is StampEvent.LevelUp -> "L|${e.code}|${e.level.key}"
        is StampEvent.Mastered -> "M|${e.code}"
        is StampEvent.Achieved -> "A|${e.code}"
        is StampEvent.Collected -> "C|${e.code}"
        is StampEvent.Chapter -> "H|${e.code}"
        is StampEvent.Expedition -> "X|${e.code}"
        is StampEvent.Special -> "S|${e.code}"
    }

    private fun decode(s: String): StampEvent? {
        val p = s.split('|')
        val code = p.getOrNull(1) ?: return null
        return when (p[0]) {
            "U" -> StampEvent.Unlocked(code)
            "P" -> StampEvent.Piece(code, p.getOrNull(2)?.toIntOrNull() ?: 0)
            "L" -> StampLevel.entries.firstOrNull { it.key == p.getOrNull(2) }?.let { StampEvent.LevelUp(code, it) }
            "M" -> StampEvent.Mastered(code)
            "A" -> StampEvent.Achieved(code)
            "C" -> StampEvent.Collected(code)
            "H" -> StampEvent.Chapter(code)
            "X" -> StampEvent.Expedition(code)
            "S" -> StampEvent.Special(code)
            else -> null
        }
    }

    private fun read(code: String): StampProgress = runCatching {
        StampProgress(
            store.decodeInt("p_$code", 0),
            store.decodeInt("m_$code", 0),
            store.decodeString("city_$code"),
            store.decodeLong("t_$code", 0L),
        )
    }.getOrDefault(StampProgress(0, 0, null, 0L))

    private fun normalize(code: String?): String? =
        code?.trim()?.uppercase()?.let { if (it == "UK") "GB" else it }?.takeIf { it.length == 2 }
}
