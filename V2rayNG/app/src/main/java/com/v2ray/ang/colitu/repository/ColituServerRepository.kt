package com.v2ray.ang.colitu.repository

import android.util.Log
import com.google.gson.JsonObject
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.colitu.api.ColituApiClient
import com.v2ray.ang.colitu.api.ColituSecureStore
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
    private const val KEY_AUTO_CONNECT_SERVER = "auto_connect_server_id"
    private const val KEY_CONFIG_ETAG = "config_etag"

    private val store by lazy { MMKV.mmkvWithID(STORE_ID, MMKV.MULTI_PROCESS_MODE) }

    fun getSelectedServerId(): String? = store.decodeString(KEY_SELECTED_SERVER)
    fun setSelectedServerId(id: String) = store.encode(KEY_SELECTED_SERVER, id)
    fun requestAutoConnect(serverId: String) = store.encode(KEY_AUTO_CONNECT_SERVER, serverId)
    fun consumeAutoConnectServerId(): String? {
        val serverId = store.decodeString(KEY_AUTO_CONNECT_SERVER)
        if (!serverId.isNullOrBlank()) store.removeValueForKey(KEY_AUTO_CONNECT_SERVER)
        return serverId
    }

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

    suspend fun fetchConfig(serverId: String): Result<ColituVpnConfig> =
        fetchConfigEnvelope().mapCatching { envelope ->
            ColituVpnConfig.fromJson(envelope) ?: throw Exception("parse_error")
        }

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

    private suspend fun fetchConfigEnvelope(): Result<JsonObject> = withContext(Dispatchers.IO) {
        when (val bootstrap = ColituApiClient.get("/client/bootstrap")) {
            is ColituApiClient.ApiResult.Success -> {
                val policy = runCatching { ClientBootstrapPolicy.fromJson(bootstrap.data) }.getOrNull()
                    ?: return@withContext Result.failure(Exception("CONFIG_NOT_READY"))
                if (policy.denial != null) {
                    ColituSecureStore.remove("vpn_lkg_envelope")
                    store.removeValueForKey(KEY_CONFIG_ETAG)
                    return@withContext Result.failure(Exception(policy.denial))
                }
            }
            is ColituApiClient.ApiResult.Error -> {
                if (!ConfigCachePolicy.allowsFallback(bootstrap.code, bootstrap.isAuthError)) {
                    ColituSecureStore.remove("vpn_lkg_envelope")
                    store.removeValueForKey(KEY_CONFIG_ETAG)
                    return@withContext Result.failure(Exception(bootstrap.message))
                }
            }
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

    /** GET /me/usage — authoritative usage and subscription summary */
    suspend fun fetchVpnStats(): Result<JsonObject> = withContext(Dispatchers.IO) {
        when (val result = ColituApiClient.get("/me/usage")) {
            is ColituApiClient.ApiResult.Success -> Result.success(result.data)
            is ColituApiClient.ApiResult.Error -> {
                if (result.isAuthError) Result.failure(Exception("auth_expired"))
                else Result.failure(Exception(result.message))
            }
        }
    }

    // ── JsonObject helpers ───────────────────────────────────────────────────────

    private fun JsonObject.tryInt(key: String): Int? =
        if (has(key) && !get(key).isJsonNull && get(key).isJsonPrimitive)
            try { get(key).asInt } catch (_: Exception) { null }
        else null

    private fun JsonObject.tryString(key: String): String? =
        if (has(key) && !get(key).isJsonNull && get(key).isJsonPrimitive)
            get(key).asString.takeIf { it.isNotBlank() }
        else null

    suspend fun selectServer(serverId:String):Result<Unit> = withContext(Dispatchers.IO) {
        val body=JsonObject().apply{addProperty("preferred_node_id",serverId)}
        when(val saved=ColituApiClient.put("/me/preferences",body)){
            is ColituApiClient.ApiResult.Error->Result.failure(Exception(saved.message))
            is ColituApiClient.ApiResult.Success->when(val refreshed=ColituApiClient.post("/config/refresh",JsonObject().apply{addProperty("current_revision",0)})){
                is ColituApiClient.ApiResult.Error->Result.failure(Exception(refreshed.message));is ColituApiClient.ApiResult.Success->Result.success(Unit)}
        }
    }
}
