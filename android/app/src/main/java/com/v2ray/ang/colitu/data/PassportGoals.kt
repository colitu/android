package com.v2ray.ang.colitu.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
 * number, title, cover), read from the server state. Changes go to the
 * account with PUT /client/passport/profile; the favourite stamp is a device
 * setting.
 */
object PassportGoals {
    private val store get() = ColituPassport.store
    private val server get() = ColituPassport.server

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

    /** The account's collector number (six digits, from the server). */
    val collectorNo: String get() = server?.collectorNo?.takeIf { it.isNotBlank() } ?: "000000"

    /** Re-reads the identity; [ColituPassport.apply] calls it. */
    fun refresh() {
        val s = server
        nickname = s?.nickname.orEmpty()
        title = s?.titleId
        cover = s?.coverId?.let { k -> CoverTheme.entries.firstOrNull { it.key == k } }
        favorite = runCatching { store.decodeString("favorite") }.getOrNull()
    }

    /** Shown at once, saved to the account; a refused change goes back to the server's value. */
    fun updateNickname(value: String) {
        val clean = value.trim().take(NICK_MAX)
        nickname = clean
        PassportSync.updateProfile(nickname = clean)
    }

    fun updateFavorite(code: String?) {
        runCatching { if (code == null) store.removeValueForKey("favorite") else store.encode("favorite", code) }
        favorite = code
    }

    fun updateTitle(id: String?) {
        title = id
        PassportSync.updateProfile(titleId = id.orEmpty())
    }

    fun updateCover(theme: CoverTheme?) {
        cover = theme
        PassportSync.updateProfile(coverId = theme?.key.orEmpty())
    }

    const val NICK_MAX = 24

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
        ColituPassport.progress.values.count { (it.level?.ordinal ?: -1) >= level.ordinal }

    fun achieved(a: PassportAchievement): Boolean = server?.achievements?.containsKey(a.id) == true

    /** Stamps of the set already collected, and how many it needs. */
    fun progress(c: PassportCollection): Pair<Int, Int> =
        if (c == PassportCollection.GoldRush) {
            countAtLeast(StampLevel.Gold).coerceAtMost(GOLD_RUSH_COUNT) to GOLD_RUSH_COUNT
        } else {
            c.codes.count { ColituPassport.unlockedAnywhere(it) } to c.codes.size
        }

    fun completed(c: PassportCollection): Boolean = server?.collections?.containsKey(c.id) == true

    /** When the set ("c_<id>") or achievement ("a_<id>") was earned (0 when not, or unknown). */
    fun earnedAt(key: String): Long {
        val s = server ?: return 0L
        return when {
            key.startsWith("c_") -> s.collections[key.removePrefix("c_")] ?: 0L
            key.startsWith("a_") -> s.achievements[key.removePrefix("a_")] ?: 0L
            else -> 0L
        }
    }

    /** Covers this user may pick (the server's list; Classic always). */
    fun covers(premium: Boolean): List<CoverTheme> {
        val keys = server?.covers
        if (keys.isNullOrEmpty()) return buildList {
            add(CoverTheme.Classic)
            if (premium) add(CoverTheme.Premium)
        }
        return (listOf(CoverTheme.Classic) + keys.mapNotNull { k -> CoverTheme.entries.firstOrNull { it.key == k } }).distinct()
    }

    /** The cover shown: the chosen one while still allowed, else Premium or Classic. */
    fun activeCover(premium: Boolean): CoverTheme {
        val allowed = covers(premium)
        return cover?.takeIf { it in allowed } ?: if (CoverTheme.Premium in allowed) CoverTheme.Premium else CoverTheme.Classic
    }

    fun titleOf(id: String?): String? = PassportAchievement.entries.firstOrNull { it.id == id }?.takeIf { achieved(it) }?.title
}
