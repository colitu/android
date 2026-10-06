package com.v2ray.ang.colitu.repository

import android.util.Log
import com.google.gson.JsonObject
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.colitu.api.ColituApiClient
import com.v2ray.ang.colitu.api.ColituSecureStore
import com.v2ray.ang.colitu.data.ColituMultihop
import com.v2ray.ang.colitu.data.ColituServerListResponse
import com.v2ray.ang.colitu.data.ColituVpnConfig
import com.v2ray.ang.colitu.data.ClientBootstrapPolicy
import com.v2ray.ang.colitu.data.XrayMobileAdapter
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ColituServerRepository {

    private const val TAG = "ColituServerRepo"
    private const val STORE_ID = "COLITU_SERVERS"
    private const val KEY_SELECTED_SERVER = "selected_server_id"
    private const val KEY_CONFIG_ETAG = "config_etag"
    /** Last good envelope of a multihop route, kept apart from the node envelope (see [fetchRouteCandidates]). */
    private const val KEY_ROUTE_ENVELOPE = "vpn_lkg_route"

    private val store by lazy { MMKV.mmkvWithID(STORE_ID, MMKV.MULTI_PROCESS_MODE) }

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
                if (!ConfigCachePolicy.allowsFallback(bootstrap.code, bootstrap.isAuthError)) {
                    clearConfigCaches()
                    bootstrap.message
                } else null
            }
        }

    private fun clearConfigCaches() {
        ColituSecureStore.remove("vpn_lkg_envelope", KEY_ROUTE_ENVELOPE)
        store.removeValueForKey(KEY_CONFIG_ETAG)
    }

    private suspend fun fetchConfigEnvelope(): Result<JsonObject> = withContext(Dispatchers.IO) {
        bootstrapFailure()?.let { return@withContext Result.failure(Exception(it)) }
        val etag = if (ColituSecureStore.get("vpn_lkg_envelope").isNullOrBlank()) {
            store.removeValueForKey(KEY_CONFIG_ETAG)
            null
        } else {
            store.decodeString(KEY_CONFIG_ETAG)
        }
        val headers = if (etag.isNullOrBlank()) emptyMap() else mapOf("If-None-Match" to etag)
        when (val result = ColituApiClient.get("/config", headers)) {
            is ColituApiClient.ApiResult.Success -> {
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
        val raw = ColituSecureStore.get("vpn_lkg_envelope") ?: return null
        val envelope = runCatching { com.google.gson.JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
        return envelope.takeIf { ColituVpnConfig.fromJson(it) != null }
    }

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
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(if (saved.isAuthError) "auth_expired" else saved.message))
            is ColituApiClient.ApiResult.Success -> {
                // The cached profile belongs to the previous node.
                store.removeValueForKey(KEY_CONFIG_ETAG)
                Result.success(Unit)
            }
        }
    }
}
