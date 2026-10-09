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

    /** The password was right; the account wants its 2FA code ([pendingMfa] holds the challenge). */
    const val MFA_REQUIRED = "MFA_REQUIRED"

    /** Sign-in requests tell the panel this build has the 2FA code step. */
    private val FEATURES = mapOf("X-Colitu-Features" to "mfa")

    /** A 2FA challenge waiting for its code; only in memory, never stored. */
    data class MfaChallenge(
        val token: String,
        val email: String,
        val expiresAtMs: Long,
        val sendCode: Boolean,
        /** "totp" (authenticator app / recovery code) or "email" (unfamiliar-country sign-in code). */
        val method: String = "totp",
    ) {
        val isEmailCode: Boolean get() = method == "email"
    }

    /** A failed code step; [attemptsLeft] from the panel's 401 MFA_INVALID_CODE body. */
    class MfaException(code: String, val attemptsLeft: Int?) : Exception(code)

    @Volatile var pendingMfa: MfaChallenge? = null
        private set

    fun clearMfa() {
        pendingMfa = null
    }

    /**
     * The challenge from a 403 MFA_REQUIRED body, or null when it carries no
     * token. [nowMs] and the panel's mfa_expires_in (default 300 s) give the
     * deadline the code screen counts down to.
     */
    internal fun mfaChallengeOf(body: JsonObject?, email: String, sendCode: Boolean, nowMs: Long = System.currentTimeMillis()): MfaChallenge? {
        val token = body?.tryString("mfa_token") ?: return null
        val ttl = (body.tryLong("mfa_expires_in") ?: 300L).coerceIn(30L, 3600L)
        val method = if (body.tryString("mfa_method")?.trim()?.lowercase() == "email") "email" else "totp"
        return MfaChallenge(token, email, nowMs + ttl * 1000, sendCode, method)
    }

    /** What the code field accepts: 6 digits, or a recovery code (letters/digits, dashes and spaces dropped). */
    internal fun normalizeMfaCode(input: String, recovery: Boolean): String? {
        if (!recovery) return input.filter { it.isDigit() }.takeIf { it.length == 6 }
        val code = input.filter { it.isLetterOrDigit() }.lowercase()
        return code.takeIf { it.length in 8..32 }
    }

    /**
     * Second sign-in step: the 6-digit code from the authenticator app or a
     * recovery code. Success finishes the sign-in exactly like a password
     * sign-in. MFA_TOKEN_EXPIRED drops the challenge (back to the password).
     */
    suspend fun completeMfa(code: String): Result<ColituUser> {
        val challenge = pendingMfa ?: return Result.failure(Exception("MFA_TOKEN_EXPIRED"))
        val body = JsonObject().apply {
            addProperty("mfa_token", challenge.token)
            addProperty("code", code)
        }
        return when (val result = ColituApiClient.post("/auth/login/mfa", body, FEATURES)) {
            is ColituApiClient.ApiResult.Success -> {
                pendingMfa = null
                finishSignIn(result.data, challenge.email, challenge.sendCode)
            }
            is ColituApiClient.ApiResult.Error -> {
                if (result.message == "MFA_TOKEN_EXPIRED") pendingMfa = null
                Result.failure(MfaException(result.message, result.body?.tryInt("attempts_left")))
            }
        }
    }

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
        pendingMfa = null
        return when (val result = ColituApiClient.post(path, body, FEATURES)) {
            is ColituApiClient.ApiResult.Success -> finishSignIn(result.data, email, sendCode)
            is ColituApiClient.ApiResult.Error -> {
                if (result.message == MFA_REQUIRED) {
                    val challenge = mfaChallengeOf(result.body, email, sendCode)
                        ?: return Result.failure(Exception("MFA_REQUIRED_UPDATE_APP"))
                    pendingMfa = challenge
                }
                Result.failure(Exception(result.message))
            }
        }
    }

    private suspend fun finishSignIn(data: JsonObject, email: String, sendCode: Boolean): Result<ColituUser> {
        val auth = ColituAuthResponse.fromJson(data)
        if (auth.accessToken.isNullOrBlank()) return Result.failure(Exception("auth_expired"))
        ColituTokenManager.saveTokens(auth.accessToken, auth.refreshToken)
        ColituTokenManager.setPendingVerificationEmail(null)
        return registerDevice().fold(
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

    /** E-mails a six-digit password reset code. Unknown addresses get the same answer. */
    suspend fun requestPasswordReset(email: String): Result<Unit> {
        val body = JsonObject().apply {
            addProperty("email", email)
            addProperty("locale", ColituLoc.language)
        }
        return when (val result = ColituApiClient.post("/auth/password/forgot", body)) {
            is ColituApiClient.ApiResult.Success -> Result.success(Unit)
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(result.message))
        }
    }

    /** Sets a new password with the e-mailed code; every other session ends and this phone signs in. */
    suspend fun resetPassword(email: String, code: String, password: String): Result<ColituUser> {
        val body = JsonObject().apply {
            addProperty("email", email)
            addProperty("code", code)
            addProperty("password", password)
        }
        return signIn("/auth/password/reset", body, email, sendCode = true)
    }

    // ── Signing a TV in with a phone ────────────────────────────────────────────

    /** What the TV shows: the code, the QR link and the secret it polls with. */
    data class LinkStart(val code: String, val url: String, val pollToken: String, val expiresInSeconds: Long, val intervalSeconds: Long)

    /** The device asking to be signed in, shown on the phone before approving. */
    data class LinkRequest(val code: String, val deviceName: String, val platform: String, val country: String?)

    sealed class LinkPoll {
        data object Pending : LinkPoll()
        data object Expired : LinkPoll()
        data object Denied : LinkPoll()
        data class SignedIn(val user: ColituUser) : LinkPoll()
        data class Failed(val code: String) : LinkPoll()
    }

    suspend fun startLink(): Result<LinkStart> {
        val body = JsonObject().apply {
            addProperty("device_name", "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(100))
            addProperty("platform", "android")
        }
        return when (val result = ColituApiClient.post("/auth/link/start", body)) {
            is ColituApiClient.ApiResult.Success -> {
                val d = result.data
                val code = d.tryString("code")
                val url = d.tryString("url")
                val poll = d.tryString("poll_token")
                if (code == null || url == null || poll == null) Result.failure(Exception("parse_error"))
                else Result.success(LinkStart(code, url, poll, d.tryLong("expires_in") ?: 600, (d.tryLong("interval") ?: 3).coerceIn(2, 15)))
            }
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(result.message))
        }
    }

    /** One poll by the TV: pending until a phone decides, then signs in like a normal sign-in. */
    suspend fun pollLink(pollToken: String): LinkPoll {
        val body = JsonObject().apply { addProperty("poll_token", pollToken) }
        return when (val result = ColituApiClient.post("/auth/link/poll", body)) {
            is ColituApiClient.ApiResult.Success ->
                if (result.statusCode == 202) LinkPoll.Pending
                else finishSignIn(result.data, "", sendCode = false).fold(
                    onSuccess = { LinkPoll.SignedIn(it) },
                    onFailure = { LinkPoll.Failed(it.message.orEmpty()) },
                )
            is ColituApiClient.ApiResult.Error -> when (result.message) {
                "LINK_EXPIRED", "LINK_NOT_FOUND" -> LinkPoll.Expired
                "LINK_DENIED" -> LinkPoll.Denied
                else -> LinkPoll.Failed(result.message)
            }
        }
    }

    /** Reads the code out of a scanned QR (https://colitu.com/link?c=CODE) or a typed "ABCD-2345". */
    fun linkCodeOf(value: String): String? {
        val raw = value.trim()
        val candidate = if (raw.contains("/")) {
            val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return null
            val host = uri.host.orEmpty().lowercase()
            if (uri.scheme != "https" || !(host == "colitu.com" || host.endsWith(".colitu.com")) || uri.path != "/link") return null
            uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("c=") }?.removePrefix("c=") ?: return null
        } else raw
        val code = candidate.uppercase().filter { it != '-' && it != ' ' }
        return code.takeIf { it.length == 8 && it.all { c -> c in LINK_ALPHABET } }
    }

    private const val LINK_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    suspend fun lookupLink(code: String): Result<LinkRequest> {
        val body = JsonObject().apply { addProperty("code", code) }
        return when (val result = ColituApiClient.post("/auth/link/lookup", body)) {
            is ColituApiClient.ApiResult.Success -> {
                val d = result.data
                Result.success(LinkRequest(d.tryString("code") ?: code, d.tryString("device_name").orEmpty(), d.tryString("platform").orEmpty(), d.tryString("country")))
            }
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(result.message))
        }
    }

    suspend fun decideLink(code: String, approve: Boolean): Result<Unit> {
        val body = JsonObject().apply { addProperty("code", code) }
        return when (val result = ColituApiClient.post(if (approve) "/auth/link/approve" else "/auth/link/deny", body)) {
            is ColituApiClient.ApiResult.Success -> Result.success(Unit)
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
                add("protocols",com.google.gson.JsonArray().apply{add("vless-reality");add("vless-xhttp");add("hysteria2");add("trojan");add("shadowsocks")})
            })
        }
        return when(val result=ColituApiClient.postRenewing("/devices/register",body)){
            is ColituApiClient.ApiResult.Success->{val id=result.data.tryString("id");if(id.isNullOrBlank())Result.failure(Exception("DEVICE_REGISTRATION_INVALID"))else{ColituTokenManager.saveDeviceId(id);Result.success(id)}}
            is ColituApiClient.ApiResult.Error->Result.failure(Exception(result.message))
        }
    }
}
