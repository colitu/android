package com.v2ray.ang.colitu.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.colitu.app.ColituNoticeCenter
import com.v2ray.ang.colitu.data.ColituNotice
import com.v2ray.ang.colitu.data.ColituNoticeLevel
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituIcon
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituLinkButton
import com.v2ray.ang.colitu.design.ColituRadius
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.pressable
import com.v2ray.ang.colitu.l10n.ColituLoc

/** Tint of a notice level: critical red, warning amber, promo accent, info neutral. */
private fun levelColor(level: ColituNoticeLevel): Color = when (level) {
    ColituNoticeLevel.Critical -> ColituColors.danger
    ColituNoticeLevel.Warning -> ColituColors.warning
    ColituNoticeLevel.Promo -> ColituColors.accent
    ColituNoticeLevel.Info -> ColituColors.muted
}

/**
 * The panel's current announcement at the top of the home screen; nothing
 * when there is none. Reports "seen" once per notice (the centre remembers).
 */
@Composable
fun ColituNoticeSlot() {
    val notice = ColituNoticeCenter.current ?: return
    val context = LocalContext.current
    LaunchedEffect(notice.id) { ColituNoticeCenter.markSeen(notice) }
    Spacer(Modifier.height(12.dp))
    ColituNoticeBanner(
        notice,
        onButton = {
            notice.url?.let { url ->
                ColituNoticeCenter.markClicked(notice)
                openUrl(context, url)
            }
        },
        onClose = { ColituNoticeCenter.dismiss(notice) },
    )
}

@Composable
fun ColituNoticeBanner(
    notice: ColituNotice,
    onButton: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loc = ColituLoc
    val tint = levelColor(notice.level)
    val shape = RoundedCornerShape(ColituRadius.md)
    val neutral = notice.level == ColituNoticeLevel.Info
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (neutral) ColituColors.glass06 else tint.copy(alpha = 0.1f))
            .border(1.dp, if (neutral) ColituColors.lineStrong else tint.copy(alpha = 0.4f), shape)
            .padding(start = 14.dp, top = 12.dp, end = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            ColituIcon(
                if (notice.level == ColituNoticeLevel.Critical || notice.level == ColituNoticeLevel.Warning) ColituIcons.Warning else ColituIcons.Info,
                tint,
                18.dp,
                Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                CText(notice.title, ColituText.body.copy(fontSize = 14.5.sp), weight = FontWeight.Bold)
                if (notice.body.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    CText(notice.body, ColituText.muted.copy(fontSize = 13.5.sp))
                }
            }
            Box(Modifier.size(32.dp).clip(CircleShape).pressable(onClose), contentAlignment = Alignment.Center) {
                ColituIcon(ColituIcons.XMark, ColituColors.dim, 16.dp, description = loc["notice.close"])
            }
        }
        if (notice.url != null) {
            Row(Modifier.fillMaxWidth().padding(start = 28.dp), horizontalArrangement = Arrangement.Start) {
                ColituLinkButton(
                    notice.button ?: loc["notice.open"],
                    onButton,
                    icon = ColituIcons.ArrowUpRightSquare,
                    color = if (neutral) ColituColors.lilac else tint,
                )
            }
        } else {
            Spacer(Modifier.height(4.dp))
        }
    }
}
