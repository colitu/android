package com.v2ray.ang.colitu.design

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * FLAG_SECURE on the activity window while the calling screen is shown (sign
 * in, MFA, e-mail code, password reset, account): no screenshots, screen
 * recording or recents thumbnail of codes, e-mail addresses and passwords.
 * Nested or overlapping callers are counted, so the flag is cleared only when
 * the last of them leaves.
 */
@Composable
fun ColituSecureWindow() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        ColituSecureWindowCount.acquire(activity)
        onDispose { ColituSecureWindowCount.release(activity) }
    }
}

private object ColituSecureWindowCount {
    private val holders = java.util.WeakHashMap<Activity, Int>()

    fun acquire(activity: Activity) {
        val count = (holders[activity] ?: 0) + 1
        holders[activity] = count
        if (count == 1) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(activity: Activity) {
        val count = (holders[activity] ?: 1) - 1
        if (count <= 0) {
            holders.remove(activity)
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            holders[activity] = count
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
