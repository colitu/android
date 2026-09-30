package com.v2ray.ang.colitu.api

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tencent.mmkv.MMKV
import com.v2ray.ang.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

object ColituApiClient {

    val BASE_URL: String get() = BuildConfig.COLITU_API_BASE_URL
    private const val MAX_RETRY = 2
    private const val TAG = "ColituApiClient"
    private const val REFRESH_DEBOUNCE_MS = 10_000L
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    // Main client — goes through ColituAuthInterceptor (injects Bearer + device headers)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .addInterceptor(ColituAuthInterceptor())
        .build()

    // Refresh client — no auth interceptor to prevent recursive 401 loops
    private val refreshClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    // Serializes concurrent token refresh attempts; debounce avoids redundant calls
    private val refreshMutex = Mutex()
    @Volatile private var lastSuccessfulRefreshMs = 0L
    @Volatile private var lastSuccessfulRefreshToken: String? = null

    // Global diagnostic state
    private var configFailCount = 0
    private var serverListFailed = false
    @Volatile private var lastFriendlyError: String? = null

    // Read selected server ID for failure logs without importing ColituServerRepository
    private val serverStore by lazy {
        try { MMKV.mmkvWithID("COLITU_SERVERS", MMKV.MULTI_PROCESS_MODE) } catch (_: Exception) { null }
    }

    sealed class ApiResult<out T> {
        data class Success<T>(
            val data: T,
            val statusCode: Int = 200,
            val etag: String? = null,
        ) : ApiResult<T>()
        data class Error(
            val code: Int,
            val message: String,
            val isAuthError: Boolean = false
        ) : ApiResult<Nothing>()
    }

    // ── Public API ──────────────────────────────────────────────────────────────

    suspend fun get(
        path: String,
        headers: Map<String, String> = emptyMap(),
    ): ApiResult<JsonObject> = withContext(Dispatchers.IO) {
        val url = buildUrl(path)
        val builder = Request.Builder().url(url).get()
        headers.forEach(builder::header)
        executeWithRetry(builder.build(), url, attempt = 0, allowRefresh = true)
    }

    suspend fun post(path: String, body: JsonObject): ApiResult<JsonObject> = withContext(Dispatchers.IO) {
        val url = buildUrl(path)
        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        executeWithRetry(req, url, attempt = 0, allowRefresh = true)
    }

    suspend fun patch(path: String, body: JsonObject): ApiResult<JsonObject> = withContext(Dispatchers.IO) {
        val url = buildUrl(path)
        val req = Request.Builder()
            .url(url)
            .patch(body.toString().toRequestBody(JSON_TYPE))
            .build()
        executeWithRetry(req, url, attempt = 0, allowRefresh = true)
    }

    suspend fun put(path: String, body: JsonObject): ApiResult<JsonObject> = withContext(Dispatchers.IO) {
        val url = buildUrl(path)
        val req = Request.Builder().url(url).put(body.toString().toRequestBody(JSON_TYPE)).build()
        executeWithRetry(req, url, attempt = 0, allowRefresh = true)
    }

    suspend fun delete(path: String): ApiResult<JsonObject> = withContext(Dispatchers.IO) {
        val url = buildUrl(path)
        executeWithRetry(Request.Builder().url(url).delete().build(), url, attempt = 0, allowRefresh = true)
    }

    /**
     * POST for requests that are safe to repeat after the session was renewed
     * (verification codes, support messages the user sees fail): an expired
     * access token is refreshed once and the request sent again.
     */
    suspend fun postRenewing(path: String, body: JsonObject): ApiResult<JsonObject> {
        val first = post(path, body)
        if (first !is ApiResult.Error || first.code != 401) return first
        val renewed = refreshMutex.withLock { attemptTokenRefresh() }
        return if (renewed) post(path, body) else first
    }

    /** multipart/form-data with a JSON "payload" part and "file" parts (support messages). */
    suspend fun postMultipart(path: String, payload: JsonObject, files: List<UploadFile>): ApiResult<JsonObject> = withContext(Dispatchers.IO) {
        fun request(): Request {
            val form = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("payload", null, payload.toString().toRequestBody(JSON_TYPE))
            files.forEach { file ->
                form.addFormDataPart("file", file.name, file.bytes.toRequestBody("application/octet-stream".toMediaType()))
            }
            return Request.Builder().url(buildUrl(path)).post(form.build()).build()
        }
        val url = buildUrl(path)
        val first = executeWithRetry(request(), url, attempt = 0, allowRefresh = false)
        if (first !is ApiResult.Error || first.code != 401) return@withContext first
        val renewed = refreshMutex.withLock { attemptTokenRefresh() }
        if (renewed) executeWithRetry(request(), url, attempt = 0, allowRefresh = false) else first
    }

