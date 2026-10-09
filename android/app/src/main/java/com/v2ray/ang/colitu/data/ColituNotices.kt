package com.v2ray.ang.colitu.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.time.Instant
import java.time.OffsetDateTime

enum class ColituNoticeLevel { Info, Promo, Warning, Critical }

/** One announcement from GET /client/notices. */
data class ColituNotice(
    val id: String,
    val kind: String,
    val level: ColituNoticeLevel,
    val title: String,
    val body: String,
    /** Label of the optional action button; null when the panel sent none. */
    val button: String?,
    /** Always https (see [ColituNotices.safeUrl]); null when absent or not allowed. */
    val url: String?,
    val push: Boolean,
    val expiresAt: Instant?,
)

/** Parsing and selection of notices. Pure, no Android types. */
object ColituNotices {
    /** Longest id list kept on the device for each persisted set. */
    const val MAX_IDS = 200

    /**
     * Notices of the response, in the panel's order. Entries without an id or
     * title, with a non-https link target, or already expired are left out;
     * a link that is not https is dropped from the entry, not the entry itself.
     * Anything unexpected (null, no "notices" array) yields an empty list.
     */
    fun parse(root: JsonObject?, now: Instant = Instant.now()): List<ColituNotice> {
        val array = root?.get("notices")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val seen = HashSet<String>()
        return array.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = obj.str("id")?.takeIf { it.isNotBlank() && '\n' !in it && it.length <= 200 } ?: return@mapNotNull null
            val title = obj.str("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            val expires = parseTime(obj.str("expires_at"))
            if (expires != null && !expires.isAfter(now)) return@mapNotNull null
            ColituNotice(
                id = id,
                kind = obj.str("kind")?.trim().orEmpty(),
                level = levelOf(obj.str("level")),
                title = title,
                body = obj.str("body")?.trim().orEmpty(),
                button = obj.str("button")?.trim()?.takeIf { it.isNotEmpty() },
                url = safeUrl(obj.str("url")),
                push = obj.get("push")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true,
                expiresAt = expires,
            )
        }
    }

    fun levelOf(value: String?): ColituNoticeLevel = when (value?.trim()?.lowercase()) {
        "critical" -> ColituNoticeLevel.Critical
        "warning" -> ColituNoticeLevel.Warning
        "promo" -> ColituNoticeLevel.Promo
        else -> ColituNoticeLevel.Info
    }

    /** The first notice, in the panel's order, that is not dismissed and has not expired. */
    fun pickNext(notices: List<ColituNotice>, dismissed: Set<String>, now: Instant = Instant.now()): ColituNotice? =
        notices.firstOrNull { it.id !in dismissed && it.expiresAt?.isAfter(now) != false }

    /** Notices that ask for a system notification and were not notified yet. */
    fun pendingPush(notices: List<ColituNotice>, notified: Set<String>, now: Instant = Instant.now()): List<ColituNotice> =
        notices.filter { it.push && it.id !in notified && it.expiresAt?.isAfter(now) != false }

    /** Only https links are opened; anything else (intent:, javascript:, http:) is ignored. */
    fun safeUrl(url: String?): String? {
        val value = url?.trim().orEmpty()
        if (value.length > 2048 || !value.startsWith("https://", ignoreCase = true)) return null
        if (value.length <= "https://".length || value.any { it.isWhitespace() }) return null
        return value
    }

    /** [ids] with [id] appended (moved to the end when present), oldest dropped beyond [max]. */
    fun appendCapped(ids: List<String>, id: String, max: Int = MAX_IDS): List<String> {
        val next = ids.filter { it != id } + id
        return if (next.size > max) next.takeLast(max) else next
    }

    /** Id lists live in one string, one id per line (ids never contain a line break). */
    fun encodeIds(ids: List<String>): String = ids.joinToString("\n")

    fun decodeIds(raw: String?): List<String> =
        raw?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeLast(MAX_IDS).orEmpty()

    private fun parseTime(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(value.trim()).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(value.trim()) }.getOrNull()
    }

    private fun JsonObject.str(key: String): String? {
        val element: JsonElement = get(key) ?: return null
        return if (element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else null
    }
}

/** Persistent id list on top of a plain key-value store (MMKV in the app, a map in tests). */
class ColituIdSet(private val store: Store, private val key: String) {
    interface Store {
        fun read(key: String): String?
        fun write(key: String, value: String)
    }

    @Synchronized
    fun all(): List<String> = ColituNotices.decodeIds(store.read(key))

    @Synchronized
    fun contains(id: String): Boolean = id in all()

    /** Adds [id]; true when it was not there before. */
    @Synchronized
    fun add(id: String): Boolean {
        val current = all()
        if (id in current) return false
        store.write(key, ColituNotices.encodeIds(ColituNotices.appendCapped(current, id)))
        return true
    }
}
