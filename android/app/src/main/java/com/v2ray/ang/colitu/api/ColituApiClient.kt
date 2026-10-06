package com.v2ray.ang.colitu.api

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

object ColituApiClient {

    val BASE_URL: String get() = BuildConfig.COLITU_API_BASE_URL
    private const val MAX_RETRY = 2
    private const val TAG = "ColituApiClient"
    private const val REFRESH_DEBOUNCE_MS = 10_000L
    /** JSON answers are small; anything bigger is refused instead of filling memory. */
    private const val MAX_JSON_BYTES = 4L * 1024 * 1024
    /** Support attachments are at most 10 MB on upload; a little slack for the server's copy. */
    const val MAX_DOWNLOAD_BYTES = 12L * 1024 * 1024
    /** After the tunnel failed to carry an API call, go direct for this long. */
    private const val TUNNEL_BACKOFF_MS = 60_000L
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    // Direct clients: the app itself is outside the VPN (Xray's own traffic
    // must not loop), so these reach the API over the phone's network.
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .addInterceptor(ColituAuthInterceptor())
        .build()

    // Refresh client: no auth interceptor, so a 401 cannot recurse.
    private val refreshClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val refreshMutex = Mutex()
    @Volatile private var lastSuccessfulRefreshMs = 0L
    @Volatile private var lastSuccessfulRefreshToken: String? = null
    @Volatile private var lastFriendlyError: String? = null
    @Volatile private var tunnelFailedAt = 0L

    sealed class ApiResult<out T> {
        data class Success<T>(
            val data: T,
            val statusCode: Int = 200,
            val etag: String? = null,
        ) : ApiResult<T>()
        data class Error(
            val code: Int,
            val message: String,
            val isAuthError: Boolean = false,
            /** The JSON error body, for answers that carry data (MFA_REQUIRED, DEVICE_OVER_LIMIT). */
            val body: JsonObject? = null,
        ) : ApiResult<Nothing>()
    }

    /** Outcome of POST /auth/refresh. Only [Rejected] ends the session. */
    internal enum class RefreshOutcome { Renewed, Rejected, Unavailable }

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

    suspend fun post(path: String, body: JsonObject, headers: Map<String, String> = emptyMap()): ApiResult<JsonObject> = withContext(Dispatchers.IO) {
        val url = buildUrl(path)
        val builder = Request.Builder().url(url).post(body.toString().toRequestBody(JSON_TYPE))
        headers.forEach(builder::header)
        executeWithRetry(builder.build(), url, 0, true)
    }

    suspend fun patch(path: String, body: JsonObject): ApiResult<JsonObject> = withContext(Dispatchers.IO) {
        val url = buildUrl(path)
        executeWithRetry(Request.Builder().url(url).patch(body.toString().toRequestBody(JSON_TYPE)).build(), url, 0, true)
    }

    suspend fun put(path: String, body: JsonObject): ApiResult<JsonObject> = withContext(Dispatchers.IO) {
        val url = buildUrl(path)
        executeWithRetry(Request.Builder().url(url).put(body.toString().toRequestBody(JSON_TYPE)).build(), url, 0, true)
    }

    suspend fun delete(path: String): ApiResult<JsonObject> = withContext(Dispatchers.IO) {
        val url = buildUrl(path)
        executeWithRetry(Request.Builder().url(url).delete().build(), url, attempt = 0, allowRefresh = true)
    }

    /** Kept for call sites that predate the general 401 handling; same as [post]. */
    suspend fun postRenewing(path: String, body: JsonObject): ApiResult<JsonObject> = post(path, body)

    /** multipart/form-data with a JSON "payload" part and "file" parts (support messages). */
    suspend fun postMultipart(path: String, payload: JsonObject, files: List<UploadFile>): ApiResult<JsonObject> = withContext(Dispatchers.IO) {
        val form = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("payload", null, payload.toString().toRequestBody(JSON_TYPE))
        files.forEach { file ->
            form.addFormDataPart("file", file.name, file.bytes.toRequestBody("application/octet-stream".toMediaType()))
        }
        val url = buildUrl(path)
        executeWithRetry(Request.Builder().url(url).post(form.build()).build(), url, attempt = 0, allowRefresh = true)
    }

