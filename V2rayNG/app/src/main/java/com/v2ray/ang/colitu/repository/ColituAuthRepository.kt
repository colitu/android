package com.v2ray.ang.colitu.repository

import android.os.Build
import android.provider.Settings
import com.v2ray.ang.AngApplication
import com.v2ray.ang.colitu.l10n.ColituLoc
import java.security.MessageDigest
import com.google.gson.JsonObject
import com.v2ray.ang.colitu.api.ColituApiClient
import com.v2ray.ang.colitu.api.ColituTokenManager
import com.v2ray.ang.colitu.data.ColituAuthResponse
import com.v2ray.ang.colitu.data.ColituUser

object ColituAuthRepository {

    /** Thrown (as a failed result) when the account must confirm its e-mail first. */
    const val EMAIL_NOT_VERIFIED = "EMAIL_NOT_VERIFIED"

    suspend fun login(email: String, password: String): Result<ColituUser> {
        val body = JsonObject().apply {
            addProperty("email", email)
            addProperty("password", password)
        }
        return signIn("/auth/login", body, email, sendCode = true)
    }

    suspend fun register(email: String, password: String): Result<ColituUser> {
        val body = JsonObject().apply {
            addProperty("email", email)
            addProperty("password", password)
            addProperty("locale", ColituLoc.language)
        }
        // Registration already e-mails the first code when verification is on.
        return signIn("/auth/register", body, email, sendCode = false)
    }

