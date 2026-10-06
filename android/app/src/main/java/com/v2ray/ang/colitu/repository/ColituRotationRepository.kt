package com.v2ray.ang.colitu.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.colitu.api.ColituApiClient
import com.v2ray.ang.colitu.data.ColituRotation
import com.v2ray.ang.colitu.data.ColituRotationPreference
import com.v2ray.ang.colitu.data.ColituRotationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The rotating exit IP (panel contract 2026-10-06, section 4): the account's preference and the status of a connected node. */
object ColituRotationRepository {

    suspend fun fetch(): Result<ColituRotationPreference> = withContext(Dispatchers.IO) {
        when (val result = ColituApiClient.get("/me/rotation")) {
            is ColituApiClient.ApiResult.Success -> runCatching { ColituRotation.parsePreference(result.data) }
                .fold({ Result.success(it) }, { Result.failure(Exception("parse_error")) })
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(if (result.isAuthError) "auth_expired" else result.message))
        }
    }

    /** [countries] empty = the panel's default set (without Russia). 400 INVALID_PREFERENCE comes back as that code. */
    suspend fun save(intervalSeconds: Int, countries: List<String>): Result<ColituRotationPreference> = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("interval_seconds", intervalSeconds)
            add("countries", JsonArray().apply { ColituRotation.normalizeCountries(countries).forEach { add(it) } })
        }
        when (val result = ColituApiClient.put("/me/rotation", body)) {
            is ColituApiClient.ApiResult.Success -> runCatching { ColituRotation.parsePreference(result.data) }
                .fold({ Result.success(it) }, { Result.failure(Exception("parse_error")) })
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(if (result.isAuthError) "auth_expired" else result.message))
        }
    }

    /** Where the rotation of [nodeId] (the node this device is connected to) is. */
    suspend fun status(nodeId: String): Result<ColituRotationStatus?> = withContext(Dispatchers.IO) {
        val path = "/me/rotation/status?node_id=${java.net.URLEncoder.encode(nodeId, "UTF-8").replace("+", "%20")}"
        when (val result = ColituApiClient.get(path)) {
            is ColituApiClient.ApiResult.Success -> Result.success(runCatching { ColituRotation.parseStatus(result.data) }.getOrNull())
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(if (result.isAuthError) "auth_expired" else result.message))
        }
    }
}
