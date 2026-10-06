package com.v2ray.ang.colitu.repository

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.v2ray.ang.colitu.api.ColituApiClient
import com.v2ray.ang.colitu.api.ColituLocalProxy
import com.v2ray.ang.colitu.data.ColituAdBlock
import com.v2ray.ang.colitu.data.ColituRuBypass
import com.v2ray.ang.colitu.data.ColituSplitTunnel
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
                val proxied = XrayMobileAdapter.withLocalProxy(raw, ColituLocalProxy.newSession())
                val runtime = ColituAdBlock.apply(
                    ColituSplitTunnel.configure(
                        ColituRuBypass.configure(proxied, config.serverCountry, ColituRuBypass.privacyMode),
                    ),
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
                ColituRuBypass.profileCountry = config.serverCountry.orEmpty()
                Result.success(guid)
            } catch (e: Exception) {
                Log.e(TAG, "runtime configuration import failed: ${e.javaClass.simpleName}")
                Result.failure(Exception("CONFIG_NOT_READY"))
            }
        }

    /**
     * Privacy mode changed while no tunnel runs: the stored Colitu profile,
     * which the quick-settings tile, the widget and Always-on start without
     * the app, is rebuilt for the new setting right away. Turning privacy mode
     * on always removes the Russian direct rules; turning it off adds them back
     * only when the profile's server country is known.
     */
    suspend fun reapplyRuBypassToStoredProfile(privacyMode: Boolean) = withContext(Dispatchers.IO) {
        val subId = MmkvManager.decodeSubscriptions().find { it.subscription.remarks == SUBSCRIPTION_NAME }?.guid
            ?: return@withContext
        val country = ColituRuBypass.profileCountry
        if (!privacyMode && country == null) return@withContext
        MmkvManager.decodeServerList(subId).forEach { guid ->
            val raw = MmkvManager.decodeServerRaw(guid) ?: return@forEach
            runCatching { ColituRuBypass.configure(raw, country?.ifEmpty { null }, privacyMode) }
                .onSuccess { if (it != raw) MmkvManager.encodeServerRaw(guid, it) }
                .onFailure { Log.e(TAG, "stored profile update failed: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * Split tunneling changed while no tunnel runs: the stored profile (tile,
     * widget, Always-on) gets the new site rules right away; the app list is
     * read by the VPN service at every start.
     */
    suspend fun reapplySplitTunnelToStoredProfile() = withContext(Dispatchers.IO) {
        val subId = MmkvManager.decodeSubscriptions().find { it.subscription.remarks == SUBSCRIPTION_NAME }?.guid
            ?: return@withContext
        MmkvManager.decodeServerList(subId).forEach { guid ->
            val raw = MmkvManager.decodeServerRaw(guid) ?: return@forEach
            runCatching { ColituSplitTunnel.configure(raw) }
                .onSuccess { if (it != raw) MmkvManager.encodeServerRaw(guid, it) }
                .onFailure { Log.e(TAG, "stored profile update failed: ${it.javaClass.simpleName}") }
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
    suspend fun fetchVpnStatus(): JsonObject? = (fetchBootstrap() as? Bootstrap.Ok)?.json

    sealed class Bootstrap {
        data class Ok(val json: JsonObject) : Bootstrap()
        /** 403 DEVICE_OVER_LIMIT: this device is paused. */
        data class Paused(val pause: com.v2ray.ang.colitu.data.ColituDevicePause) : Bootstrap()
        data object Failed : Bootstrap()
    }

    suspend fun fetchBootstrap(): Bootstrap = withContext(Dispatchers.IO) {
        when (val r = ColituApiClient.get("/client/bootstrap")) {
            is ColituApiClient.ApiResult.Success -> Bootstrap.Ok(r.data)
            is ColituApiClient.ApiResult.Error ->
                if (r.message == com.v2ray.ang.colitu.data.ColituDevicePause.CODE) {
                    Bootstrap.Paused(com.v2ray.ang.colitu.data.ColituDevicePause.fromJson(r.body))
                } else Bootstrap.Failed
        }
    }
}
