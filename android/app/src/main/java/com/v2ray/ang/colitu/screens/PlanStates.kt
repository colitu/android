package com.v2ray.ang.colitu.screens

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.v2ray.ang.colitu.api.ColituClock
import com.v2ray.ang.colitu.app.ColituController
import com.v2ray.ang.colitu.data.ColituDeviceOutlook
import com.v2ray.ang.colitu.data.ColituDevicePause
import com.v2ray.ang.colitu.data.ColituSplitTunnel
import com.v2ray.ang.colitu.design.BadgeTone
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituActionRow
import com.v2ray.ang.colitu.design.ColituBadge
import com.v2ray.ang.colitu.design.ColituButton
import com.v2ray.ang.colitu.design.ColituButtonKind
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituIcon
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituLinkButton
import com.v2ray.ang.colitu.design.ColituNotice
import com.v2ray.ang.colitu.design.ColituPanel
import com.v2ray.ang.colitu.design.ColituPill
import com.v2ray.ang.colitu.design.ColituRadius
import com.v2ray.ang.colitu.design.ColituRoundIcon
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.ColituTile
import com.v2ray.ang.colitu.design.pressable
import com.v2ray.ang.colitu.l10n.ColituLoc
import com.v2ray.ang.colitu.l10n.colituErrorMessage
import kotlinx.coroutines.launch
import java.time.Duration

const val PRICING_URL = "https://colitu.com/pricing"

/** 2FA is set up and managed on the website only. */
const val MFA_SETUP_URL = "https://colitu.com/account/security"

// ── Trial end ──────────────────────────────────────────────────────────────

/**
 * The trial-end sentence, numbers only from the panel's outlook: days left,
 * the free plan's traffic (when sent) and device limit, and whether devices
 * will be paused.
 */
fun trialEndText(outlook: ColituDeviceOutlook): String {
    val loc = ColituLoc
    val ends = outlook.endsAt ?: return ""
    val left = Duration.between(ColituClock.now(), ends)
    val leftText = if (left.toDays() >= 1) loc.count("day", (left.toHours() + 23).div(24).toInt()) else loc.count("hour", left.toHours().toInt().coerceAtLeast(1))
    val devices = outlook.nextDeviceLimit?.let { loc.count("device", it) }
    val gb = outlook.nextTrafficBytes?.takeIf { it > 0 }?.let { bytes ->
        val value = bytes / 1_000_000_000.0
        loc.format("trial.gbMonth", "gb" to if (value % 1.0 == 0.0) value.toLong().toString() else "%.1f".format(value))
    }
    val detail = listOfNotNull(gb, devices).joinToString(", ")
    val second = loc.format(if (outlook.willPauseDevices) "trial.toFreePause" else "trial.toFree", "detail" to detail)
    return "${loc.format("trial.endsIn", "left" to leftText)} $second"
}

@Composable
fun TrialEndBanner(outlook: ColituDeviceOutlook, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val loc = ColituLoc
    val context = LocalContext.current
    val shape = RoundedCornerShape(ColituRadius.md)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ColituColors.warning.copy(alpha = 0.08f))
            .border(1.dp, ColituColors.warning.copy(alpha = 0.35f), shape)
            .padding(start = 14.dp, top = 12.dp, end = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            ColituIcon(ColituIcons.Info, ColituColors.warning, 18.dp)
            Spacer(Modifier.width(10.dp))
            CText(trialEndText(outlook), ColituText.body.copy(fontSize = 14.sp), Modifier.weight(1f))
            Box(Modifier.size(32.dp).clip(CircleShape).pressable(onDismiss), contentAlignment = Alignment.Center) {
                ColituIcon(ColituIcons.XMark, ColituColors.dim, 16.dp, description = loc["trial.dismiss"])
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 24.dp), horizontalArrangement = Arrangement.Start) {
            ColituLinkButton(loc["trial.upgrade"], { openUrl(context, PRICING_URL) }, icon = ColituIcons.ArrowUpRightSquare)
        }
    }
}

// ── Paused device ──────────────────────────────────────────────────────────

