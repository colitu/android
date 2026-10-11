package com.v2ray.ang.colitu.repository

import com.v2ray.ang.colitu.api.ColituApiClient
import com.v2ray.ang.colitu.data.ColituReferral

/** GET /api/v1/referral: the signed-in user's invite link and statistics. */
object ColituReferralRepository {
    suspend fun fetch(): Result<ColituReferral> =
        when (val result = ColituApiClient.get("/referral")) {
            is ColituApiClient.ApiResult.Success ->
                ColituReferral.parse(result.data)?.let { Result.success(it) } ?: Result.failure(Exception("invalid_response"))
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(result.message))
        }
}
