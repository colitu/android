package com.v2ray.ang.colitu.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.colitu.api.ColituClock
import com.v2ray.ang.colitu.app.ColituController
import com.v2ray.ang.colitu.app.ConnectPhase
import com.v2ray.ang.colitu.app.VpnStatus
import com.v2ray.ang.colitu.app.isFreePlan
import com.v2ray.ang.colitu.app.planLeftOf
import com.v2ray.ang.colitu.app.planNameOf
import com.v2ray.ang.colitu.data.ColituMultihop
import com.v2ray.ang.colitu.data.ColituRotation
import com.v2ray.ang.colitu.data.ColituRotationStatus
import com.v2ray.ang.colitu.design.BadgeTone
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituBadge
import com.v2ray.ang.colitu.design.ColituButton
import com.v2ray.ang.colitu.design.ColituButtonKind
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituFlag
import com.v2ray.ang.colitu.design.ColituIcon
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituLinkButton
import com.v2ray.ang.colitu.design.ColituNotice
import com.v2ray.ang.colitu.design.ColituPanel
import com.v2ray.ang.colitu.design.ColituPowerButton
import com.v2ray.ang.colitu.design.ColituProgress
import com.v2ray.ang.colitu.design.ColituPulseDot
import com.v2ray.ang.colitu.design.ColituRadius
import com.v2ray.ang.colitu.design.ColituRoundIcon
import com.v2ray.ang.colitu.design.ColituSpinner
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.ColituTv
import com.v2ray.ang.colitu.design.PowerState
import com.v2ray.ang.colitu.design.reveal
import com.v2ray.ang.colitu.l10n.ColituLoc
import kotlinx.coroutines.delay
import java.time.Duration

/**
 * Connection screen, kept to one screen above the tab bar like on iOS: the
 * particle power button (its state says connected or not), live speed
 * tiles, the location card and a one-row plan card.
 */
@Composable
fun HomeTab(
    c: ColituController,
    onToggle: () -> Unit,
    onChangeLocation: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenPrivacy: () -> Unit = {},
    onOpenSplit: () -> Unit = {},
    onOpenAdvanced: () -> Unit = {},
) {
    val loc = ColituLoc
    val advanced = c.advancedMode
    if (c.loading && c.servers.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ColituSpinner() }
        return
    }
    val on = c.connected
    val connecting = c.status == VpnStatus.Connecting
    val disconnecting = c.status == VpnStatus.Disconnecting
    val planRequired = c.planRequired && !on

    val hint = when {
        on -> loc["home.tapOff"]
        connecting -> phaseText(c.phase)
        disconnecting -> loc["status.disconnecting"]
        planRequired -> loc["home.sub.noplan"]
        else -> loc["home.tap"]
    }

    c.devicePause?.let { pause ->
        // Paused over the device limit: nothing to connect until this device
        // is made the active one (or the plan allows more).
        ShellScroll {
            Spacer(Modifier.height(8.dp))
            PausedDevicePanel(c, pause)
            Spacer(Modifier.height(12.dp))
            PlanRow(c, onOpenPlan, Modifier)
        }
        return
    }

    if (ColituTv.isTv) {
        TvHome(c, on, connecting, disconnecting, if (on) loc["tv.pressOff"] else if (hint == loc["home.tap"]) loc["tv.press"] else hint, onToggle, onChangeLocation, onOpenPlan, onOpenPrivacy, onOpenAdvanced)
        return
    }

    // Small phones get a smaller button so the whole page still fits.
    val powerSize = if (LocalConfiguration.current.screenHeightDp < 700) 240.dp else 280.dp

    ShellScroll {
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ColituPowerButton(
                state = when {
                    on -> PowerState.On
                    c.busy -> PowerState.Busy
                    else -> PowerState.Off
                },
                onClick = if (disconnecting) null else onToggle,
                size = powerSize,
                description = if (on) loc["home.disconnect"] else loc["home.connect"],
                content = if (on) ({ SessionClock(c.connectedSeconds) }) else null,
            )
        }
        AnimatedContent(
            targetState = hint,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "hint",
            modifier = Modifier.fillMaxWidth(),
        ) { text ->
            CText(
                text,
                ColituText.muted,
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                color = if (connecting) ColituColors.lilac else ColituColors.muted,
                align = TextAlign.Center,
                maxLines = 2,
            )
        }
        if (advanced && on && c.transport != null) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                ColituBadge("${loc["home.protocol"]} · ${c.transportName}", BadgeTone.Neutral)
            }
        }
        if (advanced && c.ruDirectActive) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                RuDirectChip(onOpenPrivacy)
            }
        }
        if (!advanced && c.advancedSettingsOn) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                ColituLinkButton(loc["mode.advancedSettingsOn"], onOpenAdvanced, icon = ColituIcons.Info)
            }
        }
        if (advanced && c.splitTunnel.active) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                SplitTunnelChip(c.splitTunnel, onOpenSplit)
            }
        }
        c.trialEndBanner?.let { outlook ->
            Spacer(Modifier.height(12.dp))
            TrialEndBanner(outlook, onDismiss = { c.dismissTrialBanner() })
        }
        ColituNoticeSlot()
        if (advanced) {
            Spacer(Modifier.height(16.dp))
            Row {
                StatTile(ColituIcons.ArrowUp, loc["home.upload"], loc.speed(if (on) c.uploadBps else 0.0), on, Modifier.weight(1f).reveal(0))
                Spacer(Modifier.width(12.dp))
                StatTile(ColituIcons.ArrowDown, loc["home.download"], loc.speed(if (on) c.downloadBps else 0.0), on, Modifier.weight(1f).reveal(60))
            }
        } else {
            Spacer(Modifier.height(4.dp))
        }
        c.error?.let {
            Spacer(Modifier.height(12.dp))
            ColituNotice(it)
            if (c.offerFastest) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    ColituLinkButton(loc["home.tryFastest"], { c.tryFastest() }, icon = ColituIcons.Bolt)
                }
            }
        }
        if (c.offline) {
            Spacer(Modifier.height(12.dp))
            ColituNotice(loc["home.offline"], error = false)
        }
        Spacer(Modifier.height(12.dp))
        LocationCard(c, onChangeLocation, Modifier.reveal(120))
        Spacer(Modifier.height(12.dp))
        PlanRow(c, onOpenPlan, Modifier.reveal(160))
        if (!advanced) {
            Spacer(Modifier.height(10.dp))
            AdvancedModeButton(c, Modifier.fillMaxWidth())
        }
    }
}

