package com.v2ray.ang.colitu.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.v2ray.ang.colitu.app.ColituController
import com.v2ray.ang.colitu.app.ColituGeckoProtocol
import com.v2ray.ang.colitu.data.ColituTransportChoice
import com.v2ray.ang.colitu.data.XrayMobileAdapter
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituGeckoLabel
import com.v2ray.ang.colitu.design.ColituIcon
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.pressable
import com.v2ray.ang.colitu.l10n.ColituLoc

/**
 * "Protocol · Fast ▾" on the home screen (advanced mode), like the protocol
 * chip on Windows: connected, the method in use; otherwise the pick for the
 * next connect (automatic when none). A tap opens the methods; a new pick
 * rebuilds a live connection (ColituController.pickTransport). ColituGecko is
 * listed last while the panel offers it, as the animated brand word; offered
 * but unavailable now (503) it is shown disabled with a short reason.
 */
@Composable
fun ProtocolChip(c: ColituController, on: Boolean) {
    val loc = ColituLoc
    var open by rememberSaveable { mutableStateOf(false) }
    val picked = c.preferredTransport
    // A whitelist row is a way in, not a protocol: the chip shows it, locked (no menu); the pick is kept.
    val whitelist = com.v2ray.ang.colitu.data.ColituWhitelistRow.chipLabel(c.whitelistRowId != null, c.autoSelection, loc["transport.vless-reality-relay"])
    val method = when {
        whitelist != null -> whitelist
        on && c.transport != null -> c.transportName
        picked.isNotEmpty() -> XrayMobileAdapter.transportName(picked)
        else -> loc["transport.auto"]
    }
    // The offer is checked once per app run: the picker lists ColituGecko only when the panel offers it.
    LaunchedEffect(Unit) { ColituGeckoProtocol.checkOfferOnce() }
    val gecko = if (on && c.transport != null) c.transport == ColituTransportChoice.GECKO else picked == ColituTransportChoice.GECKO
    val shape = RoundedCornerShape(50)
    Box {
        Row(
            Modifier
                .clip(shape)
                .background(ColituColors.glass10)
                .pressable({ if (whitelist == null) open = !open })
                .semantics { contentDescription = loc["home.protocol.hint"] }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (gecko && whitelist == null) {
                // ColituGecko carries (or is picked for) the connection: the word shows as the animated brand gradient.
                CText("${loc["home.protocol"]} · ", ColituText.small, color = ColituColors.text, size = 12.sp, maxLines = 1)
                ColituGeckoLabel(ColituText.small.copy(fontSize = 12.sp, lineHeight = 12.sp * 1.3f))
            } else {
                CText("${loc["home.protocol"]} · $method", ColituText.small, color = ColituColors.text, size = 12.sp, maxLines = 1)
            }
            if (whitelist == null) {
                Spacer(Modifier.width(4.dp))
                ColituIcon(ColituIcons.ChevronDown, ColituColors.muted, 12.dp)
            }
        }
        if (open && whitelist == null) {
            val offsetY = with(LocalDensity.current) { 32.dp.roundToPx() }
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, offsetY),
                onDismissRequest = { open = false },
                // Focusable so a TV remote can move into the menu and Back closes it.
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier
                        .width(IntrinsicSize.Max)
                        .widthIn(min = 190.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(ColituColors.surface2)
                        .border(1.dp, ColituColors.lineStrong, RoundedCornerShape(18.dp))
                        .padding(vertical = 6.dp),
                ) {
                    ColituTransportChoice.options(ColituGeckoProtocol.listed).forEach { option ->
                        val selected = option == picked
                        val isGecko = option == ColituTransportChoice.GECKO
                        val enabled = !isGecko || ColituGeckoProtocol.pickable
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pressable({
                                    if (enabled) {
                                        open = false
                                        c.pickTransport(option)
                                    }
                                })
                                .semantics { if (isGecko) contentDescription = if (enabled) loc["transport.gecko"] else "${loc["transport.gecko"]}, ${loc["gecko.unavailable"]}" }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isGecko) {
                                Column(Modifier.weight(1f)) {
                                    ColituGeckoLabel(ColituText.label.copy(fontSize = 14.sp), showDot = selected && enabled)
                                    if (!enabled) CText(loc["gecko.unavailable"], ColituText.small, color = ColituColors.muted, size = 11.sp, maxLines = 1)
                                }
                            } else {
                                CText(
                                    if (option.isEmpty()) loc["transport.auto"] else XrayMobileAdapter.transportName(option),
                                    ColituText.label,
                                    Modifier.weight(1f),
                                    size = 14.sp,
                                    color = if (selected) ColituColors.lilac else ColituColors.text,
                                )
                            }
                            if (selected) ColituIcon(ColituIcons.Check, ColituColors.lilac, 16.dp)
                        }
                    }
                }
            }
        }
    }
}
