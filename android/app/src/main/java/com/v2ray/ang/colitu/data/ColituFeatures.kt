package com.v2ray.ang.colitu.data

import com.v2ray.ang.BuildConfig

/**
 * Release switches. Adaptive Connect 3.0 (the hinted start order from
 * `network_hints.preferred` and the recovery set, fetch and use) is on when the
 * panel turns it on for this device ([ColituRemoteFeatures], admin rollout by
 * platform and share of devices) or the build forces it:
 * [ADAPTIVE_CONNECT_3] (`-PcolituAdaptiveConnect3=true`, default false) or the
 * recovery test build (`COLITU_RECOVERY_TEST`), which can only connect through
 * the recovery set. Off, the connect path is exactly the one of Adaptive
 * Connect 2.0.
 */
object ColituFeatures {
    const val ADAPTIVE_CONNECT_3: Boolean = BuildConfig.COLITU_ADAPTIVE_CONNECT_3

    fun adaptiveConnect3(
        flag: Boolean = ADAPTIVE_CONNECT_3,
        recoveryTest: Boolean = ColituRecoveryTestMode.enabled,
        remote: Boolean = ColituRemoteFeatures.adaptiveConnect3,
    ): Boolean = flag || recoveryTest || remote

    /** Start order from `network_hints.preferred` when the network has no memory. */
    fun hintedStart(
        flag: Boolean = ADAPTIVE_CONNECT_3,
        recoveryTest: Boolean = ColituRecoveryTestMode.enabled,
        remote: Boolean = ColituRemoteFeatures.adaptiveConnect3,
    ): Boolean = adaptiveConnect3(flag, recoveryTest, remote)

    /** Recovery set: fetching it, and every connect path that uses it. */
    fun recoverySet(
        flag: Boolean = ADAPTIVE_CONNECT_3,
        recoveryTest: Boolean = ColituRecoveryTestMode.enabled,
        remote: Boolean = ColituRemoteFeatures.adaptiveConnect3,
    ): Boolean = adaptiveConnect3(flag, recoveryTest, remote)

    /** [ColituRecoverySet.shouldTryAtGiveUp] behind the switch: off, a connect never turns to the set. */
    fun recoveryAtGiveUp(
        automatic: Boolean,
        apiUnreachable: Boolean,
        alreadyTried: Boolean,
        flag: Boolean = ADAPTIVE_CONNECT_3,
        recoveryTest: Boolean = ColituRecoveryTestMode.enabled,
    ): Boolean = recoverySet(flag, recoveryTest) && ColituRecoverySet.shouldTryAtGiveUp(automatic, apiUnreachable, alreadyTried)
}