    /** Raw bytes of an authorized download (support attachments); null on failure or when too big. */
    suspend fun getBytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        fun fetch(): Pair<Int, ByteArray?> = runCatching {
            call(Request.Builder().url(buildUrl(path)).get().build()).use { response ->
                if (!response.isSuccessful) return@use response.code to null
                val body = response.body
                if (body.contentLength() > MAX_DOWNLOAD_BYTES) return@use response.code to null
                response.code to readLimited(body.byteStream(), MAX_DOWNLOAD_BYTES)
            }
        }.getOrDefault(-1 to null)
        val (code, bytes) = fetch()
        if (code != 401) return@withContext bytes
        if (refreshMutex.withLock { attemptTokenRefresh() } == RefreshOutcome.Renewed) fetch().second else null
    }

    class UploadFile(val name: String, val bytes: ByteArray)

    // ── Transport: through the tunnel when it is up ─────────────────────────────

    /**
     * Sends [request] through the tunnel's authenticated SOCKS inbound while
     * the VPN is connected (the API stays reachable when it is blocked on the
     * local network, and the phone's real address is not shown to it), and
     * directly otherwise. When the tunnel cannot carry the call it is sent
     * directly once; only failures that happen before the request reached
     * the server qualify, so a mutation is never sent twice.
     */
    private fun call(request: Request, base: OkHttpClient = httpClient): Response {
        val proxy = ColituLocalProxy.activeTunnel()
        if (proxy == null || System.currentTimeMillis() - tunnelFailedAt < TUNNEL_BACKOFF_MS) {
            return base.newCall(request).execute()
        }
        ColituSocksAuth.install(proxy)
        val tunneled = base.newBuilder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxy.port)))
            .connectTimeout(8, TimeUnit.SECONDS)
            .build()
        return try {
            tunneled.newCall(request).execute()
        } catch (e: IOException) {
            if (!failedBeforeSending(e, request)) throw e
            tunnelFailedAt = System.currentTimeMillis()
            Log.w(TAG, "tunnel could not carry the API call (${e.javaClass.simpleName}), going direct")
            base.newCall(request).execute()
        }
    }

    private fun failedBeforeSending(e: IOException, request: Request): Boolean =
        isSafeRetry(request) || e is ConnectException || e is NoRouteToHostException || e is SSLException ||
            (e is SocketTimeoutException && e.message?.contains("connect", ignoreCase = true) == true) ||
            e.message?.contains("SOCKS", ignoreCase = true) == true

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
        call(req).use { response -> handleResponse(response, req, url, attempt, allowRefresh) }
    } catch (e: SocketTimeoutException) {
        retryOnNetworkError(req, url, attempt, allowRefresh, "TIMEOUT")
    } catch (e: IOException) {
        retryOnNetworkError(req, url, attempt, allowRefresh, "IO_ERROR")
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
            logFailedRequest(req, null, attempt)
            val message = if (reason == "TIMEOUT") "timeout" else "network_error"
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
        val bodyStr = readBody(response) ?: return ApiResult.Error(code, "parse_error")
        ColituClock.observe(response.headers.getDate("Date"))

        return when {
            code == 401 -> handle401(req, url, attempt, allowRefresh, bodyStr)
            code in RETRYABLE_STATUS -> handleRetryable(req, url, attempt, allowRefresh, code, response.header("Retry-After"))
            code == 304 -> ApiResult.Success(JsonObject(), code, response.header("ETag"))
            code !in 200..299 -> {
                logFailedRequest(req, code, attempt)
                val message = extractErrorCode(bodyStr) ?: "api_error"
                if (code == 403 && message in setOf("DEVICE_REVOKED", "USER_DISABLED")) {
                    ColituTokenManager.clear()
                    ColituAuthEvents.notifyAuthExpired()
                    return ApiResult.Error(code, message, isAuthError = true)
                }
                rememberFriendlyError(message)
                ApiResult.Error(code, message, body = parseObject(bodyStr))
            }
            code == 204 || bodyStr.isBlank() -> ApiResult.Success(JsonObject(), code, response.header("ETag"))
            else -> parseBody(bodyStr, code, response.header("ETag"))
        }
    }

    private fun readBody(response: Response): String? {
        val body = response.body
        if (body.contentLength() > MAX_JSON_BYTES) return null
        return readLimited(body.byteStream(), MAX_JSON_BYTES)?.toString(Charsets.UTF_8)
    }

    private fun readLimited(input: java.io.InputStream, limit: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    // ── 401 / token refresh ─────────────────────────────────────────────────────

    // Auth endpoints returning 401 mean bad credentials — don't try to refresh.
    private val noRefreshPaths = listOf("/auth/refresh", "/auth/login", "/auth/register", "/auth/password/reset")

    /**
     * A 401 is decided by the auth middleware before the handler runs, so the
     * request had no effect and is safe to send again after a refresh, for
     * every method. The session only ends when the refresh token itself is
     * rejected; a network error or a server problem during the refresh leaves
     * the session (and the running tunnel) alone.
     */
    private suspend fun handle401(req: Request, url: String, attempt: Int, allowRefresh: Boolean, bodyStr: String = ""): ApiResult<JsonObject> {
        // Sign-in endpoints answer 401 with their own codes (wrong password,
        // MFA_INVALID_CODE, MFA_TOKEN_EXPIRED); those are kept for the screen.
        if (noRefreshPaths.any { url.contains(it) }) {
            logFailedRequest(req, 401, attempt)
            val panelCode = extractErrorCode(bodyStr)
            return ApiResult.Error(401, panelCode ?: "auth_expired", isAuthError = panelCode == null, body = parseObject(bodyStr))
        }
        // Signed out (or signed out while this request was in flight): there is
        // no session to end, so don't clear() a sign-in that may be starting.
        if (!allowRefresh || !ColituTokenManager.isLoggedIn()) {
            logFailedRequest(req, 401, attempt)
            return ApiResult.Error(401, "auth_expired", isAuthError = true)
        }
        return when (refreshMutex.withLock { attemptTokenRefresh() }) {
            RefreshOutcome.Renewed -> executeWithRetry(req, url, attempt = 0, allowRefresh = false)
            RefreshOutcome.Rejected -> {
                ColituTokenManager.clear()
                ColituAuthEvents.notifyAuthExpired()
                logFailedRequest(req, 401, attempt)
                rememberFriendlyError("auth_expired")
                ApiResult.Error(401, "auth_expired", isAuthError = true)
            }
            RefreshOutcome.Unavailable -> {
                rememberFriendlyError("server_unavailable")
                ApiResult.Error(-1, "network_error")
            }
        }
    }

    private suspend fun attemptTokenRefresh(): RefreshOutcome {
        val generation = ColituTokenManager.sessionGeneration()
        val refreshToken = ColituTokenManager.getRefreshToken() ?: return RefreshOutcome.Rejected
        val now = System.currentTimeMillis()
        if (refreshToken == lastSuccessfulRefreshToken && now - lastSuccessfulRefreshMs < REFRESH_DEBOUNCE_MS) {
            return RefreshOutcome.Renewed
        }
        return try {
            val body = JsonObject().apply { addProperty("refresh_token", refreshToken) }
            val req = Request.Builder()
                .url(buildUrl("/auth/refresh"))
                .post(body.toString().toRequestBody(JSON_TYPE))
                .header("Accept", "application/json")
                .header("X-App-Name", "Colitu")
                .header("X-Client-Platform", "android")
                .apply { ColituTokenManager.getDeviceId()?.let { header("X-Device-ID", it) } }
                .build()
            call(req, refreshClient).use { response ->
                val code = response.code
                ColituClock.observe(response.headers.getDate("Date"))
                val outcome = refreshOutcomeFor(code)
                if (outcome != RefreshOutcome.Renewed) return@use outcome
                val json = readBody(response)?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
                    ?: return@use RefreshOutcome.Unavailable
                val newAccess = json.tryString("access_token") ?: return@use RefreshOutcome.Unavailable
                val newRefresh = json.tryString("refresh_token")
                // Signed out while the refresh was in flight: don't bring the session back.
                if (!ColituTokenManager.saveRefreshedTokens(generation, newAccess, newRefresh)) {
                    return@use RefreshOutcome.Unavailable
                }
                lastSuccessfulRefreshToken = newRefresh ?: refreshToken
                lastSuccessfulRefreshMs = System.currentTimeMillis()
                RefreshOutcome.Renewed
            }
        } catch (_: Exception) {
            RefreshOutcome.Unavailable
        }
    }

    /** 2xx renews; 400/401/403 mean the refresh token is no good; anything else is temporary. */
    internal fun refreshOutcomeFor(code: Int): RefreshOutcome = when (code) {
        in 200..299 -> RefreshOutcome.Renewed
        400, 401, 403 -> RefreshOutcome.Rejected
        else -> RefreshOutcome.Unavailable
    }

    // ── 5xx / rate limit retry ──────────────────────────────────────────────────

    private val RETRYABLE_STATUS = setOf(429, 500, 502, 503, 504)

    private suspend fun handleRetryable(
        req: Request,
        url: String,
        attempt: Int,
        allowRefresh: Boolean,
        code: Int,
        retryAfter: String?,
    ): ApiResult<JsonObject> {
        // Only reads are repeated: a 500 on a mutation may already have been applied.
        return if (isSafeRetry(req) && attempt < MAX_RETRY) {
            val wait = retryAfter?.trim()?.toLongOrNull()?.times(1000)?.coerceIn(500L, 3000L)
                ?: if (attempt == 0) 500L else 1500L
            delay(wait)
            executeWithRetry(req, url, attempt + 1, allowRefresh)
        } else {
            logFailedRequest(req, code, attempt)
            val message = if (code == 429) "rate_limited" else "server_unavailable"
            rememberFriendlyError(message)
            ApiResult.Error(code, message)
        }
    }

    // ── Parse ────────────────────────────────────────────────────────────────────

    private fun parseBody(bodyStr: String, code: Int, etag: String?): ApiResult<JsonObject> = try {
        ApiResult.Success(JsonParser.parseString(bodyStr).asJsonObject, code, etag)
    } catch (_: Exception) {
        rememberFriendlyError("parse_error")
        ApiResult.Error(code, "parse_error")
    }

    private fun parseObject(body: String): JsonObject? =
        runCatching { JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()

    private fun extractErrorCode(body: String): String? = try {
        val json = JsonParser.parseString(body).asJsonObject
        if (json.has("error") && json.get("error").isJsonObject) json.getAsJsonObject("error").tryString("code")
        else json.tryString("code")
    } catch (_: Exception) { null }

    // ── Debug logging ────────────────────────────────────────────────────────────

    private fun logFailedRequest(req: Request, statusCode: Int?, attempt: Int) {
        if (!BuildConfig.DEBUG) return
        Log.d(TAG, "request failed method=${req.method} route=${req.url.encodedPath} status=$statusCode attempt=$attempt")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private fun JsonObject.tryString(key: String): String? =
        if (has(key) && !get(key).isJsonNull && get(key).isJsonPrimitive) get(key).asString.takeIf { it.isNotBlank() } else null

    /** Last API error as a stable code (for support diagnostics), never raw server text. */
    fun getLastFriendlyError(): String? = lastFriendlyError

    fun rememberFriendlyError(message: String?) {
        if (message.isNullOrBlank()) return
        lastFriendlyError = message.filter { it.isLetterOrDigit() || it == '_' }.take(60)
    }

    private fun isSafeRetry(request: Request): Boolean = request.method == "GET" || request.method == "HEAD"
}
