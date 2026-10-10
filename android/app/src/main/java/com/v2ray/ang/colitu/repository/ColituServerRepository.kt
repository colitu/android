package com.v2ray.ang.colitu.repository

import android.util.Log
import com.google.gson.JsonObject
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.colitu.api.ColituApiClient
import com.v2ray.ang.colitu.api.ColituSecureStore
import com.v2ray.ang.colitu.data.ColituMultihop
import com.v2ray.ang.colitu.data.ColituRecoverySet
import com.v2ray.ang.colitu.data.ColituRecoveryTestMode
import com.v2ray.ang.colitu.data.ColituServerListResponse
import com.v2ray.ang.colitu.data.ColituVpnConfig
import com.v2ray.ang.colitu.data.ClientBootstrapPolicy
import com.v2ray.ang.colitu.data.XrayMobileAdapter
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

object ColituServerRepository {

    private const val TAG = "ColituServerRepo"
    private const val STORE_ID = "COLITU_SERVERS"
    private const val KEY_SELECTED_SERVER = "selected_server_id"
    private const val KEY_CONFIG_ETAG = "config_etag"
    /** Last good envelope of a multihop route, kept apart from the node envelope (see [fetchRouteCandidates]). */
    private const val KEY_ROUTE_ENVELOPE = "vpn_lkg_route"
    /** Last good envelope of the warm spare's server, kept apart from the primary's (see [fetchNodeCandidates]). */
    private const val KEY_SPARE_ENVELOPE = "vpn_lkg_spare"

    /** The recovery set's raw JSON (Adaptive Connect 3.0), sealed next to the config cache. */
    private const val KEY_RECOVERY_SET = "vpn_recovery_set"
    private const val KEY_RECOVERY_ATTEMPT = "recovery_last_attempt"

    private val store by lazy { MMKV.mmkvWithID(STORE_ID, MMKV.MULTI_PROCESS_MODE) }

    /**
     * Some API call of this connect (bootstrap, config, node/route config,
     * preference) got no HTTP answer from any base, and no config has come
     * from the network since. Sticky: set at once by the first such failure,
     * cleared by a real /config answer or [clearConfigUnreachable].
     */
    @Volatile var configApiUnreachable = false
        private set

    fun clearConfigUnreachable() {
        configApiUnreachable = false
    }

    /** An API call ended with [code]; code -1 is "no base answered". */
    fun noteApiFailure(code: Int) {
        if (ColituRecoverySet.isNetworkLevel(code)) configApiUnreachable = true
    }

    fun getSelectedServerId(): String? = store.decodeString(KEY_SELECTED_SERVER)
    fun setSelectedServerId(id: String) = store.encode(KEY_SELECTED_SERVER, id)

    // ── Servers ──────────────────────────────────────────────────────────────────