/** Shown instead of the connect button while this device is over the plan's device limit. */
@Composable
fun PausedDevicePanel(c: ColituController, pause: ColituDevicePause) {
    val loc = ColituLoc
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ColituPanel(padding = PaddingValues(18.dp), radius = ColituRadius.md) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ColituRoundIcon(ColituIcons.Phone, size = 64.dp, accent = true)
            Spacer(Modifier.height(14.dp))
            ColituPill(loc["paused.kicker"], kicker = true)
            Spacer(Modifier.height(10.dp))
            CText(loc["paused.title"], ColituText.h2, Modifier.fillMaxWidth(), align = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            CText(
                pause.deviceLimit?.let { loc.format("paused.body", "devices" to loc.count("device", it)) } ?: loc["paused.bodyNoLimit"],
                ColituText.muted,
                Modifier.fillMaxWidth(),
                align = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            pause.activeDevices.forEach { device ->
                ColituTile(padding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ColituRoundIcon(ColituIcons.Phone)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            CText(device.name, ColituText.label, maxLines = 1)
                            device.lastSeenAt?.let {
                                CText(loc.format("account.lastSeen", "date" to loc.date(it)), ColituText.small, maxLines = 1)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            error?.let {
                ColituNotice(it)
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(6.dp))
            ColituButton(loc["paused.use"], {
                if (busy) return@ColituButton
                busy = true
                error = null
                scope.launch {
                    c.activateThisDevice()
                        .onSuccess { c.showToast(loc["paused.done"], error = false) }
                        .onFailure { error = colituErrorMessage(it.message) }
                    busy = false
                }
            }, loading = busy)
            Spacer(Modifier.height(6.dp))
            CText(loc["paused.useHint"], ColituText.small, Modifier.fillMaxWidth(), align = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            ColituButton(loc["paused.premium"], { openUrl(context, PRICING_URL) }, kind = ColituButtonKind.Secondary, icon = ColituIcons.ArrowUpRightSquare)
        }
    }
}

// ── Split tunneling chip ───────────────────────────────────────────────────

@Composable
fun SplitTunnelChip(settings: ColituSplitTunnel.Settings, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    val key = if (settings.mode == ColituSplitTunnel.Mode.Only) "split.chipOnly" else "split.chipBypass"
    Row(
        modifier
            .pressable(onClick)
            .clip(shape)
            .background(Color(0x26C4B5FD))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColituIcon(ColituIcons.Antenna, ColituColors.lilac, 14.dp)
        Spacer(Modifier.width(6.dp))
        CText(ColituLoc.format(key, "n" to settings.count), ColituText.small, Modifier.weight(1f, fill = false), color = ColituColors.lilac, size = 12.5.sp, weight = FontWeight.SemiBold, maxLines = 2)
        Spacer(Modifier.width(4.dp))
        ColituIcon(ColituIcons.ChevronRight, ColituColors.lilac, 12.dp)
    }
}

// ── Kill switch ────────────────────────────────────────────────────────────

/** Always-on and lockdown for this app; null below Android 10, where apps cannot ask. */
data class LockdownState(val alwaysOn: Boolean, val lockdown: Boolean)

/**
 * [VpnService.isAlwaysOn] / [VpnService.isLockdownEnabled] only look at the
 * calling app (ConnectivityManager checks the caller's uid), so a detached
 * instance in the UI process answers for Colitu.
 */
fun lockdownState(): LockdownState? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return runCatching {
        val probe = VpnService()
        LockdownState(probe.isAlwaysOn, probe.isLockdownEnabled)
    }.getOrNull()
}

fun openVpnSettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * Android has no app-level kill switch: only the system's Always-on VPN with
 * "Block connections without VPN" blocks traffic when the tunnel is down.
 * The row explains that, shows the current state and opens the settings.
 */
@Composable
fun KillSwitchRow() {
    val loc = ColituLoc
    val context = LocalContext.current
    var state by remember { mutableStateOf(lockdownState()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) state = lockdownState() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val current = state
    val hint = when {
        current == null -> loc["kill.unknown"]
        current.alwaysOn && current.lockdown -> loc["kill.on"]
        current.alwaysOn -> loc["kill.alwaysOnOnly"]
        else -> loc["kill.off"]
    }
    ColituActionRow(
        icon = ColituIcons.XOctagon,
        title = loc["kill.title"],
        hint = hint,
        onClick = { openVpnSettings(context) },
        badge = current?.let { s ->
            {
                val on = s.alwaysOn && s.lockdown
                ColituBadge(loc[if (on) "kill.badgeOn" else "kill.badgeOff"], if (on) BadgeTone.Accent else BadgeTone.Neutral)
            }
        },
        trailing = {},
        hintMaxLines = 12,
    )
}
