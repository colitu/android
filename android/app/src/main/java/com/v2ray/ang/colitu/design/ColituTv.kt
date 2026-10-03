package com.v2ray.ang.colitu.design

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v2ray.ang.BuildConfig

/**
 * Android TV / Google TV support. On a television the same app switches to a
 * remote-friendly layout: a side menu instead of the floating tab bar, wide
 * two-column pages, a visible focus ring on everything the D-pad can reach,
 * and QR codes instead of web links (TVs rarely have a browser).
 */
object ColituTv {
    var isTv by mutableStateOf(false)
        private set

    fun init(context: Context) {
        isTv = BuildConfig.COLITU_FORCE_TV || detect(context)
    }

    private fun detect(context: Context): Boolean {
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
}

/**
 * White ring around the element while it has D-pad focus. Touch never moves
 * focus onto a clickable, so phones only see it with a keyboard. The ring
 * follows a pill or card outline: half the height, at most [radius].
 */
@Composable
fun Modifier.focusRing(interaction: InteractionSource, radius: Dp = 22.dp): Modifier {
    val focused by interaction.collectIsFocusedAsState()
    val alpha by animateFloatAsState(if (focused) 1f else 0f, tween(140), label = "focusRing")
    return drawWithContent {
        drawContent()
        if (alpha > 0.01f) {
            val gap = 3.dp.toPx()
            val stroke = 2.5.dp.toPx()
            val r = minOf(size.height / 2, radius.toPx()) + gap
            drawRoundRect(
                color = Color.White.copy(alpha = 0.95f * alpha),
                topLeft = Offset(-gap, -gap),
                size = Size(size.width + gap * 2, size.height + gap * 2),
                cornerRadius = CornerRadius(r, r),
                style = Stroke(stroke),
            )
        }
    }
}

/**
 * A text field keeps the D-pad's up/down for its cursor, which traps the
 * remote in it on a TV. There up and down leave the field instead.
 */
@Composable
fun Modifier.tvFieldEscape(): Modifier {
    if (!ColituTv.isTv) return this
    val focus = LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
            Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
            else -> false
        }
    }
}
