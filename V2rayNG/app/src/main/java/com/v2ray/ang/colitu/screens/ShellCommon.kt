package com.v2ray.ang.colitu.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituDivider
import com.v2ray.ang.colitu.design.ColituLinks
import com.v2ray.ang.colitu.design.ColituTv
import com.v2ray.ang.colitu.design.ColituRadius
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.colituGlow
import com.v2ray.ang.colitu.design.pressable

const val WEB_BASE_URL = "https://colitu.com"
const val SUPPORT_EMAIL = "support@colitu.com"
const val COMPANY_NAME = "Avenlith"

/**
 * Shared scroll container for the tabs: page padding plus room for the
 * floating nav bar.
 */
@Composable
fun ShellScroll(tvMaxWidth: androidx.compose.ui.unit.Dp = 680.dp, content: @Composable ColumnScope.() -> Unit) {
    if (ColituTv.isTv) {
        // TV pages sit next to the side menu; no status bar or floating tab bar.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = tvMaxWidth)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, top = 28.dp, end = 24.dp, bottom = 32.dp),
                content = content,
            )
        }
        return
    }
    val bottomInset = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 116.dp + bottomInset),
        content = content,
    )
}

fun openWeb(context: Context, path: String) = openUrl(context, "$WEB_BASE_URL$path")

/**
 * Opens a web page. A TV rarely has a browser and nobody wants to type on it,
 * so there the link is shown as a QR code to open on the phone; the same QR
 * code is the fallback when a phone has no browser.
 */
fun openUrl(context: Context, url: String) {
    if (ColituTv.isTv) {
        ColituLinks.show(url)
        return
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        ColituLinks.show(url)
    }
}

/** Opens the mail app with the support address; false when there is none. */
fun openSupportMail(context: Context, body: String): Boolean {
    val uri = Uri.parse(
        "mailto:$SUPPORT_EMAIL?subject=" + Uri.encode("Colitu Support Request") + "&body=" + Uri.encode(body),
    )
    return try {
        context.startActivity(Intent(Intent.ACTION_SENDTO, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** Two-button confirmation in the style of the iOS alert. */
@Composable
fun ColituConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    cancel: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(ColituRadius.md)
        Column(
            Modifier
                .fillMaxWidth()
                .colituGlow(Color(0xB3000000), 20.dp, shape, 10.dp)
                .clip(shape)
                .background(Color(0xFF1B1B24))
                .border(1.dp, ColituColors.lineStrong, shape),
        ) {
            Column(Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CText(title, ColituText.h2, align = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                CText(message, ColituText.muted, align = TextAlign.Center)
            }
            ColituDivider()
            Row(Modifier.fillMaxWidth().height(50.dp)) {
                DialogButton(cancel, ColituColors.lilac, onDismiss, Modifier.weight(1f))
                Box(Modifier.width(1.dp).fillMaxSize().background(ColituColors.line))
                DialogButton(confirm, if (destructive) ColituColors.danger else ColituColors.lilac, onConfirm, Modifier.weight(1f))
            }
        }
    }
}

/** Section title above a group of rows. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    CText(text, ColituText.label, modifier.padding(start = 4.dp, bottom = 8.dp), color = ColituColors.muted)
}

/** Alert button; the D-pad focus shows as a lit cell, a ring would be cut off. */
@Composable
private fun DialogButton(label: String, color: Color, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxSize()
            .onFocusChanged { focused = it.isFocused }
            .pressable(onClick, focusRing = false)
            .background(if (focused) ColituColors.glass10 else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        CText(label, ColituText.label, color = color)
    }
}
