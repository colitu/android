package com.v2ray.ang.colitu.screens

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.colitu.data.ColituSplitTunnel
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituBackdrop
import com.v2ray.ang.colitu.design.ColituButton
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituField
import com.v2ray.ang.colitu.design.ColituIcon
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituNotice
import com.v2ray.ang.colitu.design.ColituSpinner
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.ColituTile
import com.v2ray.ang.colitu.design.pressable
import com.v2ray.ang.colitu.l10n.ColituLoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An installed app with a launcher entry, as the picker lists it. */
class SplitApp(val packageName: String, val label: String, val icon: ImageBitmap?)

/**
 * Apps that show up in the launcher. Package visibility (Android 11+) comes
 * from the manifest's <queries> launcher intent, so no QUERY_ALL_PACKAGES is
 * needed; apps without a launcher icon are not listed. Colitu itself is left
 * out: it always stays outside its own tunnel.
 */
suspend fun loadSplitApps(context: Context): List<SplitApp> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
    val infos = (pm.queryIntentActivities(launcher, 0) + pm.queryIntentActivities(leanback, 0))
        .distinctBy { it.activityInfo.packageName }
        .filter { it.activityInfo.packageName != BuildConfig.APPLICATION_ID }
    val size = (40 * context.resources.displayMetrics.density).toInt().coerceAtLeast(32)
    infos.map { info ->
        val app = info.activityInfo.applicationInfo
        SplitApp(
            packageName = app.packageName,
            label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(app.packageName),
            icon = runCatching { pm.getApplicationIcon(app).toBitmap(size, size).asImageBitmap() }.getOrNull(),
        )
    }.sortedBy { it.label.lowercase() }
}

/**
 * Split tunneling settings: mode, apps and sites/addresses. Changes are kept
 * here and applied once on "Done" (or back), so a live connection reconnects
 * only once.
 */