/** Simple mode's one tap to Advanced mode: on right away, the new items appear here. */
@Composable
private fun AdvancedModeButton(c: ColituController, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.Center) {
        ColituLinkButton(ColituLoc["mode.advanced"], { c.setAdvancedMode(true, fromHome = true) }, icon = ColituIcons.Grid)
    }
}

@Composable
private fun phaseText(phase: ConnectPhase): String = when (phase) {
    ConnectPhase.Preparing -> ColituLoc["home.phase.preparing"]
    ConnectPhase.Probing -> ColituLoc["home.phase.probing"]
    ConnectPhase.Starting -> ColituLoc["home.phase.starting"]
    ConnectPhase.Verifying -> ColituLoc["home.phase.verifying"]
    ConnectPhase.Switching -> ColituLoc["home.phase.switching"]
    ConnectPhase.SwitchingServer -> ColituLoc["home.phase.switchingServer"]
    ConnectPhase.Reconnecting -> ColituLoc["home.phase.reconnecting"]
    ConnectPhase.Idle -> ColituLoc["home.sub.connecting"]
}

/** Session clock inside the power button. */
@Composable
private fun SessionClock(seconds: Int) {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    CText(
        "%02d:%02d:%02d".format(h, m, s),
        ColituText.h2.copy(fontSize = 24.sp, fontFeatureSettings = "tnum"),
        color = Color.White,
        maxLines = 1,
    )
}

