package com.v2ray.ang.colitu.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituButton
import com.v2ray.ang.colitu.design.ColituButtonKind
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituIcon
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituLinkButton
import com.v2ray.ang.colitu.design.ColituRadius
import com.v2ray.ang.colitu.design.ColituRoundIcon
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.colituGlow
import com.v2ray.ang.colitu.design.pressable
import com.v2ray.ang.colitu.l10n.ColituLoc

/** Help page on which sites and addresses go outside the VPN, in the app language. */
fun splitTunnelingUrl(): String = "https://docs.colitu.com/${ColituLoc.language}/split-tunneling"

/**
 * Small line under the connection status while Russian sites leave outside
 * the VPN; opens the privacy mode setting.
 */
@Composable
fun RuDirectChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .pressable(onClick)
            .clip(shape)
            .background(Color(0x26F5C36B))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColituIcon(ColituIcons.Info, ColituColors.warning, 14.dp)
        Spacer(Modifier.width(6.dp))
        CText(ColituLoc["privacy.chip"], ColituText.small, color = ColituColors.warning, size = 12.5.sp, weight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        ColituIcon(ColituIcons.ChevronRight, ColituColors.warning, 12.dp)
    }
}

/**
 * One-time notice after the first connection with the Russian direct rule
 * active. Closing it any way counts as "Keep".
 */
@Composable
fun RuDirectNoticeDialog(onKeep: () -> Unit, onEnablePrivacy: () -> Unit, onDetails: () -> Unit) {
    val loc = ColituLoc
    Dialog(onDismissRequest = onKeep) {
        val shape = RoundedCornerShape(ColituRadius.md)
        Column(
            Modifier
                .fillMaxWidth()
                .colituGlow(Color(0xB3000000), 20.dp, shape, 10.dp)
                .clip(shape)
                .background(Color(0xFF1B1B24))
                .border(1.dp, ColituColors.lineStrong, shape)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 22.dp, end = 20.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ColituRoundIcon(ColituIcons.Globe, size = 56.dp, accent = true)
            Spacer(Modifier.height(14.dp))
            CText(loc["privacy.notice.title"], ColituText.h2, align = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            CText(loc["privacy.notice.body"], ColituText.muted, Modifier.fillMaxWidth(), align = TextAlign.Start)
            Spacer(Modifier.height(4.dp))
            ColituLinkButton(loc["privacy.details"], onDetails, Modifier.align(Alignment.Start), icon = ColituIcons.ArrowUpRightSquare)
            Spacer(Modifier.height(14.dp))
            ColituButton(loc["privacy.notice.keep"], onKeep)
            Spacer(Modifier.height(8.dp))
            ColituButton(loc["privacy.notice.enable"], onEnablePrivacy, kind = ColituButtonKind.Secondary)
        }
    }
}
