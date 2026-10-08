package com.v2ray.ang.colitu.api

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tencent.mmkv.MMKV
import com.v2ray.ang.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okio.ByteString.Companion.decodeBase64
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** An accepted, verified endpoint list (all URLs are https, without query or fragment). */
internal data class EndpointList(
    val version: Long,
    val api: List<String>,
    val web: List<String>,
    val lists: List<String>,
)

/** Where the accepted list and the last working base are kept. */
internal interface EndpointStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

/** Verification and parsing of the signed endpoint list. Pure, no Android types. */
internal object ColituEndpointList {
    const val KEY_ID = "e1"

    const val PUBLIC_KEY_PEM = "-----BEGIN PUBLIC KEY-----\n" +
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEhv55BVmvEisYIRhkejn+4Leuf0KW\n" +
        "6jrrRvSL4cu4W09jUwc6HTIcq+YUSG1kJ5AF7qK7PlBtf+xRMTQMGM+xPg==\n" +
        "-----END PUBLIC KEY-----"

    /**
     * Returns the list when [raw] is a correctly signed file of schema 1 whose
     * version is greater than [storedVersion] (null: nothing stored yet); null otherwise.
     * The signature is checked over the decoded payload bytes exactly as received.
     */
    fun accept(raw: String, storedVersion: Long?, publicKeyPem: String = PUBLIC_KEY_PEM): EndpointList? = try {
        val file = JsonParser.parseString(raw).asJsonObject
        if (file.str("key_id") != KEY_ID) return null
        val payload = file.str("payload")?.decodeBase64()?.toByteArray() ?: return null
        val signature = file.str("signature")?.decodeBase64()?.toByteArray() ?: return null
        if (!verify(payload, signature, publicKeyPem)) return null
        val list = parsePayload(String(payload, Charsets.UTF_8)) ?: return null
        if (storedVersion != null && list.version <= storedVersion) return null
        list
    } catch (_: Exception) {
        null
    }

    private fun verify(payload: ByteArray, derSignature: ByteArray, pem: String): Boolean {
        val der = pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
            .decodeBase64()?.toByteArray() ?: return false
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(payload)
            verify(derSignature)
        }
    }

    internal fun parsePayload(json: String): EndpointList? {
        val obj = JsonParser.parseString(json).asJsonObject
        if (obj.long("schema") != 1L) return null
        val version = obj.long("version") ?: return null
        val api = obj.urls("api") ?: return null
        val web = obj.urls("web") ?: return null
        val lists = obj.urls("lists") ?: return null
        return EndpointList(version, api, web, lists)
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    /** An integer JSON number; strings and fractions are refused. */
    private fun JsonObject.long(key: String): Long? {
        val p = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber } ?: return null
        return runCatching { java.math.BigDecimal(p.asString).longValueExact() }.getOrNull()
    }

    private fun JsonObject.urls(key: String): List<String>? {
        val array = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (array.size() == 0) return null
        val out = ArrayList<String>(array.size())
        for (item in array) {
            val s = item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
            if (!isPlainHttps(s)) return null
            out += s
        }
        return out
    }

    private fun isPlainHttps(s: String): Boolean {
        if (!s.startsWith("https://") || s.contains('?') || s.contains('#')) return false
        return !runCatching { URI(s).host }.getOrNull().isNullOrEmpty()
    }
}

/** Persisted state: the accepted list and the base that last worked. */
internal class ColituEndpointState(
    private val store: EndpointStore,
    private val builtinApi: List<String> = ColituEndpoints.BUILTIN_API,
    private val builtinLists: List<String> = ColituEndpoints.BUILTIN_LISTS,
) {
    private companion object {
        const val KEY_RAW = "list_raw"
        const val KEY_VERSION = "list_version"
        const val KEY_LAST_GOOD = "last_good"
    }

    @Volatile private var cached: EndpointList? = null
    @Volatile private var loaded = false

    /** The accepted list, re-checked on load so a damaged or foreign file is never trusted. */
    fun current(): EndpointList? {
        if (!loaded) {
            cached = store.getString(KEY_RAW)?.let { ColituEndpointList.accept(it, null) }
            loaded = true
        }
        return cached
    }

    fun storedVersion(): Long? = current()?.version

    /** Stores [raw] when it is accepted (signature, shape and a newer version). */
    fun accept(raw: String): Boolean {
        val list = ColituEndpointList.accept(raw, storedVersion()) ?: return false
        store.putString(KEY_RAW, raw)
        store.putString(KEY_VERSION, list.version.toString())
        cached = list
        loaded = true
        return true
    }

    fun lastWorking(): String? = store.getString(KEY_LAST_GOOD)

    fun markWorked(base: String) {
        val normalized = base.trimEnd('/')
        if (lastWorking() != normalized) store.putString(KEY_LAST_GOOD, normalized)
    }

    /** The bases of the accepted list (else the built-in ones), last working first, no duplicates. */
    fun bases(): List<String> =
        ColituEndpoints.orderBases(current()?.api ?: builtinApi, lastWorking())

    fun listUrls(): List<String> = current()?.lists ?: builtinLists
}

/**
 * API failover. The API base list comes from the signed endpoint list (else the
 * built-in bases); a request moves to the next base only on a network-level
 * failure. A build with a non-default COLITU_API_BASE_URL uses only that base.
 */