@Composable
fun SplitTunnelDialog(initial: ColituSplitTunnel.Settings, onDone: (ColituSplitTunnel.Settings) -> Unit) {
    val loc = ColituLoc
    val context = LocalContext.current
    var mode by remember { mutableStateOf(initial.mode) }
    var apps by remember { mutableStateOf(initial.apps) }
    var domains by remember { mutableStateOf(initial.domains) }
    var ips by remember { mutableStateOf(initial.ips) }
    var entry by remember { mutableStateOf("") }
    var entryError by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var installed by remember { mutableStateOf<List<SplitApp>?>(null) }

    LaunchedEffect(Unit) { installed = runCatching { loadSplitApps(context) }.getOrDefault(emptyList()) }

    fun done() = onDone(ColituSplitTunnel.Settings(mode, apps, domains, ips))

    fun addEntry() {
        val value = entry.trim()
        if (value.isEmpty()) return
        if (domains.size + ips.size >= ColituSplitTunnel.MAX_ENTRIES) {
            entryError = loc["split.full"]
            return
        }
        val ip = ColituSplitTunnel.normalizeIp(value)
        val domain = if (ip == null) ColituSplitTunnel.normalizeDomain(value) else null
        when {
            ip != null -> if (ip !in ips) ips = ips + ip
            domain != null -> if (domain !in domains) domains = domains + domain
            else -> {
                entryError = loc["split.invalid"]
                return
            }
        }
        entry = ""
        entryError = null
    }

    Dialog(onDismissRequest = { done() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { done() }
        ColituBackdrop(glow = false) {
            val filtered = installed?.filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
            LazyColumn(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
                contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 28.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(CircleShape).pressable({ done() }), contentAlignment = Alignment.Center) {
                            ColituIcon(ColituIcons.ChevronLeft, ColituColors.text, 20.dp)
                        }
                        Spacer(Modifier.width(8.dp))
                        CText(loc["split.title"], ColituText.h2, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(14.dp))
                    ColituSplitTunnel.Mode.entries.forEach { m ->
                        ModeRow(
                            title = loc[when (m) { ColituSplitTunnel.Mode.Off -> "split.off"; ColituSplitTunnel.Mode.Bypass -> "split.bypass"; ColituSplitTunnel.Mode.Only -> "split.only" }],
                            hint = if (m == ColituSplitTunnel.Mode.Off) loc["split.offHint"] else null,
                            selected = mode == m,
                            onClick = { mode = m },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (mode == ColituSplitTunnel.Mode.Only) {
                        ColituNotice(loc["split.onlyNote"], error = false)
                        Spacer(Modifier.height(8.dp))
                    }
                    if (mode != ColituSplitTunnel.Mode.Off) {
                        ColituNotice(loc["split.lockdown"], error = false)
                        Spacer(Modifier.height(4.dp))
                        CText(loc["split.privacyNote"], ColituText.small, Modifier.padding(start = 4.dp, top = 4.dp))
                    }
                }
                if (mode != ColituSplitTunnel.Mode.Off) {
                    item {
                        Spacer(Modifier.height(18.dp))
                        SectionTitle(loc["split.sites"])
                        CText(loc["split.sitesHint"], ColituText.small, Modifier.padding(start = 4.dp, bottom = 8.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            ColituField(
                                value = entry,
                                onChange = { entry = it.take(300); entryError = null },
                                label = null,
                                modifier = Modifier.weight(1f),
                                hint = "example.com · 10.0.0.0/8",
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done,
                                onSubmit = { addEntry() },
                                error = entryError,
                            )
                            Spacer(Modifier.width(8.dp))
                            ColituButton(loc["split.add"], { addEntry() }, expand = false, height = 52.dp)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    items(domains + ips, key = { "e:$it" }) { value ->
                        ColituTile(padding = PaddingValues(start = 14.dp, top = 6.dp, end = 6.dp, bottom = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CText(value, ColituText.body, Modifier.weight(1f), maxLines = 1)
                                Box(
                                    Modifier.size(40.dp).clip(CircleShape).pressable({
                                        domains = domains - value
                                        ips = ips - value
                                    }),
                                    contentAlignment = Alignment.Center,
                                ) { ColituIcon(ColituIcons.XCircleOutline, ColituColors.dim, 20.dp) }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    item {
                        Spacer(Modifier.height(14.dp))
                        SectionTitle(loc["split.apps"] + if (apps.isNotEmpty()) " · ${apps.size}" else "")
                        ColituField(
                            value = query,
                            onChange = { query = it.take(60) },
                            label = null,
                            hint = loc["split.search"],
                            prefix = ColituIcons.Search,
                            imeAction = ImeAction.Search,
                            pill = true,
                        )
                        Spacer(Modifier.height(10.dp))
                        if (filtered == null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ColituSpinner()
                                Spacer(Modifier.width(10.dp))
                                CText(loc["split.loading"], ColituText.small)
                            }
                        }
                    }
                    items(filtered.orEmpty(), key = { "a:${it.packageName}" }) { app ->
                        AppRow(app, app.packageName in apps) { on -> apps = if (on) apps + app.packageName else apps - app.packageName }
                        Spacer(Modifier.height(6.dp))
                    }
                }
                item {
                    Spacer(Modifier.height(16.dp))
                    ColituButton(loc["split.save"], { done() })
                }
            }
        }
    }
}

@Composable
private fun ModeRow(title: String, hint: String?, selected: Boolean, onClick: () -> Unit) {
    ColituTile(active = selected, onClick = onClick, padding = PaddingValues(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(20.dp).clip(CircleShape).background(if (selected) ColituColors.violet else ColituColors.lineStrong),
                contentAlignment = Alignment.Center,
            ) { if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(ColituColors.onAccent)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                CText(title, ColituText.label)
                if (hint != null) CText(hint, ColituText.small)
            }
        }
    }
}

@Composable
private fun AppRow(app: SplitApp, checked: Boolean, onChange: (Boolean) -> Unit) {
    ColituTile(onClick = { onChange(!checked) }, padding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val icon = app.icon
            if (icon != null) {
                Image(icon, null, Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)))
            } else {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(ColituColors.lineStrong))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                CText(app.label, ColituText.label, maxLines = 1)
                CText(app.packageName, ColituText.small, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(7.dp))
                    .background(if (checked) ColituColors.violet else ColituColors.lineStrong),
                contentAlignment = Alignment.Center,
            ) { if (checked) ColituIcon(ColituIcons.Check, ColituColors.onAccent, 16.dp) }
        }
    }
}

/** One line for the account row: the mode and how many entries it has. */
fun splitSummary(settings: ColituSplitTunnel.Settings): String {
    val loc = ColituLoc
    if (!settings.active) return loc["split.offHint"]
    val key = if (settings.mode == ColituSplitTunnel.Mode.Only) "split.chipOnly" else "split.chipBypass"
    return loc.format(key, "n" to settings.count)
}