    suspend fun fetchServers(): Result<ColituServerListResponse> = withContext(Dispatchers.IO) {
        when (val result = ColituApiClient.get("/servers")) {
            is ColituApiClient.ApiResult.Success -> {
                try {
                    Result.success(parseServerList(result.data))
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "Server list parse failed", e)
                    Result.failure(Exception("parse_error"))
                }
            }
            is ColituApiClient.ApiResult.Error -> {
                if (result.isAuthError) Result.failure(Exception("auth_expired"))
                else Result.failure(Exception(result.message))
            }
        }
    }

    private fun parseServerList(json: JsonObject): ColituServerListResponse =
        ColituServerListResponse.fromJson(json)

    // ── Config ───────────────────────────────────────────────────────────────────

    /**
     * Every transport the panel offers for the preferred server (primary
     * first), each rendered to a runtime config.
     */
    suspend fun fetchConfigCandidates(): Result<List<ColituVpnConfig>> =
        fetchConfigEnvelope().mapCatching { envelope ->
            val rendered = runCatching { XrayMobileAdapter.renderCandidates(envelope) }
            rendered.exceptionOrNull()?.let { Log.w(TAG, "config did not render: ${it.message}") }
            rendered.getOrNull()
                ?.filter { !it.rawConfig.isNullOrBlank() }
                ?.takeIf { it.isNotEmpty() }
                ?: throw Exception(rendered.exceptionOrNull()?.message?.takeIf { it.startsWith("CONFIG_") } ?: "CONFIG_NOT_READY")
        }

    /**
     * The bootstrap's verdict before a config is fetched: the failure code
     * when it denies connecting (or fails for a reason the offline grace may
     * not cover), null when the config may be requested.
     */
    private suspend fun bootstrapFailure(): String? =
        when (val bootstrap = ColituApiClient.get("/client/bootstrap")) {
            is ColituApiClient.ApiResult.Success -> {
                val policy = runCatching { ClientBootstrapPolicy.fromJson(bootstrap.data) }.getOrNull()
                    ?: return "CONFIG_NOT_READY"
                if (policy.denial != null) {
                    clearConfigCaches()
                    policy.denial
                } else null
            }
            is ColituApiClient.ApiResult.Error -> {
                noteApiFailure(bootstrap.code)
                if (!ConfigCachePolicy.allowsFallback(bootstrap.code, bootstrap.isAuthError)) {
                    clearConfigCaches()
                    bootstrap.message
                } else null
            }
        }

    private fun clearConfigCaches() {
        ColituSecureStore.remove("vpn_lkg_envelope", KEY_ROUTE_ENVELOPE, KEY_SPARE_ENVELOPE)
        store.removeValueForKey(KEY_CONFIG_ETAG)
        clearRecoverySet()
    }

    private suspend fun fetchConfigEnvelope(): Result<JsonObject> = withContext(Dispatchers.IO) {
        bootstrapFailure()?.let { return@withContext Result.failure(Exception(it)) }
        // No base answered the bootstrap: /config would wait for the same timeouts again.
        if (configApiUnreachable) {
            return@withContext cachedEnvelope()?.let { Result.success(it) } ?: Result.failure(Exception("network_error"))
        }
        val etag = if (ColituSecureStore.get("vpn_lkg_envelope").isNullOrBlank()) {
            store.removeValueForKey(KEY_CONFIG_ETAG)
            null
        } else {
            store.decodeString(KEY_CONFIG_ETAG)
        }
        val headers = if (etag.isNullOrBlank()) emptyMap() else mapOf("If-None-Match" to etag)
        when (val result = ColituApiClient.get("/config", headers)) {
            is ColituApiClient.ApiResult.Success -> {
                configApiUnreachable = false
                if (result.statusCode == 304) {
                    val cached = cachedEnvelope()
                    return@withContext if (cached != null) Result.success(cached)
                    else Result.failure(Exception("CONFIG_CACHE_MISS"))
                }
                val config = runCatching { XrayMobileAdapter.render(result.data) }.getOrElse {
                    Log.w(TAG, "config did not render: ${it.message}")
                    // A primary transport this build cannot use is fine as long as another one renders.
                    runCatching { XrayMobileAdapter.renderCandidates(result.data).first() }.getOrElse { e ->
                        return@withContext Result.failure(Exception(e.message?.takeIf { m -> m.startsWith("CONFIG_") } ?: "parse_error"))
                    }
                }
                if (config.rawConfig.isNullOrBlank()) {
                    return@withContext Result.failure(Exception("CONFIG_NOT_READY"))
                }
                ColituSecureStore.put("vpn_lkg_envelope", result.data.toString())
                result.etag?.takeIf { it.isNotBlank() }?.let { store.encode(KEY_CONFIG_ETAG, it) }
                Result.success(result.data)
            }
            is ColituApiClient.ApiResult.Error -> {
                noteApiFailure(result.code)
                if (!ConfigCachePolicy.allowsFallback(result.code, result.isAuthError)) {
                    ColituSecureStore.remove("vpn_lkg_envelope")
                    store.removeValueForKey(KEY_CONFIG_ETAG)
                    return@withContext Result.failure(Exception(if (result.isAuthError) "auth_expired" else result.message))
                }
                val lkg = cachedEnvelope()
                if (lkg != null) Result.success(lkg)
                else if (result.isAuthError) Result.failure(Exception("auth_expired"))
                else Result.failure(Exception(result.message))
            }
        }
    }

    /**
     * Transports from the cached envelope for [serverId], without a network
     * round trip. Null when there is no cache, it belongs to another server or
     * the panel's validity window (expires_at) has passed.
     */
    fun cachedConfigCandidates(serverId: String, now: java.time.Instant = com.v2ray.ang.colitu.api.ColituClock.now()): List<ColituVpnConfig>? {
        val envelope = cachedEnvelope() ?: return null
        val cachedServer = envelope.getAsJsonObject("server")?.get("id")?.takeIf { it.isJsonPrimitive }?.asString
        if (cachedServer != serverId) return null
        val expires = envelope.get("expires_at")?.takeIf { it.isJsonPrimitive }?.asString
            ?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() } ?: return null
        if (!now.isBefore(expires)) return null
        return runCatching { XrayMobileAdapter.renderCandidates(envelope, now) }.getOrNull()
            ?.filter { !it.rawConfig.isNullOrBlank() }
            ?.takeIf { it.isNotEmpty() }
    }

    /** Last known good envelope, only while it still renders to a valid config. */
    private fun cachedEnvelope(): JsonObject? {
        if (ColituRecoveryTestMode.regularCache("cache") == null) return null
        val raw = ColituSecureStore.get("vpn_lkg_envelope") ?: return null
        val envelope = runCatching { com.google.gson.JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
        return envelope.takeIf { ColituVpnConfig.fromJson(it) != null }
    }

    // ── Recovery set (Adaptive Connect 3.0) ──────────────────────────────────────

    private val recoveryFetchLock = Mutex()

    /** The envelopes of the set to try, with `recovery_until` as their grace. */
    class RecoveryPlan(
        val until: java.time.Instant,
        /** Servers of the stored set. */
        val setSize: Int,
        /** Server id and its transports, in the set's order, without the failed servers. */
        val entries: List<Pair<String, List<ColituVpnConfig>>>,
    )

    /**
     * Fetches `GET /client/recovery` when the stored set is missing or older
     * than 24 h, at most once per 6 h; the caller never waits for it. 401/403
     * delete the stored set, network errors and 5xx keep it.
     */
    suspend fun refreshRecoverySetIfDue(clientCountry: String? = null): Unit = withContext(Dispatchers.IO) {
        if (!com.v2ray.ang.colitu.data.ColituFeatures.recoverySet()) return@withContext
        if (!recoveryFetchLock.tryLock()) return@withContext
        try {
            val nowMs = System.currentTimeMillis()
            val stored = ColituRecoverySet.parse(ColituSecureStore.get(KEY_RECOVERY_SET))
            if (!ColituRecoverySet.refreshDue(stored, store.decodeLong(KEY_RECOVERY_ATTEMPT, 0L), nowMs)) return@withContext
            store.encode(KEY_RECOVERY_ATTEMPT, nowMs)
            when (val result = ColituApiClient.get(ColituRecoverySet.fetchPath(clientCountry))) {
                is ColituApiClient.ApiResult.Success -> {
                    val raw = result.data.toString()
                    val parsed = ColituRecoverySet.parse(raw)
                    if (parsed != null) {
                        ColituSecureStore.put(KEY_RECOVERY_SET, raw)
                        val servers = parsed.configs.mapNotNull { config ->
                            val server = config.getAsJsonObject("server") ?: return@mapNotNull null
                            "${server.get("id")?.asString?.take(8)}/${server.get("country")?.asString}"
                        }
                        Log.w(TAG, "Colitu: recovery set stored: ${parsed.configs.size} servers $servers, until ${parsed.recoveryUntil}")
                    } else Log.w(TAG, "recovery set refused (not a valid set)")
                }
                is ColituApiClient.ApiResult.Error -> {
                    if (ColituRecoverySet.onFetchError(result.code, result.isAuthError) == ColituRecoverySet.FetchError.DeleteSet) {
                        clearRecoverySet()
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "recovery set refresh failed: ${e.javaClass.simpleName}")
        } finally {
            recoveryFetchLock.unlock()
        }
    }

    /**
     * What a connect may try when no API base answers and the regular cache is
     * no use: the stored set's servers in order, minus [failedServers], each
     * rendered like a cached config but valid until `recovery_until`. A set
     * past that time is deleted; null when there is nothing to try.
     */
    fun recoveryPlan(failedServers: Collection<String>, now: java.time.Instant = com.v2ray.ang.colitu.api.ColituClock.now()): RecoveryPlan? {
        if (!com.v2ray.ang.colitu.data.ColituFeatures.recoverySet()) return null
        val raw = ColituSecureStore.get(KEY_RECOVERY_SET) ?: return null
        val set = ColituRecoverySet.parse(raw)
        if (set == null) {
            ColituSecureStore.remove(KEY_RECOVERY_SET)
            return null
        }
        if (ColituRecoverySet.isExpired(set, now)) {
            ColituSecureStore.remove(KEY_RECOVERY_SET)
            return null
        }
        val entries = ColituRecoverySet.serversToTry(set, failedServers).mapNotNull { envelope ->
            val id = ColituRecoverySet.serverId(envelope) ?: return@mapNotNull null
            val configs = runCatching { XrayMobileAdapter.renderCandidates(envelope, now, set.recoveryUntil) }.getOrNull()
                ?.filter { !it.rawConfig.isNullOrBlank() }
                ?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            id to configs
        }
        return if (entries.isEmpty()) null else RecoveryPlan(set.recoveryUntil, set.configs.size, entries)
    }

    /** Sign-out, account deletion, a denial: the set goes with the config cache. */
    fun clearRecoverySet() {
        ColituSecureStore.remove(KEY_RECOVERY_SET)
        store.removeValueForKey(KEY_RECOVERY_ATTEMPT)
    }

    // ── Warm spare ───────────────────────────────────────────────────────────────

    /**
     * Every transport of [nodeId] for the warm spare (`GET /config?node=`):
     * the panel answers for that node for this request only, the stored
     * preference stays the primary's. Its envelope is cached under its own
     * key, never as the primary's, and used when the panel cannot be reached.
     */
    suspend fun fetchNodeCandidates(nodeId: String): Result<List<ColituVpnConfig>> = withContext(Dispatchers.IO) {
        val path = "/config?node=${java.net.URLEncoder.encode(nodeId, "UTF-8")}"
        val envelope = when (val result = ColituApiClient.get(path)) {
            is ColituApiClient.ApiResult.Success -> {
                // An older panel ignores node= and answers for the preferred node: no spare then.
                if (envelopeServer(result.data) != nodeId) return@withContext Result.failure(Exception("SPARE_NOT_OFFERED"))
                ColituSecureStore.put(KEY_SPARE_ENVELOPE, result.data.toString())
                result.data
            }
            is ColituApiClient.ApiResult.Error -> {
                noteApiFailure(result.code)
                if (!ConfigCachePolicy.allowsFallback(result.code, result.isAuthError)) {
                    ColituSecureStore.remove(KEY_SPARE_ENVELOPE)
                    return@withContext Result.failure(Exception(result.message))
                }
                return@withContext cachedNodeCandidates(nodeId)?.let { Result.success(it) } ?: Result.failure(Exception(result.message))
            }
        }
        val rendered = runCatching { XrayMobileAdapter.renderCandidates(envelope) }.getOrNull()
            ?.filter { !it.rawConfig.isNullOrBlank() }
        if (rendered.isNullOrEmpty()) Result.failure(Exception("CONFIG_NOT_READY")) else Result.success(rendered)
    }

    /** Transports of the cached spare envelope for [nodeId], without a round trip; null when none is valid. */
    fun cachedNodeCandidates(nodeId: String, now: java.time.Instant = com.v2ray.ang.colitu.api.ColituClock.now()): List<ColituVpnConfig>? {
        if (ColituRecoveryTestMode.regularCache("cache") == null) return null
        val raw = ColituSecureStore.get(KEY_SPARE_ENVELOPE) ?: return null
        val envelope = runCatching { com.google.gson.JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
        if (envelopeServer(envelope) != nodeId) return null
        val expires = envelope.get("expires_at")?.takeIf { it.isJsonPrimitive }?.asString
            ?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() } ?: return null
        if (!now.isBefore(expires)) return null
        return runCatching { XrayMobileAdapter.renderCandidates(envelope, now) }.getOrNull()
            ?.filter { !it.rawConfig.isNullOrBlank() }
            ?.takeIf { it.isNotEmpty() }
    }

    private fun envelopeServer(envelope: JsonObject): String? =
        envelope.getAsJsonObject("server")?.get("id")?.takeIf { it.isJsonPrimitive }?.asString

    // ── Multihop routes ──────────────────────────────────────────────────────────

    /**
     * Transports of a multihop route (`GET /multihop/routes/{id}/config`, the
     * same envelope as /config), VLESS only. A route is not a node: it is
     * never sent as preferred_node_id, and its last good envelope lives under
     * its own key so it can never be mistaken for a node's. 404
     * MULTIHOP_ROUTE_NOT_FOUND means the route is gone (refresh the list).
     */
    suspend fun fetchRouteCandidates(routeId: String): Result<List<ColituVpnConfig>> = withContext(Dispatchers.IO) {
        bootstrapFailure()?.let { return@withContext Result.failure(Exception(it)) }
        val path = "/multihop/routes/${java.net.URLEncoder.encode(routeId, "UTF-8").replace("+", "%20")}/config"
        val envelope = when (val result = ColituApiClient.get(path)) {
            is ColituApiClient.ApiResult.Success -> {
                val rendered = runCatching { XrayMobileAdapter.renderCandidates(result.data) }
                rendered.exceptionOrNull()?.let { Log.w(TAG, "route config did not render: ${it.message}") }
                if (rendered.getOrNull().isNullOrEmpty()) {
                    return@withContext Result.failure(Exception(rendered.exceptionOrNull()?.message?.takeIf { it.startsWith("CONFIG_") } ?: "CONFIG_NOT_READY"))
                }
                ColituSecureStore.put(KEY_ROUTE_ENVELOPE, routeEnvelopeRecord(routeId, result.data))
                result.data
            }
            is ColituApiClient.ApiResult.Error -> {
                noteApiFailure(result.code)
                if (result.message == "MULTIHOP_ROUTE_NOT_FOUND") {
                    ColituSecureStore.remove(KEY_ROUTE_ENVELOPE)
                    return@withContext Result.failure(Exception(result.message))
                }
                if (!ConfigCachePolicy.allowsFallback(result.code, result.isAuthError)) {
                    return@withContext Result.failure(Exception(if (result.isAuthError) "auth_expired" else result.message))
                }
                cachedRouteEnvelope(routeId)
                    ?: return@withContext Result.failure(Exception(result.message))
            }
        }
        val restricted = ColituMultihop.restrictToVless(
            runCatching { XrayMobileAdapter.renderCandidates(envelope) }.getOrDefault(emptyList()).filter { !it.rawConfig.isNullOrBlank() },
        )
        if (restricted.isEmpty()) Result.failure(Exception("MULTIHOP_NEEDS_VLESS")) else Result.success(restricted)
    }

    /** VLESS transports from the cached envelope of [routeId], without a round trip; null when none is valid. */
    fun cachedRouteCandidates(routeId: String, now: java.time.Instant = com.v2ray.ang.colitu.api.ColituClock.now()): List<ColituVpnConfig>? {
        val envelope = cachedRouteEnvelope(routeId) ?: return null
        val expires = envelope.get("expires_at")?.takeIf { it.isJsonPrimitive }?.asString
            ?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() } ?: return null
        if (!now.isBefore(expires)) return null
        return runCatching { XrayMobileAdapter.renderCandidates(envelope, now) }.getOrNull()
            ?.let { ColituMultihop.restrictToVless(it) }
            ?.filter { !it.rawConfig.isNullOrBlank() }
            ?.takeIf { it.isNotEmpty() }
    }

    private fun routeEnvelopeRecord(routeId: String, envelope: JsonObject): String =
        JsonObject().apply {
            addProperty("route_id", routeId)
            add("envelope", envelope)
        }.toString()

    private fun cachedRouteEnvelope(routeId: String): JsonObject? {
        if (ColituRecoveryTestMode.regularCache("cache") == null) return null
        val raw = ColituSecureStore.get(KEY_ROUTE_ENVELOPE) ?: return null
        val record = runCatching { com.google.gson.JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
        if (record.get("route_id")?.takeIf { it.isJsonPrimitive }?.asString != routeId) return null
        val envelope = record.get("envelope")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return envelope.takeIf { ColituVpnConfig.fromJson(it) != null }
    }

    /**
     * Makes [serverId] the preferred node; GET /config then answers with
     * that node's profile. (The old follow-up POST /config/refresh with
     * revision 0 only made the panel render the whole config a second time.)
     */
    suspend fun selectServer(serverId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val body = JsonObject().apply { addProperty("preferred_node_id", serverId) }
        when (val saved = ColituApiClient.put("/me/preferences", body)) {
            is ColituApiClient.ApiResult.Error -> {
                noteApiFailure(saved.code)
                Result.failure(Exception(if (saved.isAuthError) "auth_expired" else saved.message))
            }
            is ColituApiClient.ApiResult.Success -> {
                // The cached profile belongs to the previous node.
                store.removeValueForKey(KEY_CONFIG_ETAG)
                Result.success(Unit)
            }
        }
    }
}
