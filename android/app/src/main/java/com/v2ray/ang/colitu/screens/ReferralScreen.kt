package com.v2ray.ang.colitu.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.v2ray.ang.colitu.app.safeCall
import com.v2ray.ang.colitu.data.ColituReferral
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituBackdrop
import com.v2ray.ang.colitu.design.ColituButton
import com.v2ray.ang.colitu.design.ColituButtonKind
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituIcon
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituNotice
import com.v2ray.ang.colitu.design.ColituPanel
import com.v2ray.ang.colitu.design.ColituSpinner
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.ColituTile
import com.v2ray.ang.colitu.design.pressable
import com.v2ray.ang.colitu.l10n.ColituLoc
import com.v2ray.ang.colitu.l10n.colituErrorMessage
import com.v2ray.ang.colitu.repository.ColituReferralRepository

/** The text handed to the share sheet: the invitation, then the link on its own line. */
internal fun referralShareText(template: String, referral: ColituReferral): String {
    val withDays = template.replace("{n}", referral.inviteeDays.toString())
    return if (withDays.contains("{link}")) withDays.replace("{link}", referral.link) else withDays + "\n" + referral.link
}

/** The invitation without the link (Telegram adds the link as its own field). */
internal fun referralTextWithoutLink(template: String, referral: ColituReferral): String =
    template.replace("{n}", referral.inviteeDays.toString()).replace("{link}", "").trimEnd(' ', ':', '\uFF1A', '\n')

internal fun telegramShareUrl(template: String, referral: ColituReferral): String =
    "https://t.me/share/url?url=" + Uri.encode(referral.link) + "&text=" + Uri.encode(referralTextWithoutLink(template, referral))

internal fun whatsappShareUrl(template: String, referral: ColituReferral): String =
    "https://wa.me/?text=" + Uri.encode(referralShareText(template, referral))

/** Telegram / WhatsApp share pages; the https links open the app when installed, else the browser. */
internal fun shareViaTelegram(context: Context, referral: ColituReferral) =
    openUrl(context, telegramShareUrl(ColituLoc["referral.shareText"], referral))

internal fun shareViaWhatsApp(context: Context, referral: ColituReferral) =
    openUrl(context, whatsappShareUrl(ColituLoc["referral.shareText"], referral))

/** System share sheet with the invitation text. */
internal fun shareReferralSystem(context: Context, referral: ColituReferral) =
    shareText(context, ColituLoc["referral.title"], referralShareText(ColituLoc["referral.shareText"], referral))

/**
 * "Invite a friend": the personal link (copy / share), four statistics and the
 * rules. Loads GET /referral on open; a failure shows a retry. No invitee is
 * ever named: the panel sends counts and statuses only.
 */
@Composable
fun ReferralDialog(onClose: () -> Unit) {
    val loc = ColituLoc
    val context = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var referral by remember { mutableStateOf<ColituReferral?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        loading = true
        error = null
        safeCall { ColituReferralRepository.fetch() }
            .onSuccess { referral = it }
            .onFailure { error = colituErrorMessage(it.message) }
        loading = false
    }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(2000)
            copied = false
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { onClose() }
        ColituBackdrop(glow = false) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 28.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).pressable({ onClose() }), contentAlignment = Alignment.Center) {
                        ColituIcon(ColituIcons.ChevronLeft, ColituColors.text, 20.dp)
                    }
                    Spacer(Modifier.width(8.dp))
                    CText(loc["referral.title"], ColituText.h2, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                CText(loc.format("referral.hint", "n" to (referral?.inviteeDays ?: 7)), ColituText.small, Modifier.padding(horizontal = 4.dp))
                Spacer(Modifier.height(18.dp))

                val data = referral
                when {
                    data != null -> {
                        LinkCard(
                            data,
                            copied = copied,
                            onCopy = {
                                copyText(context, data.link)
                                copied = true
                            },
                            onShare = { shareReferralSystem(context, data) },
                            onTelegram = { shareViaTelegram(context, data) },
                            onWhatsApp = { shareViaWhatsApp(context, data) },
                        )
                        Spacer(Modifier.height(18.dp))
                        Stats(data)
                        Spacer(Modifier.height(18.dp))
                        Rules(data)
                    }
                    loading -> Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { ColituSpinner(32.dp) }
                    else -> {
                        ColituNotice(error ?: loc["err.generic"])
                        Spacer(Modifier.height(14.dp))
                        ColituButton(loc["pricing.retry"], { reload++ }, kind = ColituButtonKind.Secondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkCard(
    data: ColituReferral,
    copied: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onTelegram: () -> Unit,
    onWhatsApp: () -> Unit,
) {
    val loc = ColituLoc
    ColituPanel(padding = PaddingValues(16.dp)) {
        Column {
            CText(loc["referral.link"], ColituText.small)
            Spacer(Modifier.height(6.dp))
            CText(data.link, ColituText.label, Modifier.fillMaxWidth(), maxLines = 2)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    ColituButton(
                        if (copied) loc["referral.copied"] else loc["referral.copy"],
                        onCopy,
                        kind = ColituButtonKind.Secondary,
                        icon = if (copied) ColituIcons.Check else ColituIcons.Doc,
                    )
                }
                Box(Modifier.weight(1f)) { ColituButton(loc["referral.share"], onShare, icon = ColituIcons.Send) }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { ColituButton(loc["referral.telegram"], onTelegram, kind = ColituButtonKind.Secondary) }
                Box(Modifier.weight(1f)) { ColituButton(loc["referral.whatsapp"], onWhatsApp, kind = ColituButtonKind.Secondary) }
            }
        }
    }
}

@Composable
private fun Stats(data: ColituReferral) {
    val loc = ColituLoc
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(loc["referral.statInvited"], data.invited.toString())
            Stat(
                loc["referral.statValid"],
                data.rewarded.toString(),
                if (data.pending > 0) loc.format("referral.pendingN", "n" to data.pending) else null,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(loc["referral.statEarned"], loc.format("referral.daysN", "n" to data.daysEarned))
            Stat(loc["referral.statLeft"], "${data.left} / ${data.monthlyCap}")
        }
    }
}

@Composable
private fun RowScope.Stat(label: String, value: String, note: String? = null) {
    ColituTile(Modifier.weight(1f), padding = PaddingValues(14.dp)) {
        Column {
            CText(label, ColituText.small)
            Spacer(Modifier.height(6.dp))
            CText(value, ColituText.h1)
            if (note != null) CText(note, ColituText.small, Modifier.padding(top = 2.dp), color = ColituColors.lilac)
        }
    }
}

@Composable
private fun Rules(data: ColituReferral) {
    val loc = ColituLoc
    ColituPanel(padding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CText(loc["referral.rules"], ColituText.label)
            Rule(loc.format("referral.rule1", "n" to data.inviteeDays))
            Rule(loc.format("referral.rule2", "n" to data.rewardDays))
            Rule(loc.format("referral.rule3", "n" to data.monthlyCap))
            Rule(loc["referral.rule4"])
        }
    }
}

@Composable
private fun Rule(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 3.dp)) { ColituIcon(ColituIcons.Check, ColituColors.lilac, 16.dp) }
        Spacer(Modifier.width(10.dp))
        CText(text, ColituText.muted, Modifier.weight(1f), align = TextAlign.Start)
    }
}

private fun copyText(context: Context, text: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Colitu", text))
    }
}

private fun shareText(context: Context, title: String, text: String) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
