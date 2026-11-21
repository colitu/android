package com.v2ray.ang.colitu.api

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tencent.mmkv.MMKV
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.colitu.data.ColituRecoveryTestMode
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
    /** The `relays` of the list; null when the key is absent (then the built-in relays apply). */
    val relays: List<ApiRelay>? = null,
)

/**
 * An API relay: TLS to [host]:[port] with the server name [sni], the certificate
 * checked for [verify] (always api.colitu.com, see [ColituEndpointList.parseRelay]),
 * HTTP `Host: api.colitu.com`, API base `https://api.colitu.com` + [prefix].
 */
internal data class ApiRelay(
    val id: String,
    val host: String,
    val port: Int,
    val sni: String,
    val verify: String,
    val prefix: String,
    val region: String?,
    val whitelist: Boolean,
) {
    /** The relay's entry in a base list (not a URL; [ColituEndpoints.requestFor] turns it into one). */
    val base: String get() = ColituEndpoints.RELAY_PREFIX + id
}

/** What the base order depends on: the client's country (RU) and whitelist mode. */
internal data class RouteContext(val country: String? = null, val whitelist: Boolean = false) {
    companion object { val NONE = RouteContext() }
}

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
        val relays = obj.get("relays")?.let { el -> if (el.isJsonArray) parseRelays(el.asJsonArray) else emptyList() }
        return EndpointList(version, api, web, lists, relays)
    }

    const val RELAY_VERIFY = "api.colitu.com"
    private val RELAY_ID = Regex("^[a-z0-9][a-z0-9-]{0,31}$")
    private val DNS_LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
    private val IPV4 = Regex("^(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}$")

    /** Valid entries of a `relays` array; a bad entry is skipped, never the whole list. Ids are unique (first wins). */
    internal fun parseRelays(array: JsonArray): List<ApiRelay> {
        val out = LinkedHashMap<String, ApiRelay>()
        for (item in array) {
            val relay = item.takeIf { it.isJsonObject }?.let { parseRelay(it.asJsonObject) } ?: continue
            out.putIfAbsent(relay.id, relay)
        }
        return out.values.toList()
    }

    /** One relay, or null when any field is out of shape. A `verify` other than api.colitu.com is refused. */
    internal fun parseRelay(o: JsonObject): ApiRelay? {
        val id = o.str("id")?.takeIf { RELAY_ID.matches(it) } ?: return null
        val addr = o.str("addr") ?: return null
        val colon = addr.lastIndexOf(':')
        if (colon <= 0) return null
        val host = addr.substring(0, colon).lowercase()
        val portText = addr.substring(colon + 1)
        if (portText.isEmpty() || portText.length > 5 || !portText.all { it in '0'..'9' }) return null
        val port = portText.toInt()
        if (port !in 1..65535 || !(isDnsName(host) || IPV4.matches(host))) return null
        val sni = o.str("sni")?.lowercase()?.takeIf { isDnsName(it) } ?: return null
        val verify = o.str("verify")?.takeIf { it == RELAY_VERIFY } ?: return null
        val prefix = o.str("prefix")?.trimEnd('/')?.takeIf {
            it.startsWith("/") && !it.contains('?') && !it.contains('#') && !it.contains("..") && !it.contains("//")
        } ?: return null
        val region = o.str("region")?.uppercase()
        val whitelist = o.get("whitelist")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
        return ApiRelay(id, host, port, sni, verify, prefix, region, whitelist)
    }

    /** A DNS name (two labels or more, not an IPv4 literal). */
    internal fun isDnsName(s: String): Boolean {
        if (s.isEmpty() || s.length > 253 || IPV4.matches(s)) return false
        val labels = s.split('.')
        return labels.size >= 2 && labels.all { DNS_LABEL.matches(it) } && !labels.last().all { it in '0'..'9' }
    }

    /**
     * Built-in relays from the build value: `id|host:port|sni|region|wl` entries
     * separated by `;` (wl = 1 for a whitelist relay). Verify and prefix are fixed.
     */
    internal fun parseBuildRelays(raw: String): List<ApiRelay> {
        val array = JsonArray()
        for (entry in raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val f = entry.split('|').map { it.trim() }
            if (f.size < 3) continue
            array.add(JsonObject().apply {
                addProperty("id", f[0])
                addProperty("addr", f[1])
                addProperty("sni", f[2])
                addProperty("verify", RELAY_VERIFY)
                addProperty("prefix", "/api/v1")
                f.getOrNull(3)?.takeIf { it.isNotEmpty() }?.let { addProperty("region", it) }
                addProperty("whitelist", f.getOrNull(4) == "1")
            })
        }
        return parseRelays(array)
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
    private val builtinRelays: List<ApiRelay> = ColituEndpoints.BUILTIN_RELAYS,
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

    /** The relays of the accepted list when it has the key, else the built-in ones. */
    fun relays(): List<ApiRelay> = current()?.relays ?: builtinRelays

    fun relay(id: String): ApiRelay? = relays().firstOrNull { it.id == id }

    /** The order without the last working base: api bases, mirrors and relays as [ctx] wants them. */
    fun naturalOrder(ctx: RouteContext = RouteContext.NONE): List<String> =
        ColituEndpoints.routeOrder(current()?.api ?: builtinApi, relays(), ctx)

    /** The bases of the accepted list (else the built-in ones) and the relays, last working first, no duplicates. */
    fun bases(ctx: RouteContext = RouteContext.NONE): List<String> =
        ColituEndpoints.orderBases(naturalOrder(ctx), lastWorking())

    fun listUrls(): List<String> = current()?.lists ?: builtinLists
}

