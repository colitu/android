package com.v2ray.ang.colitu.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant

/**
 * Adaptive Connect 3.0, recovery set (`GET /client/recovery`): a few complete
 * config envelopes (one per country first, each with every transport of its
 * server) that let a connect succeed when no API base can be reached and the
 * regular cached config is no use. Pure decisions only; storage and the
 * network are in ColituServerRepository, the connect path in ColituController.
 */
data class ColituRecoverySetData(
    val generatedAt: Instant,
    val recoveryUntil: Instant,
    /** Config envelopes in the panel's order, each with a `server.id`. */
    val configs: List<JsonObject>,
)

object ColituRecoverySet {
    const val FETCH_PATH = "/client/recovery"

    /** The fetch path; `?client_country=CC` (the last country the panel reported) only when it is a two-letter code. */
    fun fetchPath(clientCountry: String?): String {
        val cc = clientCountry?.trim()?.uppercase()?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
        return if (cc == null) FETCH_PATH else "$FETCH_PATH?client_country=$cc"
    }

    /** The panel sends at most this many envelopes. */
    const val MAX_CONFIGS = 4
    /** A stored set older than this is replaced. */
    const val REFRESH_AFTER_MS = 24L * 60 * 60 * 1000
    /** At most one fetch attempt per this long, successful or not. */
    const val MIN_ATTEMPT_GAP_MS = 6L * 60 * 60 * 1000

    /**
     * The set in [raw], or null when it is not usable: not a JSON object, bad
     * `generated_at` / `recovery_until`, or no envelope with a server id.
     * Whether an envelope renders is only known at use time.
     */
    fun parse(raw: String?): ColituRecoverySetData? {
        if (raw.isNullOrBlank()) return null
        val root = runCatching { JsonParser.parseString(raw) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val generatedAt = root.instant("generated_at") ?: return null
        val until = root.instant("recovery_until") ?: return null
        val array = root.get("configs")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val configs = array.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            .filter { serverId(it) != null }
            .take(MAX_CONFIGS)
        if (configs.isEmpty()) return null
        return ColituRecoverySetData(generatedAt, until, configs)
    }

    fun serverId(envelope: JsonObject): String? =
        envelope.getAsJsonObject("server")?.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    /** `recovery_until` replaces the envelopes' own offline grace: past it the set is not used (and is deleted). */
    fun isExpired(set: ColituRecoverySetData, now: Instant): Boolean = !now.isBefore(set.recoveryUntil)

    /**
     * True when a connect may turn to the set: automatic server mode (not a
     * chosen server, not multihop), the config could not be fetched because
     * every API base failed at network level (no HTTP answer at all), and the
     * regular cached config is none, expired or has failed.
     */
    fun shouldUse(automatic: Boolean, apiNetworkLevelFailure: Boolean, cacheUsable: Boolean): Boolean =
        automatic && apiNetworkLevelFailure && !cacheUsable

    /**
     * Before an automatic connect gives up, whatever the error is (verify
     * failed, fatal, every server failed, budget used up): one run through the
     * set while no API base answers and the set was not tried in this connect.
     */
    fun shouldTryAtGiveUp(automatic: Boolean, apiUnreachable: Boolean, alreadyTried: Boolean): Boolean =
        shouldUse(automatic, apiUnreachable, cacheUsable = false) && !alreadyTried

    /** ColituApiClient reports "every base failed before an answer" as code -1; 4xx/5xx answers are not network-level. */
    fun isNetworkLevel(code: Int): Boolean = code == -1

    /** The envelopes to try, in the set's order, without servers that already failed in this connect. */
    fun serversToTry(set: ColituRecoverySetData, failedServers: Collection<String>): List<JsonObject> =
        set.configs.filter { serverId(it) !in failedServers }

    /** A fetch is due without a stored set, or when its `generated_at` is older than [REFRESH_AFTER_MS]; never within [MIN_ATTEMPT_GAP_MS] of the last attempt. */
    fun refreshDue(stored: ColituRecoverySetData?, lastAttemptMs: Long, nowMs: Long): Boolean {
        // A clock that moved back must not block the fetch for days.
        if (lastAttemptMs in 1..nowMs && nowMs - lastAttemptMs < MIN_ATTEMPT_GAP_MS) return false
        if (stored == null) return true
        return nowMs - stored.generatedAt.toEpochMilli() >= REFRESH_AFTER_MS
    }

    enum class FetchError { DeleteSet, KeepSet }

    /** 401/403 (signed out, device revoked, plan ended) delete the stored set; network errors and 5xx keep it. */
    fun onFetchError(code: Int, isAuthError: Boolean): FetchError =
        if (isAuthError || code == 401 || code == 403) FetchError.DeleteSet else FetchError.KeepSet

    private fun JsonObject.instant(key: String): Instant? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.let { runCatching { Instant.parse(it) }.getOrNull() }
}
