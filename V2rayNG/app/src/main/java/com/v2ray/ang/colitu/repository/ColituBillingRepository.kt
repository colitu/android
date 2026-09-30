package com.v2ray.ang.colitu.repository

import com.v2ray.ang.colitu.api.ColituApiClient
import com.v2ray.ang.colitu.data.ColituSubscription

/**
 * Read-only view of the subscription. Plans are bought and renewed in the
 * customer account on app.colitu.com, never inside the app.
 */
object ColituBillingRepository {

    suspend fun fetchSubscription(): Result<ColituSubscription> =
        when (val result = ColituApiClient.get("/billing/subscription")) {
            is ColituApiClient.ApiResult.Success -> runCatching { ColituSubscription.fromJson(result.data) }
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(result.message))
        }
}
