package com.v2ray.ang.colitu.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.tencent.mmkv.MMKV
import com.v2ray.ang.R

/** A launcher icon the user can pick; [alias] is its activity-alias in the manifest. */
enum class AppIcon(val key: String, val alias: String, val preview: Int) {
    Default("default", "LauncherClassic", R.mipmap.ic_launcher_foreground),
    Indigo("indigo", "LauncherIndigo", R.drawable.icon_classic_full),
    Light("light", "LauncherLight", R.drawable.icon_light_full),
    Midnight("midnight", "LauncherMidnight", R.drawable.icon_midnight_full),
    Aurora("aurora", "LauncherAurora", R.drawable.icon_aurora_full),
    Gold("gold", "LauncherGold", R.drawable.icon_gold_full),
    Diamond("diamond", "LauncherDiamond", R.drawable.icon_diamond_full),
    Sunset("sunset", "LauncherSunset", R.drawable.icon_sunset_full),
    Stamp("stamp", "LauncherStamp", R.drawable.icon_stamp_full),
}

/**
 * Launcher icon switch (Colitu Passport rewards). One activity-alias per
 * icon; exactly one is enabled. Disabling the alias that started the task
 * closes the app, so a choice is stored ([request]) and applied when the app
 * goes to the background ([applyPending]). Launchers may redraw late.
 */
object ColituAppIcon {
    private val store by lazy { MMKV.mmkvWithID("COLITU_SETTINGS", MMKV.MULTI_PROCESS_MODE) }
    private const val KEY_PENDING = "app_icon_pending_key"
    private const val PREFIX = "com.v2ray.ang.colitu.ui."

    fun current(context: Context): AppIcon = runCatching {
        AppIcon.entries.firstOrNull { it != AppIcon.Default && enabled(context, it) } ?: AppIcon.Default
    }.getOrDefault(AppIcon.Default)

    /** The icon the user picked, applied or still waiting. */
    fun chosen(context: Context): AppIcon =
        runCatching { store.decodeString(KEY_PENDING) }.getOrNull()?.let { k -> AppIcon.entries.firstOrNull { it.key == k } } ?: current(context)

    fun request(icon: AppIcon) {
        runCatching { store.encode(KEY_PENDING, icon.key) }
    }

    fun applyPending(context: Context) {
        val key = runCatching { store.decodeString(KEY_PENDING) }.getOrNull() ?: return
        runCatching { store.removeValueForKey(KEY_PENDING) }
        val icon = AppIcon.entries.firstOrNull { it.key == key } ?: return
        if (icon == current(context)) return
        runCatching {
            val pm = context.packageManager
            // Enable the new entry first so the app never has no launcher icon.
            pm.setComponentEnabledSetting(component(context, icon), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            AppIcon.entries.filter { it != icon }.forEach {
                pm.setComponentEnabledSetting(component(context, it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }
    }

    private fun component(context: Context, icon: AppIcon) = ComponentName(context, PREFIX + icon.alias)

    private fun enabled(context: Context, icon: AppIcon): Boolean {
        val state = context.packageManager.getComponentEnabledSetting(component(context, icon))
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }
}