    /** Raw bytes of an authorized download (support attachments); null on failure. */
    suspend fun getBytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        suspend fun fetch(): Pair<Int, ByteArray?> = runCatching {
            httpClient.newCall(Request.Builder().url(buildUrl(path)).get().build()).execute().use { response ->
                response.code to (if (response.isSuccessful) response.body.bytes() else null)
            }
        }.getOrDefault(-1 to null)
        val (code, bytes) = fetch()
        if (code != 401) return@withContext bytes
        if (refreshMutex.withLock { attemptTokenRefresh() }) fetch().second else null
    }

    class UploadFile(val name: String, val bytes: ByteArray)

    // ── URL builder ─────────────────────────────────────────────────────────────

    private fun buildUrl(path: String): String {
        val base = BASE_URL.trimEnd('/')
        val p = if (path.startsWith('/')) path else "/$path"
        return "$base$p"
    }

    // ── Core execution ──────────────────────────────────────────────────────────

    private suspend fun executeWithRetry(
        req: Request,
        url: String,
        attempt: Int,
        allowRefresh: Boolean
    ): ApiResult<JsonObject> = try {
        val response = httpClient.newCall(req).execute()
        handleResponse(response, req, url, attempt, allowRefresh)
    } catch (e: SocketTimeoutException) {
        retryOnNetworkError(req, url, attempt, allowRefresh, "TIMEOUT")
    } catch (e: IOException) {
        retryOnNetworkError(req, url, attempt, allowRefresh, "IO_ERROR: ${e.message}")
    }

    private suspend fun retryOnNetworkError(
        req: Request,
        url: String,
        attempt: Int,
        allowRefresh: Boolean,
        reason: String
    ): ApiResult<JsonObject> {
        return if (isSafeRetry(req) && attempt < MAX_RETRY) {
            delay(if (attempt == 0) 500L else 1500L)
            executeWithRetry(req, url, attempt + 1, allowRefresh)
        } else {
            logFailedRequest(req, null, reason, attempt)
            val message = if (reason.startsWith("TIMEOUT")) "timeout" else "network_error"
            rememberFriendlyError(message)
            ApiResult.Error(-1, message)
        }
    }

    // ── Response handling ───────────────────────────────────────────────────────

    private suspend fun handleResponse(
        response: Response,
        req: Request,
        url: String,
        attempt: Int,
        allowRefresh: Boolean
    ): ApiResult<JsonObject> {
        val code = response.code
        val bodyStr = response.body.string()
        ColituClock.observe(response.headers.getDate("Date"))

        return when {
            code == 401 -> handle401(req, url, attempt, allowRefresh, bodyStr)
            code == 503 || code == 502 -> handle5xx(req, url, attempt, allowRefresh, code, bodyStr)
            code == 304 -> ApiResult.Success(JsonObject(), code, response.header("ETag"))
            code !in 200..299 -> {
                logFailedRequest(req, code, bodyStr.take(200), attempt)
                val message = extractErrorCode(bodyStr) ?: "api_error"
                if (code == 403 && message in setOf("DEVICE_REVOKED", "USER_DISABLED")) {
                    ColituTokenManager.clear()
                    ColituAuthEvents.notifyAuthExpired()
                    return ApiResult.Error(code, message, isAuthError = true)
                }
                rememberFriendlyError(message)
                ApiResult.Error(code, message)
            }
            code == 204 || bodyStr.isBlank() -> ApiResult.Success(JsonObject(), code, response.header("ETag"))
            else -> parseBody(bodyStr, code, response.header("ETag"))
        }
    }

    // ── 401 / token refresh ─────────────────────────────────────────────────────

    // Auth endpoints returning 401 mean bad credentials — don't try to refresh.
    private val noRefreshPaths = listOf("/auth/refresh", "/auth/login", "/auth/register")

    private suspend fun handle401(
        req: Request,
        url: String,
        attempt: Int,
        allowRefresh: Boolean,
        bodyStr: String
    ): ApiResult<JsonObject> {
        // Replaying a mutation after refresh can duplicate a device or billing
        // transition when the first response was ambiguous. The caller must
        // explicitly repeat non-idempotent actions.
        val skipRefresh = !allowRefresh || !isSafeRetry(req) || noRefreshPaths.any { url.contains(it) }
        if (skipRefresh) {
            logFailedRequest(req, 401, bodyStr.take(200), attempt)
            return ApiResult.Error(401, "auth_expired", isAuthError = true)
        }

        // Serialized across concurrent callers; debounced so only one network call fires
        val refreshed = refreshMutex.withLock { attemptTokenRefresh() }
        return if (refreshed) {
            // ColituAuthInterceptor will pick up the new token from ColituTokenManager
            executeWithRetry(req, url, attempt = 0, allowRefresh = false)
        } else {
            ColituTokenManager.clear()
            ColituAuthEvents.notifyAuthExpired()
            logFailedRequest(req, 401, bodyStr.take(200), attempt)
            rememberFriendlyError("auth_expired")
            ApiResult.Error(401, "auth_expired", isAuthError = true)
        }
    }

    private suspend fun attemptTokenRefresh(): Boolean {
        val refreshToken = ColituTokenManager.getRefreshToken() ?: return false
        val now = System.currentTimeMillis()
        if (refreshToken == lastSuccessfulRefreshToken && now - lastSuccessfulRefreshMs < REFRESH_DEBOUNCE_MS) {
            return true
        }
        return try {
            val body = JsonObject().apply { addProperty("refresh_token", refreshToken) }
            val req = Request.Builder()
                .url(buildUrl("/auth/refresh"))
                .post(body.toString().toRequestBody(JSON_TYPE))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-App-Name", "Colitu")
                .header("X-Client-Platform", "android")
                .apply { ColituTokenManager.getDeviceId()?.let { header("X-Device-ID", it) } }
                .build()
            val response = refreshClient.newCall(req).execute()
            if (response.code in 200..299) {
                val respBody = response.body.string()
                if (respBody.isBlank()) return false
                val json = JsonParser.parseString(respBody).asJsonObject
                val newAccess = json.tryString("access_token")
                val newRefresh = json.tryString("refresh_token")
                if (!newAccess.isNullOrBlank()) {
                    ColituTokenManager.saveTokens(newAccess, newRefresh)
                    lastSuccessfulRefreshToken = newRefresh ?: refreshToken
                    lastSuccessfulRefreshMs = System.currentTimeMillis()
                    true
                } else false
            } else false
        } catch (_: Exception) { false }
    }

    // ── 5xx / server error retry ────────────────────────────────────────────────

    private suspend fun handle5xx(
        req: Request,
        url: String,
        attempt: Int,
        allowRefresh: Boolean,
        code: Int,
        bodyStr: String
    ): ApiResult<JsonObject> {
        if (BuildConfig.DEBUG) trackDiagnostic5xx(url)
        return if (isSafeRetry(req) && attempt < MAX_RETRY) {
            delay(if (attempt == 0) 500L else 1500L)
            executeWithRetry(req, url, attempt + 1, allowRefresh)
        } else {
            logFailedRequest(req, code, bodyStr.take(200), attempt)
            rememberFriendlyError("server_unavailable")
            ApiResult.Error(code, "server_unavailable")
        }
    }

    private fun trackDiagnostic5xx(url: String) {
        if (url.endsWith("/servers")) {
            serverListFailed = true
            Log.w(TAG, "[GLOBAL_DIAG] server list endpoint unavailable or baseUrl/auth/env mismatch")
        } else if (url.endsWith("/config")) {
            configFailCount++
            if (configFailCount >= 2) {
                Log.w(TAG, "[GLOBAL_DIAG] config endpoint backend unavailable or contract mismatch")
            }
        }
    }

    // ── Parse ────────────────────────────────────────────────────────────────────

    private fun parseBody(bodyStr: String, code: Int, etag: String?): ApiResult<JsonObject> = try {
        ApiResult.Success(JsonParser.parseString(bodyStr).asJsonObject, code, etag)
    } catch (_: Exception) {
        rememberFriendlyError("parse_error")
        ApiResult.Error(code, "parse_error")
    }

    private fun extractErrorCode(body: String): String? = try {
        val json = JsonParser.parseString(body).asJsonObject
        if (json.has("error") && json.get("error").isJsonObject) json.getAsJsonObject("error").tryString("code")
        else json.tryString("code")
    } catch (_: Exception) { null }

    // ── Debug logging ────────────────────────────────────────────────────────────

    private fun logFailedRequest(req: Request, statusCode: Int?, bodyPreview: String?, attempt: Int) {
        if (!BuildConfig.DEBUG) return

        val route = req.url.encodedPath
        Log.d(TAG, "request failed method=${req.method} route=$route status=$statusCode attempt=$attempt")
    }

    private fun sanitizedBodyPreview(body: String?): String {
        if (body == null) return "(empty)"
        val sensitiveKeys = listOf(
            "rawConfig", "raw_config", "wireguardConfig", "wireguard_config",
            "openvpnConfig", "openvpn_config", "privateKey", "private_key",
            "subscriptionUrl", "subscription_url", "outboundConfig", "outbound_config",
            "outbound", "configUrl", "config_url",
            "token", "accessToken", "refreshToken", "access_token", "refresh_token"
        )
        var preview = body.take(300)
        sensitiveKeys.forEach { key ->
            preview = preview.replace(Regex(""""$key"\s*:\s*"[^"]*""""), """"$key":"***"""")
        }
        return preview
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private fun JsonObject.tryString(key: String): String? =
        if (has(key) && !get(key).isJsonNull) get(key).asString.takeIf { it.isNotBlank() } else null

    fun resetDiagnosticCounters() {
        configFailCount = 0
        serverListFailed = false
    }

    fun getLastFriendlyError(): String? = lastFriendlyError

    fun rememberFriendlyError(message: String?) {
        val friendly = when (message) {
            null, "" -> return
            "timeout" -> "Request timed out"
            "network_error" -> "Network request failed"
            "auth_expired" -> "Session expired"
            "server_unavailable" -> "Colitu service is temporarily unavailable"
            "parse_error" -> "Unexpected server response"
            "CONFIG_NOT_READY" -> "VPN profile is not ready"
            else -> message
                .replace('_', ' ')
                .lowercase()
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
        lastFriendlyError = friendly.take(120)
    }

    private fun isSafeRetry(request: Request): Boolean = request.method == "GET" || request.method == "HEAD"
}
