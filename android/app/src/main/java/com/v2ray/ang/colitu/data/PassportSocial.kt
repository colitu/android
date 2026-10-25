package com.v2ray.ang.colitu.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

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
 * Friends and the passport's own settings, kept on this device; the
 * Companion stamp comes from the server state.
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

    fun updateRanking(on: Boolean) = flag("friends_ranking", on).also { showRanking = on }
    fun updateSound(on: Boolean) = flag("sound", on).also { sound = on }
    fun updateHaptics(on: Boolean) = flag("haptics", on).also { haptics = on }

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

    /** Granted by the server (an invite that became active, or a sign-up with an invite). */
    val companion: Boolean get() = ColituPassport.server?.companion ?: false

    /** Whether the first-visit intro has been shown. */
    var introSeen: Boolean
        get() = runCatching { store.decodeBool("intro_seen", false) }.getOrDefault(false)
        set(value) {
            runCatching { store.encode("intro_seen", value) }
        }
}
