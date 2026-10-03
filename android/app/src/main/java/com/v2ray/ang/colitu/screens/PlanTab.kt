package com.v2ray.ang.colitu.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.colitu.app.ColituController
import com.v2ray.ang.colitu.app.isFreePlan
import com.v2ray.ang.colitu.app.planDetailOf
import com.v2ray.ang.colitu.app.planNameOf
import com.v2ray.ang.colitu.design.BadgeTone
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituBadge
import com.v2ray.ang.colitu.design.ColituButton
import com.v2ray.ang.colitu.design.ColituButtonKind
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituKicker
import com.v2ray.ang.colitu.design.ColituPanel
import com.v2ray.ang.colitu.design.ColituParticles
import com.v2ray.ang.colitu.design.ColituRadius
import com.v2ray.ang.colitu.design.ColituRoundIcon
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.ParticleMode
import com.v2ray.ang.colitu.l10n.ColituLoc
import kotlinx.coroutines.launch

/**
 * Plan page, as on iOS. Nothing is bought in the app: the subscription is
 * managed in the customer account on app.colitu.com. The page shows the
 * current plan, says where it is managed, opens the account (a QR code on a
 * TV) and refreshes the status after the user changed something there.
 */
@Composable
fun PlanTab(c: ColituController) {
    val loc = ColituLoc
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                c.load(showLoading = false)
                c.showToast(loc["plan.refreshed"], error = false)
            } finally {
                refreshing = false
            }
        }
    }

    val status = c.planStatus
    val active = c.planActive
    val expires = c.expiresAt

    ShellScroll {
        Hero()
        Spacer(Modifier.height(16.dp))
        ColituPanel(padding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 16.dp), radius = ColituRadius.md) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    ColituKicker(loc["pricing.current"])
                    Spacer(Modifier.height(6.dp))
                    CText(planNameOf(c.user, c.subscription), ColituText.h2, maxLines = 2)
                    Spacer(Modifier.height(3.dp))
                    CText(
                        if (isFreePlan(c.user)) loc["plan.freeHint"] else if (active && expires != null) planDetailOf(expires) else loc["plan.noneHint"],
                        ColituText.small,
                        maxLines = 2,
                    )
                }
                if (!active) {
                    Spacer(Modifier.width(8.dp))
                    ColituBadge(loc["plan.status.$status"], BadgeTone.Danger)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        ColituPanel(padding = PaddingValues(16.dp), radius = ColituRadius.md) {
            Column {
                Row(verticalAlignment = Alignment.Top) {
                    ColituRoundIcon(ColituIcons.Globe, size = 40.dp, accent = true)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        CText(loc["plan.manageTitle"], ColituText.label)
                        Spacer(Modifier.height(4.dp))
                        CText(loc["plan.manageBody"], ColituText.small)
                    }
                }
                Spacer(Modifier.height(14.dp))
                ColituButton(
                    loc["plan.manageButton"],
                    { openUrl(context, ACCOUNT_URL) },
                    icon = ColituIcons.ArrowUpRightSquare,
                )
                Spacer(Modifier.height(10.dp))
                ColituButton(
                    loc["plan.refresh"],
                    { refresh() },
                    kind = ColituButtonKind.Secondary,
                    loading = refreshing,
                )
                Spacer(Modifier.height(10.dp))
                CText(loc["plan.refreshHint"], ColituText.small, Modifier.fillMaxWidth(), align = TextAlign.Center)
            }
        }
    }
}

/** Turning gem with the headline. */
@Composable
private fun Hero() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ColituParticles(mode = ParticleMode.Gem, size = 120.dp, energy = 0.6f)
        CText(
            ColituLoc["plan.heroTitle"],
            ColituText.display.copy(fontSize = 28.sp, lineHeight = 32.sp),
            Modifier.padding(horizontal = 8.dp),
            align = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        CText(ColituLoc["plan.heroSub"], ColituText.muted, Modifier.padding(horizontal = 12.dp), align = TextAlign.Center)
    }
}
