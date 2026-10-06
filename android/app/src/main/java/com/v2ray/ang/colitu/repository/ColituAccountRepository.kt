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
            // For this phone the caller ends the session (ColituController.onCurrentDeviceRemoved).
            is ColituApiClient.ApiResult.Success -> Result.success(Unit)
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(result.message))
        }
    }

    /**
     * Makes this device the active one when the plan allows fewer devices
     * (POST /devices/{id}/activate); the panel pauses another one instead.
     */
    suspend fun activateThisDevice(): Result<Unit> {
        val id = ColituTokenManager.getDeviceId() ?: return Result.failure(Exception("DEVICE_REQUIRED"))
        return activateDevice(id)
    }

    /** Makes a paused device (this one or another) active; the panel pauses the least recently used one. */
    suspend fun activateDevice(id: String): Result<Unit> {
        return when (val result = ColituApiClient.post("/devices/${encode(id)}/activate", com.google.gson.JsonObject())) {
            is ColituApiClient.ApiResult.Success -> Result.success(Unit)
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(result.message))
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
