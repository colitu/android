package com.v2ray.ang.colitu.repository

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.v2ray.ang.colitu.api.ColituApiClient
import com.v2ray.ang.colitu.api.ColituLocalProxy
import com.v2ray.ang.colitu.data.ColituAdBlock
import com.v2ray.ang.colitu.data.ColituRuBypass
import com.v2ray.ang.colitu.data.ColituVpnConfig
import com.v2ray.ang.colitu.data.XrayMobileAdapter
import com.v2ray.ang.dto.SubscriptionItem
import com.v2ray.ang.fmt.CustomFmt
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ColituVpnRepository {

    private const val TAG = "ColituVpnRepo"
    private const val SUBSCRIPTION_NAME = "Colitu"

    /**
     * Stores one rendered runtime config as the only Colitu profile and
     * returns its GUID. The profile gets this connection's own local SOCKS
     * port and account ([ColituLocalProxy]).
     */
    suspend fun importRuntimeConfig(context: Context, config: ColituVpnConfig): Result<String> =
        withContext(Dispatchers.IO) {
            SettingsManager.initAssets(context, context.assets)
            try {
                val raw = config.rawConfig?.takeIf { it.isNotBlank() && config.revision != 0L }
                    ?: return@withContext Result.failure(Exception("CONFIG_NOT_READY"))
                val runtime = ColituAdBlock.apply(
                    ColituRuBypass.apply(XrayMobileAdapter.withLocalProxy(raw, ColituLocalProxy.newSession())),
                )
                val subId = ensureColituSubscription()
                MmkvManager.decodeServerList(subId).forEach { MmkvManager.removeServer(it) }
                val profile = CustomFmt.parse(runtime).apply {
                    subscriptionId = subId
                    // Shown in the VPN notification: Colitu's transport name only.
                    remarks = listOf("Colitu", XrayMobileAdapter.transportName(config.protocolType))
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                }
                val guid = MmkvManager.encodeServerConfig("", profile)
                MmkvManager.encodeServerRaw(guid, runtime)
                Result.success(guid)
            } catch (e: Exception) {
                Log.e(TAG, "runtime configuration import failed: ${e.javaClass.simpleName}")
                Result.failure(Exception("CONFIG_NOT_READY"))
            }
        }

    private fun ensureColituSubscription(): String {
        MmkvManager.decodeSubscriptions().find { it.subscription.remarks == SUBSCRIPTION_NAME }?.let { return it.guid }
        val subItem = SubscriptionItem().apply {
            remarks = SUBSCRIPTION_NAME
            url = ""
            enabled = true
        }
        val guid = java.util.UUID.randomUUID().toString()
        MmkvManager.encodeSubscription(guid, subItem)
        return guid
    }

    /** Backend bootstrap (policy, entitlement, maintenance) for the signed-in device. */
    suspend fun fetchVpnStatus(): JsonObject? = withContext(Dispatchers.IO) {
        when (val r = ColituApiClient.get("/client/bootstrap")) {
            is ColituApiClient.ApiResult.Success -> r.data
            else -> null
        }
    }
}
