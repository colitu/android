package com.v2ray.ang.colitu.data

import com.google.gson.JsonObject
import com.tencent.mmkv.MMKV
import com.v2ray.ang.colitu.api.ColituApiClient
import com.v2ray.ang.colitu.api.ColituTokenManager

/** One question from GET /client/survey. */
data class ColituSurvey(val kind: String, val conversationId: String?) {
    companion object {
        val KINDS = setOf("csat", "nps", "pmf", "churn")
        val PMF_ANSWERS = listOf("very", "somewhat", "not")
        val CHURN_REASONS = listOf("price", "speed", "blocked", "not_needed", "other_vpn", "technical", "other")

        /** {"survey": null} or {"survey": {"kind", "conversation_id"}}; null for none or an unknown kind. */
        fun parse(root: JsonObject?): ColituSurvey? {
            val survey = root?.get("survey")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val kind = survey.get("kind")?.takeIf { it.isJsonPrimitive }?.asString?.lowercase() ?: return null
            if (kind !in KINDS) return null
            val conversation = survey.get("conversation_id")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            return ColituSurvey(kind, conversation)
        }

        /**
         * The body of POST /client/survey. [score] is for csat (1-5) and nps (0-10), [answer] for pmf,
         * [reason] for churn; a blank [comment] is left out; the conversation id travels with csat only.
         */
        fun body(
            survey: ColituSurvey,
            dismissed: Boolean,
            score: Int? = null,
            answer: String? = null,
            reason: String? = null,
            comment: String? = null,
        ): JsonObject = JsonObject().apply {
            addProperty("kind", survey.kind)
            addProperty("platform", "android")
            addProperty("dismissed", dismissed)
            if (!dismissed) {
                score?.let { addProperty("score", it) }
                answer?.let { addProperty("answer", it) }
                reason?.let { addProperty("reason", it) }
                comment?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("comment", it.take(1000)) }
            }
            if (survey.kind == "csat") survey.conversationId?.let { addProperty("conversation_id", it) }
        }
    }
}

/** Survey settings, the once-a-day gate and the two calls. Surveys are separate from the statistics setting. */
object ColituSurveys {
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    private const val KEY_ENABLED = "surveys_enabled"
    private const val KEY_LAST_CHECK = "survey_last_check"
    private val store by lazy { MMKV.mmkvWithID("COLITU_SETTINGS", MMKV.MULTI_PROCESS_MODE) }

    /** Default on; off stops fetching surveys. */
    var enabled: Boolean
        get() = runCatching { store.decodeBool(KEY_ENABLED, true) }.getOrDefault(true)
        set(value) { runCatching { store.encode(KEY_ENABLED, value) } }

    /** At most one check per day (a clock set back also allows one). */
    fun due(lastCheckMs: Long, nowMs: Long): Boolean = lastCheckMs <= 0L || nowMs < lastCheckMs || nowMs - lastCheckMs >= CHECK_INTERVAL_MS

    /**
     * The survey to show now, or null: surveys off, signed out, already checked today,
     * nothing pending or any failure. A completed check (even "none") counts for the day.
     */
    suspend fun checkNow(nowMs: Long = System.currentTimeMillis()): ColituSurvey? {
        if (!enabled || !ColituTokenManager.isLoggedIn()) return null
        val last = runCatching { store.decodeLong(KEY_LAST_CHECK, 0L) }.getOrDefault(0L)
        if (!due(last, nowMs)) return null
        val result = runCatching { ColituApiClient.get("/client/survey") }.getOrNull()
        if (result !is ColituApiClient.ApiResult.Success) return null
        runCatching { store.encode(KEY_LAST_CHECK, nowMs) }
        return ColituSurvey.parse(result.data)
    }

    /** True when the panel took the answer (204). Failures are ignored by the caller. */
    suspend fun submit(body: JsonObject): Boolean =
        runCatching { ColituApiClient.post("/client/survey", body) }.getOrNull() is ColituApiClient.ApiResult.Success
}
