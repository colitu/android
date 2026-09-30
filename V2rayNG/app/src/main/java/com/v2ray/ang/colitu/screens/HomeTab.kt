package com.v2ray.ang.colitu.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.v2ray.ang.colitu.design.ColituTv
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.colitu.app.ColituController
import com.v2ray.ang.colitu.app.ConnectPhase
import com.v2ray.ang.colitu.app.VpnStatus
import com.v2ray.ang.colitu.app.planDetailOf
import com.v2ray.ang.colitu.app.planNameOf
import com.v2ray.ang.colitu.design.BadgeTone
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituBadge
import com.v2ray.ang.colitu.design.ColituButton
import com.v2ray.ang.colitu.design.ColituButtonKind
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituFlag
import com.v2ray.ang.colitu.design.ColituGradients
import com.v2ray.ang.colitu.design.ColituIcon
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituKicker
import com.v2ray.ang.colitu.design.ColituNotice
import com.v2ray.ang.colitu.design.ColituPanel
import com.v2ray.ang.colitu.design.ColituPowerButton
import com.v2ray.ang.colitu.design.ColituProgress
import com.v2ray.ang.colitu.design.ColituPulseDot
import com.v2ray.ang.colitu.design.ColituRadius
import com.v2ray.ang.colitu.design.ColituRoundIcon
import com.v2ray.ang.colitu.design.ColituSpinner
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.PowerState
import com.v2ray.ang.colitu.design.pressable
import com.v2ray.ang.colitu.design.reveal
import com.v2ray.ang.colitu.l10n.ColituLoc

/**
 * Connection screen: status header, the particle power button, live speed
 * tiles, the plan promo and the location card.
 */
@Composable
fun HomeTab(
    c: ColituController,
    onToggle: () -> Unit,
    onChangeLocation: () -> Unit,
    onOpenPlan: () -> Unit,
) {
    val loc = ColituLoc
    if (c.loading && c.servers.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ColituSpinner() }
        return
    }
    val on = c.connected
    val connecting = c.status == VpnStatus.Connecting
    val disconnecting = c.status == VpnStatus.Disconnecting
    val planRequired = c.planRequired && !on
    val planStatus = c.planStatus
    val planActive = c.planActive

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
    val hint = when {
        on -> loc["home.tapOff"]
        connecting -> phaseText(c.phase)
        disconnecting -> loc["status.disconnecting"]
        planRequired -> loc["home.sub.noplan"]
        else -> loc["home.tap"]
    }

    if (ColituTv.isTv) {
        TvHome(c, on, connecting, disconnecting, stateText, stateColor, if (on) loc["tv.pressOff"] else if (hint == loc["home.tap"]) loc["tv.press"] else hint, onToggle, onChangeLocation, onOpenPlan)
        return
    }

    ShellScroll {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColituRoundIcon(if (on) ColituIcons.ShieldCheck else ColituIcons.Shield, size = 44.dp, accent = on)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                CText(loc["home.statusLabel"], ColituText.small)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColituPulseDot(stateColor, pulsing = c.busy, size = 7.dp)
                    Spacer(Modifier.width(7.dp))
                    CText(stateText, ColituText.label, maxLines = 1)
                }
            }
            Spacer(Modifier.width(8.dp))
            PlanPill(active = planActive, label = if (planActive) loc["plan.status.$planStatus"] else loc["home.getPro"], onClick = onOpenPlan)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ColituPowerButton(
                state = when {
                    on -> PowerState.On
                    c.busy -> PowerState.Busy
                    else -> PowerState.Off
                },
                onClick = if (disconnecting) null else onToggle,
                size = 300.dp,
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
            )
        }
        if (on && c.transport != null) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                ColituBadge("${loc["home.protocol"]} · ${c.transportName}", BadgeTone.Neutral)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row {
            StatTile(ColituIcons.ArrowUp, loc["home.upload"], loc.speed(if (on) c.uploadBps else 0.0), on, Modifier.weight(1f).reveal(0))
            Spacer(Modifier.width(12.dp))
            StatTile(ColituIcons.ArrowDown, loc["home.download"], loc.speed(if (on) c.downloadBps else 0.0), on, Modifier.weight(1f).reveal(60))
        }
        c.error?.let {
            Spacer(Modifier.height(12.dp))
            ColituNotice(it)
        }
        if (c.offline) {
            Spacer(Modifier.height(12.dp))
            ColituNotice(loc["home.offline"], error = false)
        }
        Spacer(Modifier.height(12.dp))
        if (!planActive) {
            PromoCard(onOpenPlan, Modifier.reveal(120))
            Spacer(Modifier.height(12.dp))
        }
        LocationCard(c, onChangeLocation, Modifier.reveal(160))
        if (planActive) {
            Spacer(Modifier.height(12.dp))
            PlanCard(c, onOpenPlan, Modifier.reveal(200))
        }
    }
}

