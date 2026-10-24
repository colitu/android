package com.v2ray.ang.colitu.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.random.Random

/**
 * Achievements: each gives a title the user can show on the passport. Secret
 * ones show as "???" until earned; afterwards their condition is spelled out.
 * The titles stay in English in every language (like a badge).
 */
enum class PassportAchievement(val id: String, val title: String, val secret: Boolean) {
    First("first", "First Connection", false),
    Globetrotter("globe", "Globetrotter", false),
    Origin("origin", "Origin Collector", false),
    NordicMaster("nordic_master", "Nordic Master", false),
    Night("night", "Night Explorer", true),
    Early("early", "Early Bird", true),
    LongHaul("longhaul", "Long Haul", true),
    Diamond("diamond", "Diamond Collector", true),
    Pathfinder("pathfinder", "Pathfinder", false),
    LegacyGold("legacy_gold", "Golden Passport", false),
    LegacyDiamond("legacy_diamond", "Diamond Passport", false),
}

/**
 * Collection sets. Their countries may belong to a later chapter: those
 * stamps are recorded already, so a set can be finished before its chapter
 * opens. [cover] is the passport cover theme the set gives, if any.
 */
enum class PassportCollection(val id: String, val title: String, val codes: List<String>, val secret: Boolean, val cover: CoverTheme?) {
    Nordic("nordic", "Nordic", listOf("SE", "FI", "NO"), false, CoverTheme.Nordic),
    Baltic("baltic", "Baltic", listOf("LV", "EE", "LT"), false, CoverTheme.Baltic),
    Atlantic("atlantic", "Across the Atlantic", listOf("US", "GB", "CA"), false, CoverTheme.Atlantic),
    Heart("heart", "Heart of Europe", listOf("DE", "FR", "NL", "PL", "CH"), false, null),
    Pacific("pacific", "Pacific Rim", listOf("JP", "SG", "HK", "AU"), false, CoverTheme.Pacific),
    GoldRush("gold_rush", "Gold Rush", emptyList(), true, null),
}

/** Passport cover looks. Classic for everyone, Premium with the plan, the rest from sets. */
enum class CoverTheme(val key: String) { Classic("classic"), Premium("premium"), Nordic("nordic"), Baltic("baltic"), Atlantic("atlantic"), Pacific("pacific") }

/**
 * Achievements, collections and the passport identity (nickname, collector
 * number, favourite stamp, title, cover). All on the device, like the stamps.
 */
object PassportGoals {
    private val store get() = ColituPassport.store

    const val GOLD_RUSH_COUNT = 3
    const val DIAMOND_COUNT = 3

    var nickname by mutableStateOf("")
        private set
    var favorite by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf<String?>(null)
        private set
    var cover by mutableStateOf<CoverTheme?>(null)
        private set

    /** Six digits made on this device, not tied to the account. */
    val collectorNo: String
        get() = runCatching {
            store.decodeString("collector_no") ?: Random.nextInt(100_000, 1_000_000).toString().also { store.encode("collector_no", it) }
        }.getOrDefault("000000")

    /** Re-reads the identity; [ColituPassport.reload] calls it. */
    fun refresh() {
        runCatching {
            nickname = store.decodeString("nick").orEmpty()
            favorite = store.decodeString("favorite")
            title = store.decodeString("title")
            cover = store.decodeString("cover")?.let { k -> CoverTheme.entries.firstOrNull { it.key == k } }
        }
    }

    fun updateNickname(value: String) = save("nick", value.trim().take(20).ifEmpty { null })
    fun updateFavorite(code: String?) = save("favorite", code)
    fun updateTitle(id: String?) = save("title", id).also { PassportWeekly.bump(ExpeditionGoal.Customize) }
    fun updateCover(theme: CoverTheme?) = save("cover", theme?.key).also { PassportWeekly.bump(ExpeditionGoal.Customize) }

    private fun save(key: String, value: String?) {
        runCatching { if (value == null) store.removeValueForKey(key) else store.encode(key, value) }
        refresh()
    }

    /** Every country with a record: this chapter, the sets and whatever the service saw. */
    private fun allCodes(): List<String> =
        (ColituPassport.stamps.map { it.code } + PassportCollection.entries.flatMap { it.codes } +
            runCatching { store.decodeString("codes").orEmpty().split(',') }.getOrDefault(emptyList()))
            .filter { it.length == 2 }.distinct()

    /** Stamps of [chapter] at [level] or higher (Chapter 03 — Legacy). */
    fun legacy(level: StampLevel, chapter: List<ColituStamp>): Int =
        chapter.count { (ColituPassport.progressOf(it.code).level?.ordinal ?: -1) >= level.ordinal }