/**
 * Self-heal timer: while the client works off its first base, the earlier bases are
 * probed again every [intervalMs] (the first period starts when the move is seen).
 */
internal class ApiPathHealer(private val intervalMs: Long = ColituEndpoints.HEAL_INTERVAL_MS) {
    private var since: Long? = null

    /** True when a periodic probe run is due at [now]; claims it. [offFirst]: the current base is not the first one. */
    @Synchronized fun claim(now: Long, offFirst: Boolean): Boolean {
        if (!offFirst) {
            since = null
            return false
        }
        val start = since
        if (start == null) {
            since = now
            return false
        }
        if (now - start < intervalMs) return false
        since = now
        return true
    }

    @Synchronized fun reset() { since = null }
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

    private val MIRRORS = parseMirrors(ColituBuildValues.mirrors)

    internal val BUILTIN_API = listOf(DEFAULT_BASE) + MIRRORS.map { "$it/capi/v1" }
    internal val BUILTIN_LISTS = listOf("https://colitu.com/downloads/endpoints.json") +
        MIRRORS.map { "$it/downloads/endpoints.json" }
    internal val BUILTIN_RELAYS: List<ApiRelay> by lazy { ColituEndpointList.parseBuildRelays(ColituBuildValues.relays) }

    /** Base-list entries of relays start with this (`relay:<id>`). */
    const val RELAY_PREFIX = "relay:"
    const val RELAY_API_ORIGIN = "https://api.colitu.com"
    const val HEAL_INTERVAL_MS = 10L * 60 * 1000
    private const val HEALTH_PATH = "/health"
    private const val LIST_PATH = "/downloads/endpoints.json"

    internal fun isRelay(base: String): Boolean = base.startsWith(RELAY_PREFIX)

    /**
     * Base order before the last working one is put first. Normal: api bases (primary,
     * then mirrors), then relays. In Russia: primary, RU relays, whitelist relays, the
     * rest. In whitelist mode: whitelist relays first, then RU relays, then the rest.
     */
    internal fun routeOrder(api: List<String>, relays: List<ApiRelay>, ctx: RouteContext): List<String> {
        val bases = api.map { it.trimEnd('/') }.distinct()
        val wl = relays.filter { it.whitelist }.map { it.base }
        val ru = relays.filter { !it.whitelist && it.region == "RU" }.map { it.base }
        val other = relays.filter { !it.whitelist && it.region != "RU" }.map { it.base }
        return when {
            ctx.whitelist -> wl + ru + bases + other
            ctx.country.equals("RU", ignoreCase = true) -> bases.take(1) + ru + wl + bases.drop(1) + other
            else -> bases + other + ru + wl
        }.distinct()
    }

    /** Set by the controller: the client's country and whitelist mode, for the base order. */
    @Volatile internal var routeContext: () -> RouteContext = { RouteContext.NONE }

    private fun ctx(): RouteContext = runCatching { routeContext() }.getOrDefault(RouteContext.NONE)

