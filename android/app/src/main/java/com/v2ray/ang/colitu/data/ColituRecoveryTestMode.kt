package com.v2ray.ang.colitu.data

import com.v2ray.ang.BuildConfig

/**
 * Test build for the recovery set (`-PcolituRecoveryTest=true`): every API
 * base is an unroutable address (connect timeout), the endpoint list is not
 * refreshed and the regular config caches count as unusable, so a connect
 * can only succeed through the stored recovery set. With the flag off every
 * function returns its input unchanged.
 */
object ColituRecoveryTestMode {
    const val BLACKHOLE_BASE = "https://10.255.255.1/api/v1"
    const val STARTUP_LOG = "RECOVERY TEST BUILD: API blackholed, regular cache ignored"

    val enabled: Boolean get() = BuildConfig.COLITU_RECOVERY_TEST

    fun apiBases(normal: List<String>, enabled: Boolean = this.enabled): List<String> =
        if (enabled) listOf(BLACKHOLE_BASE) else normal

    fun preferredBase(normal: String, enabled: Boolean = this.enabled): String =
        if (enabled) BLACKHOLE_BASE else normal

    fun endpointListRefresh(normal: Boolean, enabled: Boolean = this.enabled): Boolean =
        if (enabled) false else normal

    /** A regular cached config (primary, spare or route): none while testing. The recovery set is read through its own path. */
    fun <T> regularCache(cached: T?, enabled: Boolean = this.enabled): T? =
        if (enabled) null else cached
}
