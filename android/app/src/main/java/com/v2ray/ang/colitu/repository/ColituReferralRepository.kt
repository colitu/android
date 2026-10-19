package com.v2ray.ang.colitu.repository

import com.v2ray.ang.colitu.api.ColituApiClient
import com.v2ray.ang.colitu.data.ColituReferral

/** GET /api/v1/referral: the signed-in user's invite link and statistics. */
object ColituReferralRepository {
    @Volatile private var cache: ColituReferral? = null
    @Volatile private var cachedAt = 0L
    private const val CACHE_MS = 10 * 60 * 1000L

    /** The last successful response while it is fresh (10 min), else a new fetch. */
    suspend fun cachedOrFetch(): Result<ColituReferral> {
        val hit = cache
        if (hit != null && System.currentTimeMillis() - cachedAt < CACHE_MS) return Result.success(hit)
        return fetch()
    }

    suspend fun fetch(): Result<ColituReferral> =
        when (val result = ColituApiClient.get("/referral")) {
            is ColituApiClient.ApiResult.Success ->
                ColituReferral.parse(result.data)?.let {
                    cache = it
                    cachedAt = System.currentTimeMillis()
                    Result.success(it)
                } ?: Result.failure(Exception("invalid_response"))
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(result.message))
        }
}