    /**
     * [template] sent to [base] + [suffix]: a URL base as is; a relay as
     * `https://api.colitu.com` + its prefix + [suffix], tagged with the relay so the
     * client dials it. [originPath]: [suffix] is relative to the origin (health, list file).
     */
    internal fun requestFor(
        template: Request,
        base: String,
        suffix: String,
        originPath: Boolean = false,
        target: ColituEndpointState = state,
    ): Request {
        if (!isRelay(base)) {
            val url = if (originPath) originOf(base) + suffix else base + suffix
            return template.newBuilder().url(url).tag(ApiRelay::class.java, null).build()
        }
        val relay = target.relay(base.removePrefix(RELAY_PREFIX)) ?: throw ConnectException("unknown relay")
        val path = if (originPath) suffix else relay.prefix + suffix
        return template.newBuilder().url(RELAY_API_ORIGIN + path).tag(ApiRelay::class.java, relay).build()
    }

    /** `https://host[:port]` of an https base. */
    internal fun originOf(base: String): String {
        val uri = URI(base)
        return "https://" + uri.host + (if (uri.port > 0) ":${uri.port}" else "")
    }

    /** primary | mirror | relay:<id> for reports (never an address). */
    fun apiPath(): String = apiPathOf(state)

    internal fun apiPathOf(target: ColituEndpointState): String {
        val current = target.lastWorking() ?: return "primary"
        if (isRelay(current)) return current
        val first = target.naturalOrder().firstOrNull { !isRelay(it) }
        return if (current == first) "primary" else "mirror"
    }

    /** A developer/staging build points the app at one API: no failover, no list refresh. */
    private val overridden: Boolean get() = BuildConfig.COLITU_API_BASE_URL.trimEnd('/') != DEFAULT_BASE

    private val state: ColituEndpointState by lazy { ColituEndpointState(MmkvEndpointStore()) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastRefreshAttempt = AtomicLong(0L)

    /** Lower-case hosts of [bases] (https URLs). */
    internal fun hostsOf(bases: Iterable<String>): Set<String> =
        bases.mapNotNull { runCatching { URI(it).host }.getOrNull()?.lowercase() }.toSet()

    /** True for a host of an API base this client was built for (built-in, signed list, build override). */
    internal fun isApiHost(host: String): Boolean {
        val bases = listOf(preferredBase(), BuildConfig.COLITU_API_BASE_URL) + BUILTIN_API + state.bases() + (state.current()?.api ?: emptyList())
        return host.lowercase() in hostsOf(bases)
    }

    /** Base for building a request URL: the one that works best right now. */
    fun preferredBase(): String =
        if (overridden) BuildConfig.COLITU_API_BASE_URL.trimEnd('/')
        else ColituRecoveryTestMode.preferredBase(state.bases(ctx()).firstOrNull { !isRelay(it) } ?: DEFAULT_BASE)

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
        return isConnectLevel(e) || method == "GET" || method == "HEAD"
    }

    /** The failure came while connecting (DNS, TCP, TLS, SOCKS), before any request byte was answered. */
    internal fun isConnectLevel(e: IOException): Boolean =
        e is UnknownHostException || e is ConnectException || e is NoRouteToHostException ||
            e is SSLHandshakeException || e is SSLPeerUnverifiedException ||
            (e is SocketTimeoutException && e.message?.contains("connect", ignoreCase = true) == true) ||
            e.message?.contains("SOCKS", ignoreCase = true) == true

    /**
     * Calls that decide whether a connect can start (config, server list,
     * bootstrap, recovery set, multihop routes): connect timeout of
     * [FAST_CONNECT_TIMEOUT_S] per base and a [FAST_FAIL_BUDGET_MS] budget for
     * the whole base list. Long answers of already connected calls are not touched.
     */
    internal fun isFastFail(encodedPath: String): Boolean {
        val at = encodedPath.indexOf("/v1/")
        if (at < 0) return false
        val rest = encodedPath.substring(at + 3)
        return FAST_FAIL_PATHS.any { rest == it || rest.startsWith("$it/") }
    }

    private val FAST_FAIL_PATHS = listOf("/config", "/servers", "/client/bootstrap", "/client/recovery", "/multihop")
    const val FAST_CONNECT_TIMEOUT_S = 8L
    const val FAST_FAIL_BUDGET_MS = 24_000L