@Composable
private fun phaseText(phase: ConnectPhase): String = when (phase) {
    ConnectPhase.Preparing -> ColituLoc["home.phase.preparing"]
    ConnectPhase.Probing -> ColituLoc["home.phase.probing"]
    ConnectPhase.Starting -> ColituLoc["home.phase.starting"]
    ConnectPhase.Verifying -> ColituLoc["home.phase.verifying"]
    ConnectPhase.Switching -> ColituLoc["home.phase.switching"]
    ConnectPhase.Idle -> ColituLoc["home.sub.connecting"]
}

@Composable
private fun PlanPill(active: Boolean, label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .pressable(onClick)
            .clip(shape)
            .then(
                if (active) Modifier.background(ColituGradients.badge)
                else Modifier.background(ColituColors.surface2).border(1.dp, ColituColors.lineStrong, shape),
            )
            .padding(start = 14.dp, top = 9.dp, end = 12.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CText(
            label,
            ColituText.kicker.copy(fontSize = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold),
            color = if (active) ColituColors.onAccent else ColituColors.text,
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        ColituIcon(ColituIcons.Star, if (active) ColituColors.onAccent else ColituColors.lilac, 13.dp)
    }
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
    )
}

@Composable
private fun StatTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, active: Boolean, modifier: Modifier) {
    val parts = value.split(' ')
    // Narrow phones (360 dp wide) cannot fit "19,5 MB/s" next to a 40 dp icon,
    // so the tile tightens up instead of cutting the unit.
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 400
    ColituPanel(
        modifier,
        padding = androidx.compose.foundation.layout.PaddingValues(if (compact) 12.dp else 14.dp),
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
private fun PromoCard(onClick: () -> Unit, modifier: Modifier) {
    ColituPanel(modifier, padding = androidx.compose.foundation.layout.PaddingValues(14.dp), radius = ColituRadius.md, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColituRoundIcon(ColituIcons.Star, size = 44.dp, accent = true)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                CText(ColituLoc["home.promo.title"], ColituText.label)
                Spacer(Modifier.height(2.dp))
                CText(ColituLoc["home.promo.sub"], ColituText.small)
            }
            Spacer(Modifier.width(8.dp))
            ColituIcon(ColituIcons.ChevronRight, ColituColors.dim, 16.dp)
        }
    }
}

@Composable
private fun LocationCard(c: ColituController, onChange: () -> Unit, modifier: Modifier) {
    val loc = ColituLoc
    val server = if (c.connected) c.connectedServer ?: c.effectiveServer else c.effectiveServer
    val auto = c.autoSelection
    val title = if (auto) loc["home.fastest"] else c.titleOf(server)
    val subtitle = if (auto) {
        if (server == null) loc["home.autoPicked"] else "${loc["home.autoPicked"]} · ${c.titleOf(server)}"
    } else {
        server?.let { c.subtitleOf(it) }.orEmpty()
    }
    ColituPanel(modifier, padding = androidx.compose.foundation.layout.PaddingValues(14.dp), radius = ColituRadius.md) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (auto) ColituRoundIcon(ColituIcons.Bolt, size = 44.dp, accent = true)
                else ColituFlag(server?.flagEmoji ?: "🌐", 44.dp)
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
            Spacer(Modifier.height(12.dp))
            ColituButton(loc["home.changeServer"], onChange, height = 46.dp)
        }
    }
}

