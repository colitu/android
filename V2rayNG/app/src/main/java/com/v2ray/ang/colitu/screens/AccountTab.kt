package com.v2ray.ang.colitu.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.colitu.api.ColituTokenManager
import com.v2ray.ang.colitu.app.ColituController
import com.v2ray.ang.colitu.app.isFreePlan
import com.v2ray.ang.colitu.app.planNameOf
import com.v2ray.ang.colitu.app.safeCall
import com.v2ray.ang.colitu.data.ColituDevice
import com.v2ray.ang.colitu.design.BadgeTone
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituActionRow
import com.v2ray.ang.colitu.design.ColituBadge
import com.v2ray.ang.colitu.design.ColituButton
import com.v2ray.ang.colitu.design.ColituButtonKind
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituGradients
import com.v2ray.ang.colitu.design.ColituIcon
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituLinkButton
import com.v2ray.ang.colitu.design.ColituNotice
import com.v2ray.ang.colitu.design.ColituPanel
import com.v2ray.ang.colitu.design.ColituRadius
import com.v2ray.ang.colitu.design.ColituRoundIcon
import com.v2ray.ang.colitu.data.ColituAdBlock
import com.v2ray.ang.colitu.design.ColituSegment
import com.v2ray.ang.colitu.design.ColituTv
import com.v2ray.ang.colitu.design.ColituSpinner
import com.v2ray.ang.colitu.design.ColituSwitchRow
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.ColituTile
import com.v2ray.ang.colitu.design.pressable
import com.v2ray.ang.colitu.l10n.ColituLoc
import com.v2ray.ang.colitu.l10n.colituErrorMessage
import com.v2ray.ang.colitu.repository.ColituAccountRepository
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Account and settings in one place: profile, features (always-on,
 * auto-connect, DNS), connection (protocol, language), devices, general.
 */
