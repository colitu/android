package com.v2ray.ang.colitu.app

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.v2ray.ang.colitu.api.ColituApiClient
import com.v2ray.ang.colitu.data.ColituGeckoConfigResult
import com.v2ray.ang.colitu.data.ColituGeckoOffer
import com.v2ray.ang.colitu.data.ColituGeckoRules
import com.v2ray.ang.colitu.data.ColituGeckoSignedConfig
import com.v2ray.ang.colitu.data.ColituGeckoStore
import com.v2ray.ang.colitu.data.ColituVpnConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The ColituGecko protocol in the app's process: the panel's offer
 * (`GET /gecko/config`, a full authenticated call) for the protocol picker and
 * Adaptive Connect, and the Gecko-only profile a connect imports. The VPN
 * process reads what this stores ([ColituGeckoStore]) at every core start.
 */
object ColituGeckoProtocol {
    private const val TAG = "ColituGecko"
    private const val CONFIG_PATH = "/gecko/config"
    /** A connect fetches the configuration at most this often (its attempts share one answer). */
    private const val FETCH_EVERY_MS = 2 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val offerChecked = AtomicBoolean()
    private val loading = AtomicBoolean()

    /** The panel's last real answer (Unknown: never asked); the picker lists ColituGecko by it. */
    var offer by mutableStateOf(ColituGeckoStore.offer)
        private set

    /** Listed in the protocol picker (offered, or offered but unavailable now). */
    val listed: Boolean get() = ColituGeckoRules.listed(offer)

    /** Pickable in the protocol picker (offered now). */
    val pickable: Boolean get() = ColituGeckoRules.pickable(offer)

    /** The profile a Gecko attempt imports (no server: the panel's anchors choose the exit). */
    fun profile(): ColituVpnConfig = ColituVpnConfig(
        serverId = "",
        configType = "xray-mobile-v1",
        protocolType = ColituGeckoRules.PROTOCOL,
        rawConfig = ColituGeckoRules.profileTemplate(),
        expiresAt = null,
        unlimited = false,
        revision = 1,
    )

    /**
     * Before a connect's attempts: refreshes the configuration in the
     * background (at most every [FETCH_EVERY_MS]) so the Gecko step finds a
     * current one. Never waited for: a slow or unreachable panel (whitelist,
     * blocked network) must not delay the connect.
     */
    fun prepare() {
        if (offer == ColituGeckoOffer.NotOffered) return
        val fresh = System.currentTimeMillis() - ColituGeckoStore.fetchedAt in 0 until FETCH_EVERY_MS
        if (fresh && ColituGeckoStore.config() != null) return
        if (!loading.compareAndSet(false, true)) return
        scope.launch {
            try {
                runCatching { load() }
            } finally {
                loading.set(false)
            }
        }
    }

    /**
     * The configuration for a Gecko attempt: the cached one when it is
     * fresh, else the panel's answer within [ColituGeckoRules.CONFIG_WAIT_MS]
     * (an older cached one when the panel does not answer in time). Null
     * when there is none.
     */
    suspend fun ensureConfig(): ColituGeckoSignedConfig? {
        val cached = ColituGeckoStore.config()
        val fresh = System.currentTimeMillis() - ColituGeckoStore.fetchedAt in 0 until FETCH_EVERY_MS
        if (cached != null && fresh) return cached
        withTimeoutOrNull(ColituGeckoRules.CONFIG_WAIT_MS) { runCatching { load() } }
        return ColituGeckoStore.config()
    }

    /** Once per app run (the home screen opens) the offer is checked in the background, so the picker can list ColituGecko. */
    fun checkOfferOnce() {
        if (!offerChecked.compareAndSet(false, true)) return
        ColituGeckoStore.forgetAlpha()
        scope.launch { runCatching { load() } }
    }

    private suspend fun load(): ColituGeckoConfigResult {
        val result = withTimeoutOrNull(ColituGeckoRules.CONFIG_WAIT_MS) {
            ColituGeckoConfigResult.fromApi(runCatching { ColituApiClient.get(CONFIG_PATH, direct = true) }.getOrNull())
        } ?: ColituGeckoConfigResult.UNKNOWN
        ColituGeckoStore.remember(result)
        offer = ColituGeckoStore.offer
        Log.w(TAG, "Colitu: Gecko: panel answered ${result.offer}")
        return result
    }
}
