package com.v2ray.ang.colitu.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.v2ray.ang.colitu.l10n.ColituLoc

/** QR code on a white card, drawn module by module (no bitmap). */
@Composable
fun ColituQrCode(text: String, size: Dp, modifier: Modifier = Modifier) {
    val matrix = remember(text) { encode(text) }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .padding(size * 0.06f),
    ) {
        if (matrix != null) {
            Canvas(Modifier.size(size)) {
                val cell = this.size.minDimension / matrix.width
                for (y in 0 until matrix.height) {
                    for (x in 0 until matrix.width) {
                        if (matrix[x, y]) {
                            drawRect(Color(0xFF0B0A14), Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                        }
                    }
                }
            }
        }
    }
}

private fun encode(text: String): BitMatrix? = runCatching {
    QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8"),
    )
}.getOrNull()

/**
 * A web link the TV cannot open itself. [openUrl] puts it here on a TV (or
 * when no browser is installed) and [ColituLinkQrHost] shows it as a QR code.
 */
object ColituLinks {
    var pending by mutableStateOf<String?>(null)

    fun show(url: String) {
        pending = url
    }
}

@Composable
fun ColituLinkQrHost() {
    val url = ColituLinks.pending ?: return
    ColituQrDialog(url, onDismiss = { ColituLinks.pending = null })
}

@Composable
fun ColituQrDialog(url: String, onDismiss: () -> Unit, title: String = ColituLoc["tv.qr.title"], hint: String = ColituLoc["tv.qr.sub"]) {
    val close = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ColituPanel(Modifier.padding(24.dp).widthIn(max = 640.dp), padding = androidx.compose.foundation.layout.PaddingValues(28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColituQrCode(url, 220.dp)
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    CText(title, ColituText.h1)
                    Spacer(Modifier.height(8.dp))
                    CText(hint, ColituText.muted)
                    Spacer(Modifier.height(12.dp))
                    CText(url.removePrefix("https://"), ColituText.small, color = ColituColors.lilac, maxLines = 3)
                    Spacer(Modifier.height(20.dp))
                    ColituButton(
                        ColituLoc["tv.qr.close"],
                        onDismiss,
                        Modifier.focusRequester(close),
                        kind = ColituButtonKind.Secondary,
                        height = 48.dp,
                    )
                }
            }
        }
    }
    LaunchedEffect(url) { runCatching { close.requestFocus() } }
}

/** Centred QR block used inline on TV pages (the payment screen). */
@Composable
fun ColituQrBlock(url: String, hint: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ColituQrCode(url, 200.dp)
        Spacer(Modifier.height(12.dp))
        CText(hint, ColituText.muted, Modifier.fillMaxWidth(), align = TextAlign.Center)
    }
}
