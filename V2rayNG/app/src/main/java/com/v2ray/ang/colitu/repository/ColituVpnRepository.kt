package com.v2ray.ang.colitu.repository

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.colitu.api.ColituApiClient
import com.v2ray.ang.colitu.data.ColituVpnConfig
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ColituVpnRepository {

    private const val TAG = "ColituVpnRepo"

    /**
     * Fetches VPN config for a server, imports it into v2ray, and returns
     * the GUID of the imported profile, or throws on error.
     */
    suspend fun prepareAndImportConfig(
        context: Context,
        serverId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        SettingsManager.initAssets(context, context.assets)

        val configResult = ColituServerRepository.fetchConfig(serverId)
        if (configResult.isFailure) {
            val err = configResult.exceptionOrNull()
            Log.e(TAG, "[VPN] configuration fetch failed: ${err?.message}")
            return@withContext Result.failure(err!!)
        }
        val config = configResult.getOrThrow()

        try {
            val colituSubId = ensureColituSubscription(context)
            val importText = resolveImportText(config)
            if (importText == null) {
                Log.e(TAG, "[VPN] validated configuration is unavailable")
                return@withContext Result.failure(Exception("CONFIG_NOT_READY"))
            }

            val (count, _) = AngConfigManager.importBatchConfig(importText, colituSubId, false)

            if (count <= 0) {
                Log.e(TAG, "[VPN] importBatchConfig returned 0 for server=$serverId — invalid config format?")
                return@withContext Result.failure(Exception("CONFIG_NOT_READY"))
            }

            val serverList = MmkvManager.decodeServerList(colituSubId)
            val guid = serverList.firstOrNull()
            if (guid == null) {
                return@withContext Result.failure(Exception("CONFIG_NOT_READY"))
            }

            Result.success(guid)
        } catch (e: Exception) {
            Log.e(TAG, "[VPN] configuration import failed")
            Result.failure(Exception("CONFIG_NOT_READY"))
        }
    }

    /**
     * Imports one rendered runtime config as the Colitu profile and returns
     * its GUID. Earlier Colitu profiles are replaced so the list never grows.
     */
    suspend fun importRuntimeConfig(context: Context, config: ColituVpnConfig): Result<String> =
        withContext(Dispatchers.IO) {
            SettingsManager.initAssets(context, context.assets)
            try {
                val importText = resolveImportText(config)
                    ?: return@withContext Result.failure(Exception("CONFIG_NOT_READY"))
                val subId = ensureColituSubscription(context)
                MmkvManager.decodeServerList(subId).forEach { MmkvManager.removeServer(it) }
                val (count, _) = AngConfigManager.importBatchConfig(importText, subId, false)
                if (count <= 0) return@withContext Result.failure(Exception("CONFIG_NOT_READY"))
                val guid = MmkvManager.decodeServerList(subId).firstOrNull()
                    ?: return@withContext Result.failure(Exception("CONFIG_NOT_READY"))
                Result.success(guid)
            } catch (e: Exception) {
                Log.e(TAG, "[VPN] runtime configuration import failed")
                Result.failure(Exception("CONFIG_NOT_READY"))
            }
        }

    private fun resolveImportText(config: ColituVpnConfig): String? =
        config.rawConfig?.takeIf { it.isNotBlank() && config.revision > 0 }

    private fun ensureColituSubscription(context: Context): String {
        val subscriptions = MmkvManager.decodeSubscriptions()
        val existing = subscriptions.find { it.subscription.remarks == "Colitu" }
        if (existing != null) return existing.guid

        val subItem = com.v2ray.ang.dto.SubscriptionItem().apply {
            remarks = "Colitu"
            url = ""
            enabled = true
        }
        val guid = java.util.UUID.randomUUID().toString()
        MmkvManager.encodeSubscription(guid, subItem)
        return guid
    }

    // ── API health ───────────────────────────────────────────────────────────────

    /** Authenticated control-plane readiness using the canonical mobile bootstrap. */
    suspend fun checkApiHealth(): Boolean = withContext(Dispatchers.IO) {
        when (ColituApiClient.get("/client/bootstrap")) {
            is ColituApiClient.ApiResult.Success -> true
            else -> false
        }
    }

    /** Backend bootstrap readiness for the authenticated device. */
    suspend fun fetchVpnStatus(): JsonObject? = withContext(Dispatchers.IO) {
        when (val r = ColituApiClient.get("/client/bootstrap")) {
            is ColituApiClient.ApiResult.Success -> r.data
            else -> null
        }
    }

    /**
     * Returns the backend-owned version policy from the canonical bootstrap.
     */
    suspend fun fetchAppVersion(): JsonObject? = withContext(Dispatchers.IO) {
        when (val r = ColituApiClient.get("/client/bootstrap")) {
            is ColituApiClient.ApiResult.Success -> r.data
            else -> null
        }
    }
}