    /** Chapter 03 opens with Chapter 02 complete. */
    val chapter3Open: Boolean get() = ColituPassport.countIn(ColituPassport.chapter2) == ColituPassport.chapter2.size

    /** Ring around the account avatar: Diamond with Diamond Collector, gold with any Gold stamp. */
    fun avatarFrame(): StampLevel? = when {
        achieved(PassportAchievement.Diamond) -> StampLevel.Diamond
        countAtLeast(StampLevel.Gold) > 0 -> StampLevel.Gold
        else -> null
    }

    private fun countAtLeast(level: StampLevel): Int =
        allCodes().count { (ColituPassport.progressOf(it).level?.ordinal ?: -1) >= level.ordinal }

    fun achieved(a: PassportAchievement): Boolean = when (a) {
        PassportAchievement.First -> allCodes().any { ColituPassport.unlockedAnywhere(it) }
        PassportAchievement.Origin -> ColituPassport.stamps.all { ColituPassport.unlockedAnywhere(it.code) }
        PassportAchievement.NordicMaster -> PassportCollection.Nordic.codes.all {
            (ColituPassport.progressOf(it).level?.ordinal ?: -1) >= StampLevel.Gold.ordinal
        }
        PassportAchievement.Diamond -> countAtLeast(StampLevel.Diamond) >= DIAMOND_COUNT
        PassportAchievement.Pathfinder -> PassportWeekly.total >= PassportWeekly.PATHFINDER_AT
        PassportAchievement.LegacyGold -> legacy(StampLevel.Gold, ColituPassport.stamps) >= ColituPassport.stamps.size
        PassportAchievement.LegacyDiamond -> legacy(StampLevel.Diamond, ColituPassport.stamps) >= ColituPassport.stamps.size
        else -> runCatching { store.decodeLong("a_${a.id}", 0L) > 0 }.getOrDefault(false)
    }

    /** Stamps of the set already collected, and how many it needs. */
    fun progress(c: PassportCollection): Pair<Int, Int> =
        if (c == PassportCollection.GoldRush) {
            countAtLeast(StampLevel.Gold).coerceAtMost(GOLD_RUSH_COUNT) to GOLD_RUSH_COUNT
        } else {
            c.codes.count { ColituPassport.unlockedAnywhere(it) } to c.codes.size
        }

    fun completed(c: PassportCollection): Boolean = progress(c).let { it.first >= it.second }

    /** When the set or achievement was first seen complete (0 when not yet). */
    fun earnedAt(key: String): Long = runCatching { store.decodeLong("seen_$key", 0L) }.getOrDefault(0L)

    /** Covers this user may pick. */
    fun covers(premium: Boolean): List<CoverTheme> = buildList {
        add(CoverTheme.Classic)
        if (premium) add(CoverTheme.Premium)
        PassportCollection.entries.forEach { c -> c.cover?.let { if (completed(c)) add(it) } }
    }

    /** The cover shown: the chosen one while still allowed, else Premium or Classic. */
    fun activeCover(premium: Boolean): CoverTheme {
        val allowed = covers(premium)
        return cover?.takeIf { it in allowed } ?: if (premium) CoverTheme.Premium else CoverTheme.Classic
    }

    fun titleOf(id: String?): String? = PassportAchievement.entries.firstOrNull { it.id == id }?.takeIf { achieved(it) }?.title

    /**
     * Sets and achievements reached since the last call, each once (the app
     * plays their reveal). Sets first, then achievements.
     */
    fun checkNew(now: Long = System.currentTimeMillis()): List<StampEvent> = runCatching {
        buildList {
            // Chapter 02 opens: then the stamps of its countries recorded so far, one by one.
            if (ColituPassport.chapter2Open && earnedAt("ch_2") == 0L) {
                store.encode("seen_ch_2", now)
                add(StampEvent.Chapter("2"))
                ColituPassport.chapter2.filter { ColituPassport.unlockedAnywhere(it.code) }.forEach { add(StampEvent.Unlocked(it.code)) }
            }
            if (chapter3Open && earnedAt("ch_3") == 0L) {
                store.encode("seen_ch_3", now)
                add(StampEvent.Chapter("3"))
            }
            PassportWeekly.takeNewlyDone().forEach { add(StampEvent.Expedition(it.id)) }
            PassportCollection.entries.forEach { c ->
                if (completed(c) && earnedAt("c_${c.id}") == 0L) {
                    store.encode("seen_c_${c.id}", now)
                    add(StampEvent.Collected(c.id))
                }
            }
            PassportAchievement.entries.forEach { a ->
                if (achieved(a) && earnedAt("a_${a.id}") == 0L) {
                    store.encode("seen_a_${a.id}", now)
                    add(StampEvent.Achieved(a.id))
                }
            }
        }
    }.getOrDefault(emptyList())
}
