package com.v2ray.ang.colitu.repository

import android.net.Uri
import com.google.gson.JsonObject
import com.v2ray.ang.colitu.api.ColituApiClient
import com.v2ray.ang.colitu.data.ColituNotice
import com.v2ray.ang.colitu.data.ColituNotices

/**
 * GET /client/notices and POST /client/notices/{id}/events. Both are best
 * effort: an older panel (404) or any error means "no notices", silently.
 */
object ColituNoticeRepository {
    const val EVENT_SEEN = "seen"
    const val EVENT_CLICKED = "clicked"
    const val EVENT_DISMISSED = "dismissed"

    /** Notices for [lang] (tr, en or ru); null when they could not be fetched. */
    suspend fun fetch(lang: String): List<ColituNotice>? =
        parsed(runCatching { ColituApiClient.get(noticesPath(lang)) }.getOrNull())

    /**
     * Same list for the VPN process: stored access token only, never a token
     * refresh or any change of the session (see [ColituApiClient.getPassive]).
     */
    suspend fun fetchPassive(lang: String): List<ColituNotice>? =
        parsed(runCatching { ColituApiClient.getPassive(noticesPath(lang)) }.getOrNull())

    private fun noticesPath(lang: String) = "/client/notices?lang=${Uri.encode(lang)}"

    private fun parsed(result: ColituApiClient.ApiResult<com.google.gson.JsonObject>?): List<ColituNotice>? =
        if (result is ColituApiClient.ApiResult.Success) ColituNotices.parse(result.data) else null

    /** True when the panel accepted the event. */
    suspend fun postEvent(id: String, event: String): Boolean {
        val body = JsonObject().apply { addProperty("event", event) }
        val result = runCatching { ColituApiClient.post("/client/notices/${Uri.encode(id)}/events", body) }.getOrNull()
        return result is ColituApiClient.ApiResult.Success
    }
}