/** Plan summary: name, status badge, expiry. */
@Composable
private fun PlanCard(c: ColituController, onOpenPlan: () -> Unit, modifier: Modifier) {
    val loc = ColituLoc
    val status = c.planStatus
    val active = c.planActive
    val expires = c.expiresAt
    ColituPanel(modifier, padding = androidx.compose.foundation.layout.PaddingValues(14.dp), radius = ColituRadius.md) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColituKicker(loc["home.plan"], Modifier.weight(1f))
                ColituBadge(loc["plan.status.$status"], if (active) BadgeTone.Accent else BadgeTone.Danger)
            }
            Spacer(Modifier.height(8.dp))
            CText(planNameOf(c.user, c.subscription), ColituText.h2)
            if (active && expires != null) {
                Spacer(Modifier.height(3.dp))
                CText(planDetailOf(expires), ColituText.small)
            } else if (!active) {
                Spacer(Modifier.height(3.dp))
                CText(loc["plan.noneHint"], ColituText.small)
            }
            val limit = c.user?.trafficLimitBytes
            if (limit != null && limit > 0) {
                val used = c.user?.trafficUsedBytes ?: 0L
                Spacer(Modifier.height(12.dp))
                ColituProgress(used.toFloat() / limit)
                Spacer(Modifier.height(6.dp))
                CText(
                    "${loc["home.traffic"]}: ${loc.format("home.trafficOf", "used" to loc.bytes(used), "limit" to loc.bytes(limit))}",
                    ColituText.small,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CText(if (limit == null) loc["home.unlimited"] else "", ColituText.small, Modifier.weight(1f))
                ColituButton(
                    if (active) loc["plan.extend"] else loc["plan.choose"],
                    onOpenPlan,
                    kind = ColituButtonKind.Secondary,
                    expand = false,
                    height = 40.dp,
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
    stateText: String,
    stateColor: Color,
    hint: String,
    onToggle: () -> Unit,
    onChangeLocation: () -> Unit,
    onOpenPlan: () -> Unit,
) {
    val loc = ColituLoc
    val power = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { power.requestFocus() } }
    Row(Modifier.fillMaxSize().padding(start = 24.dp, top = 24.dp, end = 32.dp, bottom = 24.dp)) {
        Column(
            Modifier.width(330.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            PlanPill(active = c.planActive, label = if (c.planActive) loc["plan.status.${c.planStatus}"] else loc["home.getPro"], onClick = onOpenPlan)
            Spacer(Modifier.height(14.dp))
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
            if (on && c.transport != null) {
                Spacer(Modifier.height(8.dp))
                ColituBadge("${loc["home.protocol"]} · ${c.transportName}", BadgeTone.Neutral)
            }
        }
        Spacer(Modifier.width(28.dp))
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row {
                StatTile(ColituIcons.ArrowUp, loc["home.upload"], loc.speed(if (on) c.uploadBps else 0.0), on, Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                StatTile(ColituIcons.ArrowDown, loc["home.download"], loc.speed(if (on) c.downloadBps else 0.0), on, Modifier.weight(1f))
            }
            c.error?.let { ColituNotice(it) }
            if (c.offline) ColituNotice(loc["home.offline"], error = false)
            if (!c.planActive) PromoCard(onOpenPlan, Modifier)
            LocationCard(c, onChangeLocation, Modifier)
            if (c.planActive) PlanCard(c, onOpenPlan, Modifier)
        }
    }
}