    /**
     * Runs [attempt] for each base in order until one answers. A network-level failure
     * ([isFailoverEligible]) moves on; any other exception, or the last failure, is thrown.
     * Each base is tried once. The base that answered is returned with the result.
     */
    internal fun <T> failover(
        bases: List<String>,
        method: String,
        budgetMs: Long = Long.MAX_VALUE,
        attempt: (String) -> T,
    ): Pair<String, T> {
        var last: IOException? = null
        val begin = System.nanoTime()
        for (base in bases) {
            try {
                return base to attempt(base)
            } catch (e: IOException) {
                if (!isFailoverEligible(e, method)) throw e
                last = e
                // Out of time: the decision "no base answers" is made now.
                if ((System.nanoTime() - begin) / 1_000_000 >= budgetMs) break
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
        healIfDue()
        val bases = ColituRecoveryTestMode.apiBases(state.bases(ctx()))
        val url = request.url.toString()
        val known = (bases + BUILTIN_API + (state.current()?.api ?: emptyList())).filterNot { isRelay(it) }.map { it.trimEnd('/') }
        val own = known.firstOrNull { url == it || url.startsWith("$it/") } ?: return exec(request)
        val suffix = url.removePrefix(own)
        val budget = if (isFastFail(request.url.encodedPath)) FAST_FAIL_BUDGET_MS else Long.MAX_VALUE
        val (worked, response) = failover(bases, request.method, budget) { base ->
            exec(if (base == own) request else requestFor(request, base, suffix))
        }
        state.markWorked(worked)
        return response
    }

    // Self-heal

    /** Sends a GET /health request (2 s); true on a 2xx. Installed by [ColituApiClient]. */
    @Volatile internal var prober: ((Request) -> Boolean)? = null
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }
    private val healer = ApiPathHealer()

    /**
     * Probes the bases of [target] in order with GET /health and makes the first one
     * that answers the working base. [all] = false: only the bases before the current
     * one (back to an earlier path); true (network change): from the start, current included.
     */
    internal fun heal(target: ColituEndpointState, ctx: RouteContext, all: Boolean, probe: (Request) -> Boolean): String? {
        val current = target.lastWorking()
        val template = Request.Builder().url(RELAY_API_ORIGIN + HEALTH_PATH).get().build()
        for (base in target.naturalOrder(ctx)) {
            if (!all && base == current) return null
            val ok = runCatching { probe(requestFor(template, base, HEALTH_PATH, originPath = true, target = target)) }.getOrDefault(false)
            if (ok) {
                target.markWorked(base)
                return base
            }
        }
        return null
    }

    /** Every [HEAL_INTERVAL_MS] while off the first base: try the earlier bases again (never blocks the caller). */
    private fun healIfDue() {
        val probe = prober ?: return
        if (!ColituRecoveryTestMode.endpointListRefresh(true)) return
        val c = ctx()
        val current = state.lastWorking()
        val offFirst = current != null && current != state.naturalOrder(c).firstOrNull()
        if (!healer.claim(clock(), offFirst)) return
        scope.launch {
            val back = runCatching { heal(state, c, all = false, probe = probe) }.getOrNull()
            if (back != null) Log.i(TAG, "API path back to an earlier base")
        }
    }

    /** Network change: the path is chosen again from the start, then the list is refreshed through it. */
    fun onNetworkChanged() {
        if (overridden || !ColituRecoveryTestMode.endpointListRefresh(true)) return
        val probe = prober ?: return
        healer.reset()
        lastRefreshAttempt.set(0L)
        scope.launch {
            runCatching { heal(state, ctx(), all = true, probe = probe) }
            refreshIfDue()
        }
    }

    // Refresh

    /** Fetches a list file as text (null on any error). Installed by [ColituApiClient]. */
    @Volatile internal var fetcher: ((Request) -> String?)? = null

    /** At the first call after the process starts, then at most every 6 hours; never blocks the caller. */
    private fun refreshIfDue() {
        val fetch = fetcher ?: return
        if (!ColituRecoveryTestMode.endpointListRefresh(true)) return
        val now = System.currentTimeMillis()
        val last = lastRefreshAttempt.get()
        if (last != 0L && now - last < REFRESH_INTERVAL_MS) return
        if (!lastRefreshAttempt.compareAndSet(last, now)) return
        scope.launch {
            val updated = runCatching { refresh(fetch, state) }.getOrDefault(false)
            Log.i(TAG, if (updated) "endpoint list updated" else "endpoint list unchanged")
        }
    }

    /** GETs each list URL in order, then the list through each relay, and stores the first accepted file. */
    internal fun refresh(fetch: (Request) -> String?, target: ColituEndpointState): Boolean {
        val template = Request.Builder().url(RELAY_API_ORIGIN + LIST_PATH).get().build()
        val requests = target.listUrls().mapNotNull { url -> runCatching { template.newBuilder().url(url).build() }.getOrNull() } +
            target.relays().map { requestFor(template, it.base, LIST_PATH, originPath = true, target = target) }
        for (req in requests) {
            val raw = runCatching { fetch(req) }.getOrNull() ?: continue
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
