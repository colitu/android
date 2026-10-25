package com.v2ray.ang.colitu.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tencent.mmkv.MMKV

/** One passport stamp: the country it stands for and its printed number. */
data class ColituStamp(val code: String, val number: Int)

/** Stamp material; every level is one 12-piece puzzle. */
enum class StampLevel(val key: String) { Bronze("bronze"), Silver("silver"), Gold("gold"), Diamond("diamond") }

/** What is known about one stamp. Unlocked once [unlockedAt] is set. */
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

/** A reward to reveal; the server creates them (passport events), the app plays them in order. */
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
 * Colitu Passport, saved to the account. The server counts the minutes from
 * node traffic and evaluates every rule; this object only shows the last
 * server state ([PassportSync] fetches it, [PassportCache] keeps it for
 * offline display) and keeps a few device settings (sound, haptics,
 * notifications, friends, the stamp cities).
 */
object ColituPassport {
    const val UNLOCK_MINUTES = 5
    val PIECE_MINUTES = listOf(10, 60)

    /** Chapter 01 — Origin. */
    val stamps = listOf("SE", "FI", "DE", "NL", "GB", "PL", "LV", "FR", "US", "JP")
        .mapIndexed { i, code -> ColituStamp(code, i + 1) }

    /** Chapter 02 — Horizons; opens once Chapter 01 is complete. No Russia or Kazakhstan (owner decision). */
    val chapter2 = listOf("NO", "EE", "LT", "CH", "CA", "TR", "SG", "HK", "BR", "AU")
        .mapIndexed { i, code -> ColituStamp(code, i + 11) }

    val allStamps = stamps + chapter2

    fun stampOf(code: String): ColituStamp? = allStamps.firstOrNull { it.code == code }

    /** Shared with the VPN process (it caches what it polls while connected). */
    internal val store by lazy { MMKV.mmkvWithID("COLITU_PASSPORT_V2", MMKV.MULTI_PROCESS_MODE) }

    internal val cache by lazy {
        PassportCache(
            object : PassportKv {
                override fun string(key: String): String? = runCatching { store.decodeString(key) }.getOrNull()
                override fun putString(key: String, value: String?) {
                    runCatching { if (value == null) store.removeValueForKey(key) else store.encode(key, value) }
                }
                override fun bool(key: String): Boolean = runCatching { store.decodeBool(key, false) }.getOrDefault(false)
                override fun putBool(key: String, value: Boolean) {
                    runCatching { store.encode(key, value) }
                }
                override fun long(key: String): Long = runCatching { store.decodeLong(key, 0L) }.getOrDefault(0L)
                override fun putLong(key: String, value: Long) {
                    runCatching { store.encode(key, value) }
                }
            },
        )
    }

    /** Last server state; null before the first answer (or after sign-out). Compose state. */
    var server: PassportState? by mutableStateOf(null)
        private set

    /** Country code → progress, unlocked stamps of every chapter. Compose state, see [reload]. */
    var progress: Map<String, StampProgress> by mutableStateOf(emptyMap())
        private set

    /** Minutes of use today and pieces already earned today (server's day). Compose state. */
    var today by mutableStateOf(0 to 0)
        private set

    /** A passport notification was tapped; the shell opens the passport and clears it. */
    var openRequested by mutableStateOf(false)

    /** Chapter 02 is open (the server decides). */
    val chapter2Open: Boolean get() = server?.chapter2Open ?: stamps.all { it.code in progress }

    /** Chapter 01 stamps collected (the passport's main count). */
    val count: Int get() = stamps.count { it.code in progress }
    val complete: Boolean get() = count == stamps.size

    fun countIn(chapter: List<ColituStamp>): Int = chapter.count { it.code in progress }

    fun progressOf(code: String): StampProgress = progress[code] ?: StampProgress(0, 0, null, 0L)

    fun stampFor(code: String?): ColituStamp? = PassportJson.normalizeCountry(code)?.let { c -> allStamps.firstOrNull { it.code == c } }

    /** Test accounts earn a piece every ten minutes (server flag). */
    val tester: Boolean get() = server?.tester ?: false

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

    /** Re-reads the cached server state (also written by the VPN process). Cheap. */
    fun reload() {
        val raw = cache.raw()
        shownRaw = raw
        apply(PassportJson.parseState(raw))
    }

    /** The cached text last shown; [reloadIfChanged] skips the parse while it is the same. */
    @Volatile private var shownRaw: String? = null

    /** Shows what the VPN process cached since, if anything (never clears a shown state). */
    fun reloadIfChanged() {
        val raw = cache.raw() ?: return
        if (raw == shownRaw) return
        shownRaw = raw
        PassportJson.parseState(raw)?.let { apply(it) }
    }

    /** Shows [state] (null: signed out, nothing to show). */
    internal fun apply(state: PassportState?) {
        server = state
        progress = state?.stamps.orEmpty().mapValues { (code, p) ->
            p.copy(city = runCatching { store.decodeString("city_$code") }.getOrNull())
        }
        today = if (state != null) state.todayMinutes to state.todayPieces else 0 to 0
        if (state != null) fallBackIcon(state)
        PassportGoals.refresh()
        PassportWeekly.refresh()
        PassportSocial.refresh()
    }

    /** A reward launcher icon no longer allowed (taken away by an admin) goes back to the default one. */
    private fun fallBackIcon(state: PassportState) {
        runCatching {
            val context = com.v2ray.ang.AngApplication.application
            val chosen = com.v2ray.ang.colitu.app.ColituAppIcon.chosen(context)
            if (!PassportJson.iconAllowed(chosen.key, state)) com.v2ray.ang.colitu.app.ColituAppIcon.request(com.v2ray.ang.colitu.app.AppIcon.Default)
        }
    }

    /** The app remembers the city shown under a stamp (the server only knows the country). */
    fun rememberCity(countryCode: String?, city: String?) {
        val code = PassportJson.normalizeCountry(countryCode) ?: return
        if (!city.isNullOrBlank()) runCatching { store.encode("city_$code", city) }
    }

    /** Every country with a stamp, this chapter or not (collections span chapters). */
    fun unlockedAnywhere(code: String): Boolean = code in progress

    // ── v1 (on-device) passport, imported once per device ────────────────

    /** What the v1 passport (2.9.4, counted on the device) left in the store. */
    internal fun v1Snapshot(): PassportV1Snapshot = runCatching {
        val keys = store.allKeys().orEmpty()
        val stamps = keys.filter { it.startsWith("t_") && it.length == 4 }.mapNotNull { key ->
            val code = key.substring(2)
            val at = store.decodeLong(key, 0L)
            if (at <= 0) null else code to (store.decodeInt("p_$code", 0) to at)
        }.toMap()
        fun ids(prefix: String, all: List<String>) = all.filter { id ->
            store.decodeLong("${prefix}$id", 0L) > 0 || store.decodeLong("seen_${prefix}$id", 0L) > 0
        }
        PassportV1Snapshot(
            stamps = stamps,
            achievements = ids("a_", PassportAchievement.entries.map { it.id }),
            collections = ids("c_", PassportCollection.entries.map { it.id }),
            expeditionsDone = store.decodeInt("expeditions_done", 0),
            companion = store.decodeLong("special_${PassportSocial.COMPANION}", 0L) > 0,
            nickname = store.decodeString("nick"),
            titleId = store.decodeString("title"),
            coverId = store.decodeString("cover"),
        )
    }.getOrDefault(PassportV1Snapshot(emptyMap(), emptyList(), emptyList(), 0, false, null, null, null))
}
