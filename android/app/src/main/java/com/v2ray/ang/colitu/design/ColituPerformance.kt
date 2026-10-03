package com.v2ray.ang.colitu.design

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How much animation the phone can afford. Budget phones (Helio P35 class,
 * 3-4 GB RAM) struggled with a 1,250-point particle field plus blurred glows
 * at 60 fps: the UI kept a core busy and slowed the VPN service start.
 */
enum class PerformanceTier(
    /** Share of the particle count that is drawn. */
    val particleShare: Float,
    /** Minimum time between particle frames. */
    val frameIntervalNanos: Long,
    /** Soft coloured shadows (blur mask filters). */
    val blurShadows: Boolean,
) {
    Low(particleShare = 0.28f, frameIntervalNanos = 50_000_000L, blurShadows = false),
    Mid(particleShare = 0.6f, frameIntervalNanos = 16_000_000L, blurShadows = true),
    High(particleShare = 1f, frameIntervalNanos = 0L, blurShadows = true),
}

object ColituPerformance {
    var tier by mutableStateOf(PerformanceTier.High)
        private set

    /** Why the tier was chosen, for the logcat line at start-up. */
    var reason: String = ""
        private set

    fun init(context: Context) {
        val (chosen, why) = classify(context)
        tier = chosen
        reason = why
    }

    private fun classify(context: Context): Pair<PerformanceTier, String> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val ramGb = memory.totalMem / (1024.0 * 1024 * 1024)
        val cores = Runtime.getRuntime().availableProcessors()
        val sdk = Build.VERSION.SDK_INT
        val animatorScale = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        val mediaClass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0
        val summary = "ram=%.1fGB cores=%d sdk=%d animScale=%.1f mpc=%d".format(ramGb, cores, sdk, animatorScale, mediaClass)
        val tier = when {
            // Google's media performance class is only granted to capable phones.
            mediaClass >= Build.VERSION_CODES.S -> PerformanceTier.High
            am?.isLowRamDevice == true -> PerformanceTier.Low
            // Reported totals sit a little under the marketed size (4 GB -> ~3.7).
            ramGb < 4.5 -> PerformanceTier.Low
            sdk < Build.VERSION_CODES.Q -> PerformanceTier.Low
            cores <= 4 -> PerformanceTier.Low
            // "Remove animations" / reduced motion: keep motion minimal.
            animatorScale == 0f -> PerformanceTier.Low
            ramGb < 6.5 -> PerformanceTier.Mid
            else -> PerformanceTier.High
        }
        return tier to summary
    }

    /**
     * Called by the particle loop when frames keep arriving late: a phone that
     * looked fine on paper but cannot hold the frame rate steps down one tier.
     */
    fun reportSlowFrames() {
        tier = when (tier) {
            PerformanceTier.High -> PerformanceTier.Mid
            else -> PerformanceTier.Low
        }
    }
}
