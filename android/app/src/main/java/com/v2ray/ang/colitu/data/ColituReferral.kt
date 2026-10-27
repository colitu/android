package com.v2ray.ang.colitu.data

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonObject
import com.tencent.mmkv.MMKV

/**
 * Invite (referral) codes. A code is 6-12 characters A-Z0-9; the server
 * ignores unknown ones, so nothing here ever blocks a sign-up.
 */
object ColituInvite {
    const val MIN_LENGTH = 6
    const val MAX_LENGTH = 12
    private const val KEY_PENDING = "invite_pending"

    /** Keeps only A-Z0-9, upper-cased and capped; what a text field should hold. */
    fun sanitize(input: String?): String =
        input.orEmpty().uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(MAX_LENGTH)

    /** The code to send with the registration, or null when there is none worth sending. */
    fun normalize(input: String?): String? =
        sanitize(input).takeIf { it.length in MIN_LENGTH..MAX_LENGTH }

    /**
     * The code in an invite link https://colitu.com/r/CODE (a www. host and a
     * trailing slash or query are fine); null for any other URL.
     */
    fun fromLink(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        return fromParts(uri.scheme, uri.host, uri.pathSegments)
    }

    internal fun fromParts(scheme: String?, host: String?, segments: List<String>): String? {
        if (!scheme.equals("https", ignoreCase = true)) return null
        val h = host.orEmpty().lowercase()
        if (h != "colitu.com" && h != "www.colitu.com") return null
        if (segments.size < 2 || segments[0] != "r") return null
        return normalize(segments[1])
    }

    private val store by lazy { MMKV.mmkvWithID("COLITU_SETTINGS", MMKV.MULTI_PROCESS_MODE) }

    /** The code from an invite link, kept until the account is created. Compose state. */
    var pending: String? by mutableStateOf(runCatching { store.decodeString(KEY_PENDING) }.getOrNull()?.let { normalize(it) })
        private set

    fun keepPending(code: String?) {
        val value = normalize(code)
        pending = value
        runCatching { if (value == null) store.removeValueForKey(KEY_PENDING) else store.encode(KEY_PENDING, value) }
    }

    fun clear() = keepPending(null)
}

/**
 * State of the invite card on the home screen: it appears only after this
 * install has connected once (the flag is never reset) and stays hidden for
 * [DISMISS_MS] after the user closes it.
 */
object ColituInviteCard {
    private const val KEY_ELIGIBLE = "invite_card_eligible"
    private const val KEY_DISMISSED_AT = "invite_card_dismissed_at"
    const val DISMISS_MS = 14L * 24 * 60 * 60 * 1000

    private val store by lazy { MMKV.mmkvWithID("COLITU_SETTINGS", MMKV.MULTI_PROCESS_MODE) }

    /** True once a connection has succeeded on this install. Compose state. */
    var eligible: Boolean by mutableStateOf(runCatching { store.decodeBool(KEY_ELIGIBLE, false) }.getOrDefault(false))
        private set

    /** Epoch ms of the last dismissal, 0 when never. Compose state. */
    var dismissedAt: Long by mutableStateOf(runCatching { store.decodeLong(KEY_DISMISSED_AT, 0L) }.getOrDefault(0L))
        private set

    fun markEligible() {
        if (eligible) return
        eligible = true
        runCatching { store.encode(KEY_ELIGIBLE, true) }
    }

    fun dismiss(now: Long = System.currentTimeMillis()) {
        dismissedAt = now
        runCatching { store.encode(KEY_DISMISSED_AT, now) }
    }

    fun dismissedNow(now: Long = System.currentTimeMillis()): Boolean =
        dismissedAt > 0 && now - dismissedAt < DISMISS_MS

    fun visible(now: Long = System.currentTimeMillis()): Boolean = eligible && !dismissedNow(now)
}

/** One line of the invite history; never carries a name or e-mail. */
data class ColituReferralEvent(
    /** "pending", "rewarded" or "rejected". */
    val status: String,
    val reason: String?,
    val days: Int,
    val createdAt: String?,
)

/** GET /api/v1/referral. */
data class ColituReferral(
    val code: String,
    val link: String,
    val inviteeDays: Int,
    val rewardDays: Int,
    val monthlyCap: Int,
    val invited: Int,
    val pending: Int,
    val rewarded: Int,
    val rejected: Int,
    val daysEarned: Int,
    val rewardedThisMonth: Int,
    val recent: List<ColituReferralEvent>,
) {
    /** Rewards still available this month. */
    val left: Int get() = (monthlyCap - rewardedThisMonth).coerceAtLeast(0)

    /** The link is a https colitu.com one; anything else is never shown on the home card. */
    val linkUsable: Boolean get() {
        val uri = runCatching { Uri.parse(link) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase()
        return uri.scheme.equals("https", ignoreCase = true) && (host == "colitu.com" || host == "www.colitu.com")
    }

    companion object {
        /** From the response root ({"data":{...}}) or the data object itself; null without a link. */
        fun parse(root: JsonObject?): ColituReferral? {
            if (root == null) return null
            val data = if (root.has("data") && root.get("data").isJsonObject) root.getAsJsonObject("data") else root
            fun int(name: String, default: Int = 0): Int =
                runCatching { data.get(name).takeIf { it != null && !it.isJsonNull }?.asInt }.getOrNull() ?: default
            fun str(name: String): String? =
                runCatching { data.get(name).takeIf { it != null && !it.isJsonNull }?.asString }.getOrNull()
            val link = str("link")?.takeIf { it.isNotBlank() } ?: return null
            val recent = runCatching {
                data.getAsJsonArray("recent")?.mapNotNull { element ->
                    val o = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    ColituReferralEvent(
                        status = o.get("status")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                        reason = o.get("reason")?.takeIf { !it.isJsonNull }?.asString,
                        days = o.get("days")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                        createdAt = o.get("created_at")?.takeIf { !it.isJsonNull }?.asString,
                    )
                }
            }.getOrNull().orEmpty()
            return ColituReferral(
                code = str("code").orEmpty(),
                link = link,
                // Fallbacks match the panel's rules since 2026-10-27 (1 day each).
                inviteeDays = int("invitee_days", 1),
                rewardDays = int("reward_days", 1),
                monthlyCap = int("monthly_cap", 6),
                invited = int("invited"),
                pending = int("pending"),
                rewarded = int("rewarded"),
                rejected = int("rejected"),
                daysEarned = int("days_earned"),
                rewardedThisMonth = int("rewarded_this_month"),
                recent = recent,
            )
        }
    }
}