@Composable
private fun StatTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, active: Boolean, modifier: Modifier) {
    val parts = value.split(' ')
    // Narrow phones (360 dp wide) cannot fit "19,5 MB/s" next to a 40 dp icon,
    // so the tile tightens up instead of cutting the unit.
    val compact = LocalConfiguration.current.screenWidthDp < 400
    ColituPanel(
        modifier,
        padding = PaddingValues(if (compact) 12.dp else 14.dp),
        radius = ColituRadius.md,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColituRoundIcon(icon, size = if (compact) 32.dp else 40.dp)
            Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
            Column {
                CText(label, ColituText.small, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    CText(
                        parts.first(),
                        ColituText.h2.copy(fontSize = if (compact) 18.sp else 20.sp, fontFeatureSettings = "tnum"),
                        color = if (active) ColituColors.text else ColituColors.muted,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(3.dp))
                    CText(
                        if (parts.size > 1) parts.last() else "",
                        ColituText.small.copy(fontSize = if (compact) 10.5.sp else 11.5.sp),
                        Modifier.padding(bottom = 2.dp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun LocationCard(c: ColituController, onChange: () -> Unit, modifier: Modifier) {
    val loc = ColituLoc
    val server = if (c.connected) c.connectedServer ?: c.effectiveServer else c.effectiveServer
    val auto = c.connectsAutomatically
    val title = if (auto) loc["home.fastest"] else c.titleOf(server)
    val subtitle = if (auto) {
        if (server == null) loc["home.autoPicked"] else "${loc["home.autoPicked"]} · ${c.titleOf(server)}"
    } else {
        server?.let { c.subtitleOf(it) }.orEmpty()
    }
    ColituPanel(modifier, padding = PaddingValues(14.dp), radius = ColituRadius.md) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (auto) ColituRoundIcon(ColituIcons.Bolt, size = 44.dp, accent = true)
                else if (server?.route != null) ColituFlag(server.route.exit.country, 44.dp)
                else ColituFlag(server?.countryCode, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    CText(title, ColituText.label, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    CText(subtitle, ColituText.small, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                ColituIcon(
                    if (c.connected) ColituIcons.CheckCircle else ColituIcons.Info,
                    if (c.connected) ColituColors.success else ColituColors.dim,
                    18.dp,
                )
            }
            ConnectionPathLines(c)
            Spacer(Modifier.height(12.dp))
            ColituButton(loc["home.changeServer"], onChange, height = 46.dp)
        }
    }
}

/**
 * What the running tunnel does beyond "connected": over a multihop route the
 * entry and exit countries; on a rotating node the current exit with the
 * countdown to the next change (or the note that the transport cannot rotate).
 */
@Composable
private fun ConnectionPathLines(c: ColituController) {
    val loc = ColituLoc
    if (!c.connected) return
    val route = c.connectedServer?.route
    if (route != null) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColituFlag(route.entry.country, 22.dp)
            Spacer(Modifier.width(4.dp))
            ColituIcon(ColituIcons.ArrowRight, ColituColors.muted, 12.dp)
            Spacer(Modifier.width(4.dp))
            ColituFlag(route.exit.country, 22.dp)
            Spacer(Modifier.width(10.dp))
            CText(
                loc.format("multihop.home", "entry" to (route.entry.country ?: route.entry.label), "exit" to (route.exit.country ?: route.exit.label)),
                ColituText.small,
                Modifier.weight(1f),
                color = ColituColors.text,
                maxLines = 1,
            )
        }
        return
    }
    if (!c.rotationActive) return
    val status = c.rotationStatus
    if (c.transport != null && !ColituMultihop.isVless(c.transport)) {
        Spacer(Modifier.height(10.dp))
        CText(loc["rotation.needsVless"], ColituText.small, maxLines = 2)
    } else if (status != null && status.active) {
        RotationLine(status)
    }
}

/** Current exit and a ticking countdown to the panel's next change. */
@Composable
private fun RotationLine(status: ColituRotationStatus) {
    val loc = ColituLoc
    val exit = status.currentExit ?: return
    val next = status.nextChangeAt ?: return
    var now by remember { mutableStateOf(ColituClock.now()) }
    LaunchedEffect(next) {
        while (true) {
            delay(1_000)
            now = ColituClock.now()
        }
    }
    val left = Duration.between(now, next)
    val name = if (exit.country != null && exit.label != exit.country) "${exit.label} (${exit.country})" else exit.label
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        ColituFlag(exit.country, 22.dp)
        Spacer(Modifier.width(10.dp))
        CText(
            if (left.isNegative || left.isZero) loc.format("rotation.homeDue", "exit" to name)
            else loc.format("rotation.home", "exit" to name, "time" to ColituRotation.formatCountdown(left)),
            ColituText.small,
            Modifier.weight(1f),
            color = ColituColors.text,
            maxLines = 2,
        )
    }
}

/**
 * One-row plan summary: name, expiry (or "no expiry") and traffic, with a
 * button to the plan tab. A status badge appears only when something is
 * wrong (expired or no plan); an active plan needs no label.
 */
@Composable
private fun PlanRow(c: ColituController, onOpenPlan: () -> Unit, modifier: Modifier) {
    val loc = ColituLoc
    val status = c.planStatus
    val active = c.planActive
    val expires = c.expiresAt
    val limit = c.user?.trafficLimitBytes?.takeIf { it > 0 }
    val free = isFreePlan(c.user)
    val detail = if (!active && !free) loc["plan.noneHint"] else if (free) loc["plan.freeHint"] else listOfNotNull(
        expires?.let { planLeftOf(it) },
        if (limit == null) loc["home.unlimited"] else null,
    ).joinToString(" · ")
    ColituPanel(modifier, padding = PaddingValues(start = 14.dp, top = 12.dp, end = 12.dp, bottom = 12.dp), radius = ColituRadius.md, onClick = onOpenPlan) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColituRoundIcon(ColituIcons.Star, size = 40.dp, accent = active)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CText(planNameOf(c.user, c.subscription), ColituText.label, Modifier.weight(1f, fill = false), maxLines = 1)
                        if (!active) {
                            Spacer(Modifier.width(8.dp))
                            ColituBadge(loc["plan.status.$status"], BadgeTone.Danger)
                        }
                    }
                    if (detail.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        CText(detail, ColituText.small, maxLines = 1)
                    }
                }
                Spacer(Modifier.width(8.dp))
                ColituButton(loc["plan.manage"], onOpenPlan, kind = ColituButtonKind.Secondary, expand = false, height = 36.dp)
            }
            if (limit != null) {
                val used = c.user?.trafficUsedBytes ?: 0L
                Spacer(Modifier.height(10.dp))
                ColituProgress((used.toFloat() / limit).coerceIn(0f, 1f))
                Spacer(Modifier.height(5.dp))
                CText(
                    "${loc["home.traffic"]}: ${loc.format("home.trafficOf", "used" to loc.bytes(used), "limit" to loc.bytes(limit))}",
                    ColituText.small,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * TV home: the power button on the left, where focus starts, so connecting
 * is one press of OK; status, speed, location and plan on the right.
 */
@Composable
private fun TvHome(
    c: ColituController,
    on: Boolean,
    connecting: Boolean,
    disconnecting: Boolean,
    hint: String,
    onToggle: () -> Unit,
    onChangeLocation: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenAdvanced: () -> Unit,
) {
    val loc = ColituLoc
    val power = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { power.requestFocus() } }
    val stateText = when {
        on -> loc["home.state.on"]
        connecting -> loc["home.state.connecting"]
        disconnecting -> loc["home.state.disconnecting"]
        else -> loc["home.state.off"]
    }
    val stateColor = when {
        on -> ColituColors.success
        c.busy -> ColituColors.lilac
        else -> ColituColors.danger
    }
    Row(Modifier.fillMaxSize().padding(start = 24.dp, top = 24.dp, end = 32.dp, bottom = 24.dp)) {
        Column(
            Modifier.width(330.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColituPulseDot(stateColor, pulsing = c.busy, size = 8.dp)
                Spacer(Modifier.width(8.dp))
                CText(stateText, ColituText.h2, maxLines = 1)
            }
            ColituPowerButton(
                state = when {
                    on -> PowerState.On
                    c.busy -> PowerState.Busy
                    else -> PowerState.Off
                },
                onClick = if (disconnecting) null else onToggle,
                size = 300.dp,
                description = if (on) loc["home.disconnect"] else loc["home.connect"],
                modifier = Modifier.focusRequester(power),
                content = if (on) ({ SessionClock(c.connectedSeconds) }) else null,
            )
            CText(
                hint,
                ColituText.muted,
                Modifier.fillMaxWidth(),
                color = if (connecting) ColituColors.lilac else ColituColors.muted,
                align = TextAlign.Center,
                maxLines = 2,
            )
            if (c.advancedMode && on && c.transport != null) {
                Spacer(Modifier.height(8.dp))
                ColituBadge("${loc["home.protocol"]} · ${c.transportName}", BadgeTone.Neutral)
            }
            if (c.advancedMode && c.ruDirectActive) {
                Spacer(Modifier.height(8.dp))
                RuDirectChip(onOpenPrivacy)
            }
            if (!c.advancedMode && c.advancedSettingsOn) {
                Spacer(Modifier.height(8.dp))
                ColituLinkButton(loc["mode.advancedSettingsOn"], onOpenAdvanced, icon = ColituIcons.Info)
            }
        }
        Spacer(Modifier.width(28.dp))
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (c.advancedMode) {
                Row {
                    StatTile(ColituIcons.ArrowUp, loc["home.upload"], loc.speed(if (on) c.uploadBps else 0.0), on, Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    StatTile(ColituIcons.ArrowDown, loc["home.download"], loc.speed(if (on) c.downloadBps else 0.0), on, Modifier.weight(1f))
                }
            }
            c.error?.let { ColituNotice(it) }
            if (c.error != null && c.offerFastest) ColituLinkButton(loc["home.tryFastest"], { c.tryFastest() }, icon = ColituIcons.Bolt)
            if (c.offline) ColituNotice(loc["home.offline"], error = false)
            LocationCard(c, onChangeLocation, Modifier)
            PlanRow(c, onOpenPlan, Modifier)
            if (!c.advancedMode) AdvancedModeButton(c, Modifier.fillMaxWidth())
        }
    }
}