object ColituEndpoints {
    private const val TAG = "ColituEndpoints"
    private const val DEFAULT_BASE = "https://api.colitu.com/api/v1"
    private const val REFRESH_INTERVAL_MS = 6L * 60 * 60 * 1000

    /** Mirror origins from the build (comma separated); https only, no trailing slash. */
    internal fun parseMirrors(raw: String): List<String> =
        raw.split(',').map { it.trim().trimEnd('/') }.filter { it.startsWith("https://") && it.length > 8 }.distinct()

    private val MIRRORS = parseMirrors(BuildConfig.COLITU_MIRRORS)

    internal val BUILTIN_API = listOf(DEFAULT_BASE) + MIRRORS.map { "$it/capi/v1" }
    internal val BUILTIN_LISTS = listOf("https://colitu.com/downloads/endpoints.json") +
        MIRRORS.map { "$it/downloads/endpoints.json" }

    /** A developer/staging build points the app at one API: no failover, no list refresh. */
    private val overridden: Boolean get() = BuildConfig.COLITU_API_BASE_URL.trimEnd('/') != DEFAULT_BASE

    private val state: ColituEndpointState by lazy { ColituEndpointState(MmkvEndpointStore()) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastRefreshAttempt = AtomicLong(0L)

    /** Base for building a request URL: the one that works best right now. */
    fun preferredBase(): String =
        if (overridden) BuildConfig.COLITU_API_BASE_URL.trimEnd('/') else state.bases().first()

    // Base ordering and failover

    /** [lastWorking] first (when it is one of [api]), then the rest in list order, no duplicates. */
    internal fun orderBases(api: List<String>, lastWorking: String?): List<String> {
        val all = api.map { it.trimEnd('/') }.distinct()
        val first = lastWorking?.trimEnd('/')?.takeIf { it in all } ?: return all
        return listOf(first) + all.filter { it != first }
    }

    /**
     * True when [e] means the base could not be used, so the next one may be tried:
     * the failure came before any HTTP response, and for a non-GET request also
     * before its body can have been sent.
     */
    internal fun isFailoverEligible(e: IOException, method: String): Boolean {
        if (e.message == "Canceled") return false
        val beforeSending = e is UnknownHostException || e is ConnectException || e is NoRouteToHostException ||
            e is SSLHandshakeException || e is SSLPeerUnverifiedException ||
            (e is SocketTimeoutException && e.message?.contains("connect", ignoreCase = true) == true) ||
            e.message?.contains("SOCKS", ignoreCase = true) == true
        return beforeSending || method == "GET" || method == "HEAD"
    }

    /**
     * Runs [attempt] for each base in order until one answers. A network-level failure
     * ([isFailoverEligible]) moves on; any other exception, or the last failure, is thrown.
     * Each base is tried once. The base that answered is returned with the result.
     */
    internal fun <T> failover(bases: List<String>, method: String, attempt: (String) -> T): Pair<String, T> {
        var last: IOException? = null
        for (base in bases) {
            try {
                return base to attempt(base)
            } catch (e: IOException) {
                if (!isFailoverEligible(e, method)) throw e
                last = e
            }
        }
        throw last ?: IOException("no API base")
    }

    /**
     * Sends [request] with [exec], moving to the next API base on network-level failures.
     * Requests that do not target a known base (and the override build) go out unchanged.
     */
    internal fun send(request: Request, exec: (Request) -> Response): Response {
        if (overridden) return exec(request)
        refreshIfDue()
        val bases = state.bases()
        val url = request.url.toString()
        val known = (bases + BUILTIN_API + (state.current()?.api ?: emptyList())).map { it.trimEnd('/') }
        val own = known.firstOrNull { url == it || url.startsWith("$it/") } ?: return exec(request)
        val suffix = url.removePrefix(own)
        val (worked, response) = failover(bases, request.method) { base ->
            exec(if (base == own) request else request.newBuilder().url(base + suffix).build())
        }
        state.markWorked(worked)
        return response
    }

    // Refresh

    /** Fetches a list file as text (null on any error). Installed by [ColituApiClient]. */
    @Volatile internal var fetcher: ((String) -> String?)? = null

    /** At the first call after the process starts, then at most every 6 hours; never blocks the caller. */
    private fun refreshIfDue() {
        val fetch = fetcher ?: return
        val now = System.currentTimeMillis()
        val last = lastRefreshAttempt.get()
        if (last != 0L && now - last < REFRESH_INTERVAL_MS) return
        if (!lastRefreshAttempt.compareAndSet(last, now)) return
        scope.launch {
            val updated = runCatching { refresh(fetch, state) }.getOrDefault(false)
            Log.i(TAG, if (updated) "endpoint list updated" else "endpoint list unchanged")
        }
    }

    /** GETs each list URL in order and stores the first accepted file. */
    internal fun refresh(fetch: (String) -> String?, target: ColituEndpointState): Boolean {
        for (url in target.listUrls()) {
            val raw = runCatching { fetch(url) }.getOrNull() ?: continue
            if (target.accept(raw)) return true
        }
        return false
    }

    private class MmkvEndpointStore : EndpointStore {
        private val mmkv by lazy { MMKV.mmkvWithID("COLITU_ENDPOINTS", MMKV.MULTI_PROCESS_MODE) }
        override fun getString(key: String): String? = mmkv.decodeString(key)
        override fun putString(key: String, value: String) { mmkv.encode(key, value) }
    }
}
