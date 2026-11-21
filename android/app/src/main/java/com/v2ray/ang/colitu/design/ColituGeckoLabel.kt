package com.v2ray.ang.colitu.design

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlin.math.pow

/**
 * The "ColituGecko" word (owner's wish, as on Windows): the brand gradient
 * slides over the text in a 3.5 s loop, with a small breathing dot in front
 * while Gecko carries the connection (opacity 0.45↔1, scale 0.85↔1, 2 s). Static when the
 * system turned animations off (ValueAnimator.areAnimatorsEnabled), and it
 * runs only while it is on screen (Compose stops the frame clock when the app
 * is in the background; a label scrolled out of view stops too). Only Gecko
 * is animated anywhere in the app.
 */
object ColituGeckoPalette {
    const val WORD = "ColituGecko"
    const val SLIDE_MS = 3_500
    /** Half of the 2 s breathing loop (it reverses). */
    const val BREATHE_HALF_MS = 1_000

    /** Darkest stop, light stop and dot colour (dark = on the app's dark surfaces). */
    fun colors(dark: Boolean): Triple<Color, Color, Color> = if (dark) {
        Triple(Color(0xFF8F7CF0), Color(0xFFC4B8FF), Color(0xFFB4A4FF))
    } else {
        Triple(Color(0xFF262280), Color(0xFF6650CC), Color(0xFF6650CC))
    }

    /** The repeating gradient at [slide] (0..1 of one loop) over a word [width] px wide: one loop moves it one period. */
    fun brush(dark: Boolean, slide: Float, width: Float): Brush {
        val (deep, light, _) = colors(dark)
        val period = width.coerceAtLeast(1f)
        val shift = slide.coerceIn(0f, 1f) * period
        return Brush.linearGradient(listOf(deep, light, deep), start = Offset(shift, 0f), end = Offset(shift + period, 0f), tileMode = TileMode.Repeated)
    }

    /** WCAG contrast ratio of two opaque colours. */
    fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** [top] with alpha composed over an opaque [bottom]. */
    fun over(top: Color, bottom: Color): Color = Color(
        red = top.red * top.alpha + bottom.red * (1 - top.alpha),
        green = top.green * top.alpha + bottom.green * (1 - top.alpha),
        blue = top.blue * top.alpha + bottom.blue * (1 - top.alpha),
    )

    private fun luminance(c: Color): Double {
        fun lin(v: Float): Double = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    /** False when the user (or battery saver) turned system animations off. */
    fun animationsEnabled(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ValueAnimator.areAnimatorsEnabled()
        } else {
            runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }.getOrDefault(true)
        }
}

/**
 * The animated "ColituGecko" word in [style] (the app's font). [showDot]: the
 * breathing dot in front (Gecko on). [dark]: the palette for a dark surface
 * (the app's surfaces are all dark).
 */
@Composable
fun ColituGeckoLabel(style: TextStyle, modifier: Modifier = Modifier, showDot: Boolean = false, dark: Boolean = true) {
    val context = LocalContext.current
    val animate = remember { ColituGeckoPalette.animationsEnabled(context) }
    var visible by remember { mutableStateOf(true) }
    var width by remember { mutableFloatStateOf(0f) }
    val running = animate && visible
    val slide: State<Float>
    val breathe: State<Float>
    if (running) {
        val transition = rememberInfiniteTransition(label = "gecko")
        slide = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(ColituGeckoPalette.SLIDE_MS, easing = LinearEasing), RepeatMode.Restart), label = "slide")
        breathe = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(ColituGeckoPalette.BREATHE_HALF_MS, easing = EaseInOutSine), RepeatMode.Reverse), label = "breathe")
    } else {
        slide = remember { mutableFloatStateOf(0f) }
        breathe = remember { mutableFloatStateOf(1f) }
    }
    val (_, _, dotColor) = ColituGeckoPalette.colors(dark)
    Row(
        modifier
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                visible = bounds.width > 0f && bounds.height > 0f
            }
            .clearAndSetSemantics { contentDescription = ColituGeckoPalette.WORD },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showDot) {
            Box(
                Modifier
                    .size(6.5.dp)
                    .graphicsLayer {
                        val t = breathe.value
                        alpha = 0.45f + 0.55f * t
                        scaleX = 0.85f + 0.15f * t
                        scaleY = scaleX
                    }
                    .background(dotColor, CircleShape),
            )
            Spacer(Modifier.width(6.dp))
        }
        BasicText(
            text = ColituGeckoPalette.WORD,
            style = style.copy(brush = ColituGeckoPalette.brush(dark, slide.value, width)),
            modifier = Modifier.onSizeChanged { width = it.width.toFloat() },
            maxLines = 1,
        )
    }
}
