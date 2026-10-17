package com.v2ray.ang.colitu.screens

import androidx.compose.runtime.Composable
import com.v2ray.ang.colitu.app.ColituController

/**
 * Store-specific purchase UI. Only the Google Play build registers one
 * (src/playstore, Google Play Billing); in every other build it stays null
 * and the Plan tab is unchanged.
 */
object ColituStore {
    var purchaseSection: (@Composable (ColituController) -> Unit)? = null

    /** The Play build with Play Billing: plans are bought inside the app through Google Play. */
    val playBilling: Boolean get() = PLAY_POLICY && purchaseSection != null
}
