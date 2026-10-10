package com.v2ray.ang.colitu.data

import com.v2ray.ang.BuildConfig

/**
 * Release switches. [ADAPTIVE_CONNECT_3] (`-PcolituAdaptiveConnect3=true`,
 * default false) turns on the two Adaptive Connect 3.0 behaviours: the hinted
 * start order (`network_hints.preferred`) and the recovery set (fetch and use).
 * Off, the connect path is exactly the one of Adaptive Connect 2.0. The recovery
 * test build (`COLITU_RECOVERY_TEST`) forces the feature on, since it can only
 * connect through the recovery set.
 */
object ColituFeatures {
    const val ADAPTIVE_CONNECT_3: Boolean = BuildConfig.COLITU_ADAPTIVE_CONNECT_3

    fun adaptiveConnect3(
        flag: Boolean = ADAPTIVE_CONNECT_3,
        recoveryTest: Boolean = ColituRecoveryTestMode.enabled,
    ): Boolean = flag || recoveryTest

    /** Start order from `network_hints.preferred` when the network has no memory. */
    fun hintedStart(flag: Boolean = ADAPTIVE_CONNECT_3, recoveryTest: Boolean = ColituRecoveryTestMode.enabled): Boolean =
        adaptiveConnect3(flag, recoveryTest)

    /** Recovery set: fetching it, and every connect path that uses it. */
    fun recoverySet(flag: Boolean = ADAPTIVE_CONNECT_3, recoveryTest: Boolean = ColituRecoveryTestMode.enabled): Boolean =
        adaptiveConnect3(flag, recoveryTest)

    /** [ColituRecoverySet.shouldTryAtGiveUp] behind the switch: off, a connect never turns to the set. */
    fun recoveryAtGiveUp(
        automatic: Boolean,
        apiUnreachable: Boolean,
        alreadyTried: Boolean,
        flag: Boolean = ADAPTIVE_CONNECT_3,
        recoveryTest: Boolean = ColituRecoveryTestMode.enabled,
    ): Boolean = recoverySet(flag, recoveryTest) && ColituRecoverySet.shouldTryAtGiveUp(automatic, apiUnreachable, alreadyTried)
}
