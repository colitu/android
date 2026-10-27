package com.v2ray.ang.colitu.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tencent.mmkv.MMKV

/**
 * Recently connected servers and starred favourites, kept on this device
 * only (nothing goes to the panel). Ids that are no longer in the server
 * list are skipped when shown, so a removed server never appears.
 */
object ColituQuickServers {
    const val MAX_RECENT = 3
    const val MAX_PICKS = 3
    private const val MAX_FAVORITES = 50

    /** [id] first, then the earlier ones without it, at most [max]. */
    fun pushRecent(recent: List<String>, id: String, max: Int = MAX_RECENT): List<String> {
        if (id.isBlank()) return recent.take(max)
        return (listOf(id) + recent.filter { it != id }).take(max)
    }

    /** Adds [id] when missing, removes it when present; new favourites go first. */
    fun toggleFavorite(favorites: List<String>, id: String): List<String> =
        if (id in favorites) favorites.filter { it != id }
        else (listOf(id) + favorites).take(MAX_FAVORITES)

    /** The servers behind [ids] in that order; ids missing from [servers] (removed servers) are dropped. */
    fun resolve(ids: List<String>, servers: List<ColituServer>): List<ColituServer> {
        val byId = servers.associateBy { it.id }
        return ids.distinct().mapNotNull { byId[it] }
    }

    /** Recent servers that are not already favourites. */
    fun recentWithoutFavorites(recent: List<ColituServer>, favorites: List<ColituServer>): List<ColituServer> {
        val fav = favorites.map { it.id }.toSet()
        return recent.filter { it.id !in fav }
    }

    /** The home screen's shortcuts: favourites first, then recent ones, online only, at most [max]. */
    fun picks(favorites: List<ColituServer>, recent: List<ColituServer>, max: Int = MAX_PICKS): List<ColituServer> =
        (favorites + recent).distinctBy { it.id }.filter { it.isAvailable }.take(max)

    fun encode(ids: List<String>): String = ids.joinToString("\n")

    fun decode(raw: String?): List<String> =
        raw.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    // ── Storage (the app's settings store) ─────────────────────────────────

    private const val KEY_RECENT = "quick_recent_servers"
    private const val KEY_FAVORITES = "quick_favorite_servers"
    private val store by lazy { MMKV.mmkvWithID("COLITU_SETTINGS", MMKV.MULTI_PROCESS_MODE) }

    private var recentState by mutableStateOf<List<String>?>(null)
    private var favoriteState by mutableStateOf<List<String>?>(null)

    /** Recently connected server ids, newest first (Compose state). */
    val recentIds: List<String>
        get() = recentState ?: runCatching { decode(store.decodeString(KEY_RECENT)) }.getOrDefault(emptyList()).also { recentState = it }

    /** Starred server ids, newest first (Compose state). */
    val favoriteIds: List<String>
        get() = favoriteState ?: runCatching { decode(store.decodeString(KEY_FAVORITES)) }.getOrDefault(emptyList()).also { favoriteState = it }

    fun isFavorite(id: String): Boolean = id in favoriteIds

    fun recordConnected(id: String) {
        val next = pushRecent(recentIds, id)
        if (next == recentIds) return
        recentState = next
        runCatching { store.encode(KEY_RECENT, encode(next)) }
    }

    fun toggleFavorite(id: String) {
        val next = toggleFavorite(favoriteIds, id)
        favoriteState = next
        runCatching { store.encode(KEY_FAVORITES, encode(next)) }
    }
}