@Composable
fun AccountTab(
    c: ColituController,
    onOpenPlan: () -> Unit,
    onOpenSupport: () -> Unit,
    onSignOut: () -> Unit,
    onHowItWorks: () -> Unit,
) {
    val loc = ColituLoc
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<ColituDevice>>(emptyList()) }
    var loadingDevices by remember { mutableStateOf(true) }
    var devicesError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var removing by remember { mutableStateOf<ColituDevice?>(null) }
    var linking by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        loadingDevices = true
        safeCall { ColituAccountRepository.fetchDevices() }
            .onSuccess { list ->
                devices = list.sortedBy { if (it.current) 0 else 1 }
                devicesError = null
            }
            .onFailure { devicesError = colituErrorMessage(it.message) }
        loadingDevices = false
    }

    val user = c.user
    val email = user?.email.orEmpty()
    val status = c.planStatus
    val active = c.planActive
    val limit = (user?.deviceLimit ?: 1).coerceAtLeast(1)

    ShellScroll {
        CText(loc["account.title"], ColituText.h1)
        Spacer(Modifier.height(14.dp))
        // Profile
        ColituPanel(padding = PaddingValues(14.dp), radius = ColituRadius.md) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(ColituGradients.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        CText(
                            if (email.isEmpty()) "•" else email.take(1).uppercase(),
                            ColituText.h2.copy(fontSize = 20.sp),
                            color = ColituColors.onAccent,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        CText(email.ifEmpty { "—" }, ColituText.label, maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                        val expires = c.expiresAt
                        CText(
                            if (isFreePlan(user)) "${planNameOf(user, c.subscription)} · ${loc["plan.freeHint"]}"
                            else if (active && expires != null) "${planNameOf(user, c.subscription)} · ${loc.date(expires)}"
                            else planNameOf(user, c.subscription),
                            ColituText.small,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    ColituBadge(loc["plan.status.$status"], if (active) BadgeTone.Accent else BadgeTone.Danger)
                }
                Spacer(Modifier.height(12.dp))
                ColituButton(if (isFreePlan(user)) loc["plan.upgrade"] else if (active) loc["plan.extend"] else loc["plan.choose"], onOpenPlan, height = 44.dp)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ColituLinkButton(loc["account.manage"], { openUrl(context, ACCOUNT_URL) }, icon = ColituIcons.ArrowUpRightSquare)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // Sign a TV in by scanning the QR code on its sign-in screen.
        if (!ColituTv.isTv) {
            ColituActionRow(
                icon = ColituIcons.Tv,
                title = loc["link.row"],
                hint = loc["link.rowHint"],
                onClick = { linking = true },
                trailing = { ColituIcon(ColituIcons.QrScan, ColituColors.lilac, 22.dp) },
            )
        }
        Spacer(Modifier.height(18.dp))

        // Features
        SectionTitle(loc["account.features"])
        ColituActionRow(
            icon = ColituIcons.ShieldHalf,
            title = loc["settings.alwaysOn"],
            hint = loc["settings.alwaysOnHint"],
            onClick = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            trailing = { ColituIcon(ColituIcons.ArrowUpRightSquare, ColituColors.dim, 18.dp) },
        )
        Spacer(Modifier.height(8.dp))
        ColituSwitchRow(
            icon = ColituIcons.Bolt,
            title = loc["settings.autoConnect"],
            hint = loc["settings.autoConnectHint"],
            value = c.autoConnect,
            onChange = { c.setAutoConnectEnabled(it) },
        )
        if (ColituAdBlock.available) {
            Spacer(Modifier.height(8.dp))
            ColituSwitchRow(
                icon = ColituIcons.EyeSlash,
                title = loc["settings.adBlock"],
                hint = loc["settings.adBlockHint"],
                value = c.adBlock,
                onChange = { c.setAdBlockEnabled(it) },
            )
        }
        Spacer(Modifier.height(8.dp))
        ColituSwitchRow(
            icon = ColituIcons.LockShield,
            title = loc["settings.dns"],
            hint = loc["settings.dnsHint"],
            value = true,
            onChange = null,
            trailing = { ColituIcon(ColituIcons.SealCheck, ColituColors.success, 22.dp) },
        )
        Spacer(Modifier.height(18.dp))

        // Connection
        SectionTitle(loc["account.connection"])
        ColituActionRow(
            icon = ColituIcons.Antenna,
            title = loc["account.protocol"],
            hint = if (c.connected && c.transport != null) "${loc["account.protocolAuto"]} · ${c.transportName}" else loc["account.protocolAuto"],
            trailing = {},
        )
        Spacer(Modifier.height(8.dp))
        ColituTile(padding = PaddingValues(start = 12.dp, top = 11.dp, end = 12.dp, bottom = 12.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColituRoundIcon(ColituIcons.Globe)
                    Spacer(Modifier.width(12.dp))
                    CText(loc["settings.language"], ColituText.label)
                }
                Spacer(Modifier.height(10.dp))
                ColituSegment(
                    values = ColituLoc.languages,
                    selected = loc.language,
                    onChange = { ColituLoc.setLanguage(it) },
                    label = {
                        when (it) {
                            "ru" -> "Русский"
                            "tr" -> "Türkçe"
                            else -> "English"
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(18.dp))

        // Devices
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(loc["account.devices"], Modifier.weight(1f))
            if (!loadingDevices) {
                CText(
                    loc.format("account.devicesTitle", "used" to devices.size, "limit" to maxOf(limit, devices.size)),
                    ColituText.small,
                    Modifier.padding(bottom = 8.dp),
                )
            }
        }
        when {
            loadingDevices -> Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) { ColituSpinner() }
            devicesError != null -> ColituNotice(devicesError!!) { ColituLinkButton(loc["pricing.retry"], { reload++ }) }
            else -> devices.forEach { device ->
                DeviceRow(device) { removing = device }
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(10.dp))

        // General
        SectionTitle(loc["account.general"])
        // The tour and the mail app are phone things; a TV has no use or app for them.
        if (!ColituTv.isTv) {
            ColituActionRow(ColituIcons.PlayCircle, loc["account.howItWorks"], onClick = onHowItWorks)
            Spacer(Modifier.height(8.dp))
        }
        ColituActionRow(ColituIcons.Chat, loc["support.title"], loc["account.helpHint"], onClick = onOpenSupport)
        Spacer(Modifier.height(8.dp))
        ColituActionRow(ColituIcons.Question, loc["support.help"], onClick = { openUrl(context, "https://docs.colitu.com/${loc.language}") })
        Spacer(Modifier.height(8.dp))
        if (!ColituTv.isTv) ColituActionRow(ColituIcons.Mail, loc["account.mail"], onClick = {
            val body = "\n\n—\nColitu Android ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                "Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                "Device ${ColituTokenManager.getDeviceId().orEmpty()}"
            if (!openSupportMail(context, body)) c.showToast(loc["account.mailUnavailable"], error = true)
        })
        Spacer(Modifier.height(8.dp))
        ColituActionRow(ColituIcons.Doc, loc["settings.terms"], onClick = { openWeb(context, "/legal/terms") })
        Spacer(Modifier.height(8.dp))
        ColituActionRow(ColituIcons.LockShield, loc["settings.privacy"], onClick = { openWeb(context, "/legal/privacy") })
        Spacer(Modifier.height(8.dp))
        ColituActionRow(
            ColituIcons.Info,
            "Colitu VPN",
            loc.format("settings.version", "version" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") + " · " + loc["update.check"],
            onClick = {
                scope.launch {
                    val update = com.v2ray.ang.colitu.update.ColituUpdater.check(context.applicationContext)
                    if (update == null) c.showToast(loc["update.latest"], error = false)
                    else com.v2ray.ang.colitu.update.ColituUpdater.offered = update
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        // The app is GPL-3.0; on a TV openUrl shows the link as a QR code.
        ColituActionRow(ColituIcons.Code, loc["about.openSource"], loc["about.openSourceHint"], onClick = { openUrl(context, SOURCE_CODE_URL) })
        Spacer(Modifier.height(18.dp))
        ColituButton(loc["account.signOut"], onSignOut, kind = ColituButtonKind.Danger, icon = ColituIcons.SignOut)
        Spacer(Modifier.height(14.dp))
        CText(loc.format("brand.credit", "brand" to COMPANY_NAME), ColituText.small, Modifier.fillMaxWidth(), align = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        CText(loc["about.credits"], ColituText.small, Modifier.fillMaxWidth(), color = ColituColors.dim, align = androidx.compose.ui.text.style.TextAlign.Center)
    }

    if (linking) {
        LinkScannerDialog(
            onDismiss = { linking = false },
            onApproved = {
                linking = false
                c.showToast(loc["link.approved"], error = false)
                scope.launch {
                    kotlinx.coroutines.delay(6_000)
                    reload++
                }
            },
        )
    }

    removing?.let { device ->
        ColituConfirmDialog(
            title = loc["account.remove"],
            message = loc.format("account.removeConfirm", "name" to device.name),
            confirm = loc["account.remove"],
            cancel = loc["pay.cancel"],
            destructive = true,
            onConfirm = {
                removing = null
                scope.launch {
                    safeCall { ColituAccountRepository.disconnectDevice(device.id) }
                        .onSuccess {
                            if (device.current) {
                                // This phone is no longer on the account: end the session
                                // right away instead of asking "sign out?" first.
                                c.onCurrentDeviceRemoved()
                            } else {
                                c.showToast(loc["account.removed"], error = false)
                                reload++
                            }
                        }
                        .onFailure { c.showToast(colituErrorMessage(it.message), error = true) }
                }
            },
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun DeviceRow(device: ColituDevice, onRemove: () -> Unit) {
    val loc = ColituLoc
    val icon = when (device.platform?.lowercase()) {
        "windows", "linux" -> ColituIcons.Desktop
        "macos" -> ColituIcons.Laptop
        else -> ColituIcons.Phone
    }
    ColituTile(padding = PaddingValues(start = 12.dp, top = 10.dp, end = 8.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColituRoundIcon(icon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                // The name gets the full width; the badge sits on the second line.
                CText(device.name, ColituText.label, maxLines = 1)
                val lastSeen = device.lastActiveAt?.let { raw -> runCatching { Instant.parse(raw) }.getOrNull() }
                if (device.current || lastSeen != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (device.current) {
                            ColituBadge(loc["account.thisDevice"])
                            Spacer(Modifier.width(8.dp))
                        }
                        lastSeen?.let {
                            CText(loc.format("account.lastSeen", "date" to loc.date(it)), ColituText.small, maxLines = 1)
                        }
                    }
                }
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).pressable(onRemove),
                contentAlignment = Alignment.Center,
            ) { ColituIcon(ColituIcons.XCircleOutline, ColituColors.dim, 20.dp, description = loc["account.remove"]) }
        }
    }
}
