package com.v2ray.ang.colitu.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.LocalDate

/** A friend's passport as read from their QR (no account data, only what their share card shows). */
data class PassportFriend(
    val nick: String,
    val collector: String,
    val title: String?,
    /** One digit per stamp in [ColituPassport.allStamps] order: 0 locked, 1-4 Bronze..Diamond, 5 Diamond complete. */
    val levels: String,
    val addedAt: Long,
) {
    fun level(code: String): Int = ColituPassport.allStamps.indexOfFirst { it.code == code }.let { levels.getOrNull(it)?.digitToIntOrNull() ?: 0 }
    fun count(chapter: List<ColituStamp>): Int = chapter.count { level(it.code) > 0 }
    /** Sum of levels: the tie-breaker of the friends ranking. */
    val score: Int get() = levels.sumOf { it.digitToIntOrNull() ?: 0 }
}

/**
 * Friends, the Companion stamp and the passport's own settings. Everything
 * is kept on this device; the optional anonymous counters (see
 * [metricsPayload]) carry no identifier at all.
 */
object PassportSocial {
    private val store get() = ColituPassport.store

    var friends by mutableStateOf(loadFriends())
        private set

    var showRanking by mutableStateOf(runCatching { store.decodeBool("friends_ranking", true) }.getOrDefault(true))
        private set
    var sound by mutableStateOf(runCatching { store.decodeBool("sound", true) }.getOrDefault(true))
        private set
    var haptics by mutableStateOf(runCatching { store.decodeBool("haptics", true) }.getOrDefault(true))
        private set
    var metrics by mutableStateOf(runCatching { store.decodeBool("metrics", false) }.getOrDefault(false))
        private set

    fun updateRanking(on: Boolean) = flag("friends_ranking", on).also { showRanking = on }
    fun updateSound(on: Boolean) = flag("sound", on).also { sound = on }
    fun updateHaptics(on: Boolean) = flag("haptics", on).also { haptics = on }
    fun updateMetrics(on: Boolean) = flag("metrics", on).also { metrics = on }

    private fun flag(key: String, on: Boolean) {
        runCatching { store.encode(key, on) }
    }

    // ── Friends ──────────────────────────────────────────────────────────

    /** Adds or refreshes a friend scanned from a passport QR (one per collector number). */
    fun addFriend(friend: PassportFriend) {
        if (friend.collector == PassportGoals.collectorNo) return
        friends = (friends.filter { it.collector != friend.collector } + friend).sortedByDescending { it.addedAt }
        saveFriends()
    }

    fun removeFriend(collector: String) {
        friends = friends.filter { it.collector != collector }
        saveFriends()
    }

    // Written by hand (not reflection): field names survive R8 and app updates.
    private fun saveFriends() {
        val array = JsonArray()
        friends.forEach { f ->
            array.add(
                JsonObject().apply {
                    addProperty("nick", f.nick)
                    addProperty("collector", f.collector)
                    f.title?.let { addProperty("title", it) }
                    addProperty("levels", f.levels)
                    addProperty("added", f.addedAt)
                },
            )
        }
        runCatching { store.encode("friends", array.toString()) }
    }

    private fun loadFriends(): List<PassportFriend> = runCatching {
        val raw = store.decodeString("friends") ?: return emptyList()
        JsonParser.parseString(raw).asJsonArray.mapNotNull { e ->
            val o = e.asJsonObject
            PassportFriend(
                o.get("nick")?.asString.orEmpty(),
                o.get("collector")?.asString ?: return@mapNotNull null,
                o.get("title")?.asString,
                o.get("levels")?.asString.orEmpty(),
                o.get("added")?.asLong ?: 0L,
            )
        }
    }.getOrDefault(emptyList())

    /** Re-reads what another screen or a reset changed. */
    fun refresh() {
        friends = loadFriends()
    }

    // ── Companion stamp ──────────────────────────────────────────────────

    const val COMPANION = "companion"

    val companion: Boolean get() = runCatching { store.decodeLong("special_$COMPANION", 0L) > 0 }.getOrDefault(false)

    fun companionAt(): Long = runCatching { store.decodeLong("special_$COMPANION", 0L) }.getOrDefault(0L)

    /**
     * The Companion stamp: whoever signed up with an invite code and whoever
     * invited someone. Queued like any reward so the app plays its reveal.
     */
    fun markCompanion(now: Long = System.currentTimeMillis()) {
        runCatching {
            if (store.decodeLong("special_$COMPANION", 0L) > 0) return
            store.encode("special_$COMPANION", now)
            val old = store.decodeString("pending").orEmpty()
            store.encode("pending", if (old.isEmpty()) "S|$COMPANION" else "$old;S|$COMPANION")
        }
    }

    // ── Anonymous counters ───────────────────────────────────────────────

    /**
     * Today's counters for POST /api/v1/passport/metrics, once a day and only
     * when the user turned them on. Null when off or already sent today.
     * Totals only: no account, device or connection data.
     */
    fun metricsPayload(): JsonObject? {
        if (!metrics) return null
        val day = LocalDate.now().toString()
        if (runCatching { store.decodeString("metrics_day") }.getOrNull() == day) return null
        val progress = ColituPassport.progress.values
        val levels = JsonArray().apply {
            StampLevel.entries.forEach { level -> add(progress.count { it.level == level }) }
        }
        return JsonObject().apply {
            addProperty("v", 1)
            addProperty("stamps", progress.size)
            addProperty("chapter", if (PassportGoals.chapter3Open) 3 else if (ColituPassport.chapter2Open) 2 else 1)
            add("levels", levels)
            addProperty("expeditions", PassportWeekly.total)
            addProperty("pieces_today", ColituPassport.today.second.coerceIn(0, 2))
            addProperty("opened_today", true)
            addProperty("platform", "android")
        }
    }

    fun metricsSent() {
        runCatching { store.encode("metrics_day", LocalDate.now().toString()) }
    }

    /** Whether the first-visit intro has been shown. */
    var introSeen: Boolean
        get() = runCatching { store.decodeBool("intro_seen", false) }.getOrDefault(false)
        set(value) {
            runCatching { store.encode("intro_seen", value) }
        }
}