    /**
     * Saves the tokens, registers this phone and loads the account. An account
     * that still has to confirm its e-mail keeps its tokens (the verification
     * endpoints need them) and fails with [EMAIL_NOT_VERIFIED].
     */
    private suspend fun signIn(path: String, body: JsonObject, email: String, sendCode: Boolean): Result<ColituUser> {
        return when (val result = ColituApiClient.post(path, body)) {
            is ColituApiClient.ApiResult.Success -> {
                val auth = ColituAuthResponse.fromJson(result.data)
                if (auth.accessToken.isNullOrBlank()) return Result.failure(Exception("auth_expired"))
                ColituTokenManager.saveTokens(auth.accessToken, auth.refreshToken)
                ColituTokenManager.setPendingVerificationEmail(null)
                registerDevice().fold(
                    onSuccess = {
                        runCatching { fetchMe() }.fold(
                            onSuccess = { Result.success(it) },
                            onFailure = { ColituTokenManager.clear(); Result.failure(it) },
                        )
                    },
                    onFailure = { error ->
                        if (error.message == EMAIL_NOT_VERIFIED) {
                            ColituTokenManager.setPendingVerificationEmail(email)
                            // A code sent less than a minute ago is still valid; the
                            // verification screen offers "send again" for the rest.
                            if (sendCode) sendVerificationCode()
                        } else {
                            ColituTokenManager.clear()
                        }
                        Result.failure(error)
                    },
                )
            }
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(result.message))
        }
    }

    /** E-mails a new six-digit code (the panel allows one a minute). */
    suspend fun sendVerificationCode(): Result<Unit> {
        val body = JsonObject().apply { addProperty("locale", ColituLoc.language) }
        return when (val result = ColituApiClient.postRenewing("/auth/email/send", body)) {
            is ColituApiClient.ApiResult.Success -> Result.success(Unit)
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(result.message))
        }
    }

    /**
     * Confirms the code, then finishes what sign-in could not do: registers
     * this phone (the trial starts now) and loads the account.
     */
    suspend fun verifyEmail(code: String): Result<ColituUser> {
        val body = JsonObject().apply { addProperty("code", code) }
        when (val result = ColituApiClient.postRenewing("/auth/email/verify", body)) {
            is ColituApiClient.ApiResult.Error -> return Result.failure(Exception(result.message))
            is ColituApiClient.ApiResult.Success -> Unit
        }
        ColituTokenManager.setPendingVerificationEmail(null)
        return registerDevice().fold(
            onSuccess = { runCatching { fetchMe() } },
            onFailure = { Result.failure(it) },
        )
    }

    /**
     * Finishes sign-in when the address was confirmed somewhere else
     * (colitu.com or another device). Null while it is still unconfirmed.
     */
    suspend fun tryCompleteVerification(): Result<ColituUser?> =
        registerDevice().fold(
            onSuccess = {
                ColituTokenManager.setPendingVerificationEmail(null)
                runCatching { fetchMe() }
            },
            onFailure = { if (it.message == EMAIL_NOT_VERIFIED) Result.success(null) else Result.failure(it) },
        )

    suspend fun refreshToken(): Boolean {
        val refresh = ColituTokenManager.getRefreshToken() ?: return false
        val body = JsonObject().apply { addProperty("refresh_token", refresh) }
        return when (val result = ColituApiClient.post("/auth/refresh", body)) {
            is ColituApiClient.ApiResult.Success -> {
                val auth = ColituAuthResponse.fromJson(result.data)
                if (!auth.accessToken.isNullOrBlank()) {
                    ColituTokenManager.saveTokens(auth.accessToken, auth.refreshToken)
                    true
                } else false
            }
            is ColituApiClient.ApiResult.Error -> false
        }
    }

    suspend fun fetchMe(): ColituUser {
        val json = when (val result = ColituApiClient.get("/me")) {
            is ColituApiClient.ApiResult.Success -> result.data
            is ColituApiClient.ApiResult.Error -> throw Exception(result.message)
        }
        val id = json.tryString("id") ?: throw Exception("INVALID_USER_RESPONSE")
        val email = json.tryString("email") ?: throw Exception("INVALID_USER_RESPONSE")
        val entitlement = (ColituApiClient.get("/me/entitlement") as? ColituApiClient.ApiResult.Success)?.data
        val status = entitlement?.tryString("status") ?: "inactive"
        val active = status == "active" || status == "trialing"
        val traffic = entitlement?.get("traffic")?.takeIf { it.isJsonObject }?.asJsonObject
        val user = ColituUser(
            id = id,
            name = "Colitu",
            email = email,
            plan = entitlement?.tryString("plan") ?: "inactive",
            entitlementStatus = status,
            deviceLimit = entitlement?.tryInt("device_limit") ?: 0,
            expiresAt = entitlement?.tryString("expires_at"),
            trafficUsedBytes = traffic?.tryLong("used_bytes") ?: 0L,
            trafficLimitBytes = traffic?.tryLong("limit_bytes"),
        )
        ColituTokenManager.saveUserInfo(user.id, user.email, user.name, user.plan, user.entitlementStatus)
        return user
    }

    private fun JsonObject.tryString(key: String): String? =
        if (has(key) && !get(key).isJsonNull && get(key).isJsonPrimitive) get(key).asString.takeIf { it.isNotBlank() } else null

    private fun JsonObject.tryLong(key: String): Long? =
        if (has(key) && !get(key).isJsonNull && get(key).isJsonPrimitive) runCatching { get(key).asLong }.getOrNull() else null

    private fun JsonObject.tryInt(key: String): Int? =
        if (has(key) && !get(key).isJsonNull && get(key).isJsonPrimitive) runCatching { get(key).asInt }.getOrNull() else null

    suspend fun logout() {
        val refresh = ColituTokenManager.getRefreshToken()
        if (!refresh.isNullOrBlank()) {
            val body = JsonObject().apply { addProperty("refresh_token", refresh) }
            runCatching { ColituApiClient.post("/auth/logout", body) }
        }
        ColituTokenManager.clear()
    }

    /** Re-sends the device capabilities (e.g. after an update adds a transport). */
    suspend fun refreshDeviceCapabilities(): Result<String> = registerDevice()

    /**
     * A hash of ANDROID_ID, which stays the same across reinstalls of the app.
     * The panel only uses it to stop the free trial being claimed again from
     * the same phone; the raw id never leaves the device.
     */
    private fun hardwareId(): String? {
        val raw = runCatching {
            Settings.Secure.getString(AngApplication.application.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest("colitu-hardware-v1|$raw".toByteArray())
        return "android-" + digest.joinToString("") { "%02x".format(it) }
    }

    private suspend fun registerDevice(): Result<String> {
        val body=JsonObject().apply {
            addProperty("device_key",ColituTokenManager.getDeviceKey())
            addProperty("name","${Build.MANUFACTURER} ${Build.MODEL}".trim().take(100))
            addProperty("platform","android")
            addProperty("app_version",com.v2ray.ang.BuildConfig.VERSION_NAME)
            addProperty("os_version",Build.VERSION.RELEASE)
            hardwareId()?.let { addProperty("hardware_id", it) }
            add("capabilities",JsonObject().apply {
                add("config_formats",com.google.gson.JsonArray().apply{add("xray-mobile-v1")})
                add("protocols",com.google.gson.JsonArray().apply{add("vless-reality");add("hysteria2");add("trojan");add("shadowsocks")})
            })
        }
        return when(val result=ColituApiClient.postRenewing("/devices/register",body)){
            is ColituApiClient.ApiResult.Success->{val id=result.data.tryString("id");if(id.isNullOrBlank())Result.failure(Exception("DEVICE_REGISTRATION_INVALID"))else{ColituTokenManager.saveDeviceId(id);Result.success(id)}}
            is ColituApiClient.ApiResult.Error->Result.failure(Exception(result.message))
        }
    }
}
