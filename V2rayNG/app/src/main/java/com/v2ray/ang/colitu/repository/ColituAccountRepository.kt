package com.v2ray.ang.colitu.repository

import com.v2ray.ang.colitu.api.ColituApiClient
import com.v2ray.ang.colitu.api.ColituTokenManager
import com.v2ray.ang.colitu.data.ColituDevice
import java.net.URLEncoder

object ColituAccountRepository {

    suspend fun fetchDevices(): Result<List<ColituDevice>> {
        val path = "/devices"
        return when (val result = ColituApiClient.get(path)) {
            is ColituApiClient.ApiResult.Success -> {
                val array = if (result.data.has("data") && result.data.get("data").isJsonArray) result.data.getAsJsonArray("data") else null
                val devices = array?.mapNotNull { element ->
                    if (element.isJsonObject) ColituDevice.fromJson(
                        element.asJsonObject,
                        currentDeviceId = ColituTokenManager.getDeviceId(),
                    ) else null
                }.orEmpty()
                Result.success(devices)
            }
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(result.message))
        }
    }

    suspend fun disconnectDevice(deviceId: String): Result<Unit> {
        val encodedId = encode(deviceId)
        val path = "/devices/$encodedId"
        return when (val result = ColituApiClient.delete(path)) {
            is ColituApiClient.ApiResult.Success -> {
                if (deviceId == ColituTokenManager.getDeviceId()) ColituTokenManager.clear()
                Result.success(Unit)
            }
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(result.message))
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
