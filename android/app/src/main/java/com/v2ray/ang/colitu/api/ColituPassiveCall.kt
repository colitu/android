package com.v2ray.ang.colitu.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Reading answers of "passive" API calls: calls made with the stored access
 * token as it is, from the VPN process (`:RunSoLibV2RayDaemon`).
 *
 * That process never owns the session. Rotating the refresh token there would
 * invalidate the one the main process holds and sign the user out, so a
 * passive call must never refresh, retry through the authenticator, clear the
 * tokens or raise an auth event. A 401/403 (or no token at all) is just an
 * error result that the caller drops silently. Pure: no Android types, no
 * access to [ColituTokenManager] or [ColituAuthEvents].
 */
internal object ColituPassiveCall {
    fun result(code: Int, body: String?): ColituApiClient.ApiResult<JsonObject> {
        if (code !in 200..299) {
            return ColituApiClient.ApiResult.Error(code, "api_error", isAuthError = code == 401 || code == 403)
        }
        if (code == 204 || body.isNullOrBlank()) return ColituApiClient.ApiResult.Success(JsonObject(), code)
        val parsed = runCatching { JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()
            ?: return ColituApiClient.ApiResult.Error(code, "parse_error")
        return ColituApiClient.ApiResult.Success(parsed, code)
    }

    val noToken = ColituApiClient.ApiResult.Error(401, "no_token", isAuthError = true)
}
