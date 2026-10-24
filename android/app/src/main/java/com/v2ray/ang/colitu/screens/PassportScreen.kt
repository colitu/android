package com.v2ray.ang.colitu.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.withContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.v2ray.ang.R
import com.v2ray.ang.colitu.app.ColituController
import com.v2ray.ang.colitu.data.ColituPassport
import com.v2ray.ang.colitu.data.ColituStamp
import com.v2ray.ang.colitu.data.StampLevel
import com.v2ray.ang.colitu.data.StampProgress
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituButton
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituGradients
import com.v2ray.ang.colitu.design.ColituIcon
import com.v2ray.ang.colitu.design.ColituIconButton
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituLinkButton
import com.v2ray.ang.colitu.design.ColituPanel
import com.v2ray.ang.colitu.design.ColituPerformance
import com.v2ray.ang.colitu.design.ColituRadius
import com.v2ray.ang.colitu.design.ColituSegment
import com.v2ray.ang.colitu.design.ColituSwitch
import com.v2ray.ang.colitu.design.ColituChip
import com.v2ray.ang.colitu.data.PassportGoals
import com.v2ray.ang.colitu.design.qrMatrix
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.PerformanceTier
import com.v2ray.ang.colitu.design.pressable
import com.v2ray.ang.colitu.l10n.ColituLoc
import java.io.File
import kotlin.math.sin

private enum class StampFilter { All, Unlocked, Locked }

@Suppress("DiscouragedApi")
private fun stampImage(context: Context, code: String): Int = when (code) {
    "SE" -> R.drawable.stamp_se
    "FI" -> R.drawable.stamp_fi
    "DE" -> R.drawable.stamp_de
    "NL" -> R.drawable.stamp_nl
    "GB" -> R.drawable.stamp_gb
    "PL" -> R.drawable.stamp_pl
    "LV" -> R.drawable.stamp_lv
    "FR" -> R.drawable.stamp_fr
    "US" -> R.drawable.stamp_us
    "JP" -> R.drawable.stamp_jp
    else -> context.resources.getIdentifier("stamp_${code.lowercase()}", "drawable", context.packageName)
}

/**
 * The drawn level art (stamp_se_gold …) when it ships, else 0 and the base
 * stamp is tinted in code ([materialMatrix]).
 */
private fun levelImage(context: Context, code: String, level: StampLevel?): Int {
    level ?: return 0
    @Suppress("DiscouragedApi")
    return context.resources.getIdentifier("stamp_${code.lowercase()}_${level.key}", "drawable", context.packageName)
}

/** Time spent on a country's servers: "45 dk", "14 sa". */
internal fun usedTime(minutes: Int): String =
    if (minutes < 60) ColituLoc.format("passport.mins", "n" to minutes) else ColituLoc.format("passport.hours", "n" to minutes / 60)

internal fun stampNumber(stamp: ColituStamp) = stamp.number.toString().padStart(2, '0')

internal fun levelColor(level: StampLevel): Color = when (level) {
    StampLevel.Bronze -> Color(0xFFDDA27A)
    StampLevel.Silver -> Color(0xFFD3DAE8)
    StampLevel.Gold -> Color(0xFFF2C76E)
    StampLevel.Diamond -> Color(0xFFC4B5FD)
}

internal fun levelName(level: StampLevel) = ColituLoc["passport.level.${level.key}"]

/** Effect strength of the live stamp per level; Diamond the most. */
private fun levelIntensity(level: StampLevel): Float = when (level) {
    StampLevel.Bronze -> 0.35f
    StampLevel.Silver -> 0.55f
    StampLevel.Gold -> 0.75f
    StampLevel.Diamond -> 1f
}

/** Fixed order in which the 12 puzzle pieces fill (row-major indices). */
private val PIECE_ORDER = listOf(4, 0, 11, 6, 2, 9, 7, 1, 10, 3, 8, 5)

/** Grid index (row-major) of the [filled]-th piece of a puzzle. */
internal fun pieceSlot(filled: Int): Int = PIECE_ORDER[(filled - 1).coerceIn(0, PIECE_ORDER.size - 1)]

private val IDENTITY = floatArrayOf(
    1f, 0f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f, 0f,
    0f, 0f, 1f, 0f, 0f,
    0f, 0f, 0f, 1f, 0f,
)

/** Luminance mapped onto one material tone (r, g, b gains and offsets). */
private fun tone(mr: Float, mg: Float, mb: Float, or: Float, og: Float, ob: Float) = floatArrayOf(
    .3f * mr, .59f * mr, .11f * mr, 0f, or,
    .3f * mg, .59f * mg, .11f * mg, 0f, og,
    .3f * mb, .59f * mb, .11f * mb, 0f, ob,
    0f, 0f, 0f, 1f, 0f,
)

/** Grey, darker and slightly cool: the stamp before it is unlocked. */
private val LOCKED = tone(0.5f, 0.5f, 0.59f, 0f, 0f, 6f)

/** Code tint for a level until its drawn art ships; Diamond keeps the violet original. */
private fun materialMatrix(level: StampLevel?): FloatArray = when (level) {
    StampLevel.Bronze -> tone(1.2f, 0.82f, 0.52f, 38f, 16f, 0f)
    StampLevel.Silver -> tone(1.12f, 1.16f, 1.25f, 18f, 20f, 26f)
    StampLevel.Gold -> tone(1.25f, 1.02f, 0.42f, 48f, 30f, 0f)
    StampLevel.Diamond, null -> IDENTITY
}

/** Premium cover until its own art ships: the same cover in gold. */
private val PREMIUM_COVER = tone(1.3f, 1.06f, 0.55f, 46f, 28f, 0f)

private fun lerp(from: FloatArray, to: FloatArray, t: Float) = FloatArray(20) { from[it] + (to[it] - from[it]) * t }

/** Live effects run unless the system asks for less motion or the phone saves power. */
@Composable
private fun motionAllowed(): Boolean {
    val context = LocalContext.current
    val system = remember {
        val scale = runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        scale > 0f && power?.isPowerSaveMode != true
    }
    return ColituPassport.demoMotion || (system && ColituPerformance.tier != PerformanceTier.Low)
}

/** Ignores the system animation scale (only in the test build's demo mode). */
private object FullMotion : MotionDurationScale {
    override val scaleFactor: Float = 1f
}

/** Runs [block] with the demo mode's full-speed animations when it is on. */
internal suspend fun withMotion(block: suspend () -> Unit) {
    if (ColituPassport.demoMotion) withContext(FullMotion) { block() } else block()
}

/** Phone tilt from gravity, smoothed, each axis in -1..1. */
@Composable
private fun rememberTilt(enabled: Boolean): State<Offset> {
    val context = LocalContext.current
    val tilt = remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(enabled) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = (-event.values[0] / 6f).coerceIn(-1f, 1f)
                val y = ((event.values[1] - 7f) / 4f).coerceIn(-1f, 1f)
                val old = tilt.value
                tilt.value = Offset(old.x * 0.8f + x * 0.2f, old.y * 0.8f + y * 0.2f)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (enabled && sensor != null) manager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { manager?.unregisterListener(listener) }
    }
    return tilt
}

/** Colitu Passport entry on the home page: cover, count and a chevron. */
@Composable
fun PassportCard(onOpen: () -> Unit, premium: Boolean, modifier: Modifier = Modifier) {
    val loc = ColituLoc
    ColituPanel(
        modifier,
        padding = PaddingValues(start = 12.dp, top = 10.dp, end = 14.dp, bottom = 10.dp),
        radius = ColituRadius.md,
        onClick = onOpen,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PassportCover(PassportGoals.activeCover(premium), Modifier.height(46.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                CText(loc["passport.title"], ColituText.label, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                CText(passportGoal(), ColituText.small, color = ColituColors.lilac, maxLines = 2)
            }
            Spacer(Modifier.width(10.dp))
            CText("${ColituPassport.count}/${ColituPassport.stamps.size}", ColituText.label, color = ColituColors.lilac)
            Spacer(Modifier.width(6.dp))
            ColituIcon(ColituIcons.ChevronRight, ColituColors.muted, 16.dp)
        }
    }
}

/**
 * The nearest reward, for the home card: today's next piece while one is
 * still open, else the stamp closest to its next level.
 */
private fun passportGoal(): String {
    val loc = ColituLoc
    if (ColituPassport.count == 0) return loc["passport.goal.first"]
    val (minutes, pieces) = ColituPassport.today
    if (!ColituPassport.tester && pieces < ColituPassport.PIECE_MINUTES.size) {
        val next = ColituPassport.PIECE_MINUTES[pieces]
        if (minutes < next) return loc.format("passport.goal.today", "n" to next - minutes)
    }
    val (code, best) = ColituPassport.progress.entries
        .filter { !it.value.mastered }
        .minByOrNull { it.value.toNext }
        ?.toPair() ?: return loc["passport.goal.done"]
    val level = best.level ?: return loc["passport.goal.done"]
    val target = if (level == StampLevel.Diamond) levelName(level) else levelName(StampLevel.entries[level.ordinal + 1])
    return loc.format("passport.goal.level", "country" to loc.countryName(code), "level" to target, "n" to best.toNext)
}

internal fun premiumCoverMatrix(): FloatArray = PREMIUM_COVER

/**
 * One stamp: grey and locked, or in its level's material. [puzzle] darkens
 * the pieces still missing on the way to the next level; [live] turns the
 * orbit lines and adds a tilt-driven glint (stronger per level).
 */
@Composable
internal fun StampArt(
    stamp: ColituStamp,
    progress: StampProgress,
    modifier: Modifier,
    lockSize: androidx.compose.ui.unit.Dp,
    puzzle: Boolean = false,
    live: Boolean = false,
) {
    val context = LocalContext.current
    val level = progress.level
    val art = remember(stamp.code, level) { levelImage(context, stamp.code, level) }
    val baseArt = remember(stamp.code) { stampImage(context, stamp.code) }
    // Animates when a stamp unlocks while the page is open.
    val open by animateFloatAsState(if (level != null) 1f else 0f, tween(900), label = "stamp")
    val target = if (art != 0) IDENTITY else materialMatrix(level)
    val matrix = lerp(LOCKED, target, open)
    val pieces = progress.levelPieces
    val showPuzzle = puzzle && level != null && !progress.mastered
    val animate = live && level != null && motionAllowed()
    val tilt = rememberTilt(animate)
    val orbit = remember { Animatable(0f) }
    LaunchedEffect(animate, level) {
        if (!animate) return@LaunchedEffect
        withMotion {
            while (true) {
                orbit.snapTo(0f)
                orbit.animateTo(360f, tween(if (level == StampLevel.Diamond) 9000 else 14000, easing = LinearEasing))
            }
        }
    }
    val angle: State<Float>? = if (animate) orbit.asState() else null
    val intensity = level?.let { levelIntensity(it) } ?: 0f

    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        if (art == 0 && baseArt == 0) {
            PlaceholderStamp(stamp, level, Modifier.fillMaxSize().alpha(0.5f + 0.5f * open))
        } else Image(
            painterResource(if (art != 0) art else baseArt),
            ColituLoc.countryName(stamp.code),
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (showPuzzle) drawPuzzle(pieces)
                    if (angle != null) drawLive(angle.value, tilt.value, intensity, level == StampLevel.Diamond)
                },
            colorFilter = if (matrix.contentEquals(IDENTITY)) null else ColorFilter.colorMatrix(ColorMatrix(matrix)),
        )
        if (open < 1f) {
            Box(
                Modifier
                    .size(lockSize)
                    .alpha(1f - open)
                    .clip(CircleShape)
                    .background(Color(0xCC14141B))
                    .border(1.dp, ColituColors.lineStrong, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                ColituIcon(ColituIcons.Lock, ColituColors.text, lockSize * 0.46f)
            }
        }
    }
}

/** 3 × 4 jigsaw over the stamp; missing pieces are shaded. Drawn only on the stamp's pixels. */
private fun DrawScope.drawPuzzle(filled: Int) {
    val inset = size.minDimension * 0.06f
    val w = size.width - inset * 2
    val h = size.height - inset * 2
    val cw = w / 3
    val ch = h / 4
    val done = PIECE_ORDER.take(filled).toSet()
    for (i in 0 until StampProgress.PIECES) {
        if (i in done) continue
        drawRect(Color(0xC705040F),Offset(inset + (i % 3) * cw, inset + (i / 3) * ch), Size(cw, ch), blendMode = BlendMode.SrcAtop)
    }
    drawPath(jigsaw(inset, w, h), Color(0x66FFFFFF), style = Stroke(1.3.dp.toPx()), blendMode = BlendMode.SrcAtop)
}

private fun jigsaw(o: Float, w: Float, h: Float): Path {
    val p = Path()
    val cw = w / 3
    val ch = h / 4
    val k = minOf(cw, ch) * 0.15f
    for (i in 1 until 3) {
        val x = o + cw * i
        for (j in 0 until 4) {
            val y0 = o + ch * j
            val ym = y0 + ch / 2
            val d = if ((i + j) % 2 == 0) 1f else -1f
            p.moveTo(x, y0)
            p.lineTo(x, ym - k)
            p.cubicTo(x + d * k * 2.4f, ym - k * 1.9f, x + d * k * 2.4f, ym + k * 1.9f, x, ym + k)
            p.lineTo(x, y0 + ch)
        }
    }
    for (j in 1 until 4) {
        val y = o + ch * j
        for (i in 0 until 3) {
            val x0 = o + cw * i
            val xm = x0 + cw / 2
            val d = if ((i + j) % 2 == 0) -1f else 1f
            p.moveTo(x0, y)
            p.lineTo(xm - k, y)
            p.cubicTo(xm - k * 1.9f, y + d * k * 2.4f, xm + k * 1.9f, y + d * k * 2.4f, xm + k, y)
            p.lineTo(x0 + cw, y)
        }
    }
    return p
}

/** Turning orbit lines, a glint that follows the tilt and, on Diamond, twinkles. */
private fun DrawScope.drawLive(angle: Float, tilt: Offset, intensity: Float, diamond: Boolean) {
    val stroke = Stroke(1.4.dp.toPx())
    rotate(angle, center) {
        drawOval(
            Color.White.copy(alpha = 0.26f * intensity),
            topLeft = Offset(size.width * 0.06f, size.height * 0.33f),
            size = Size(size.width * 0.88f, size.height * 0.34f),
            style = stroke,
            blendMode = BlendMode.SrcAtop,
        )
    }
    rotate(-angle * 0.6f + 60f, center) {
        drawOval(
            Color(0xFFC4B5FD).copy(alpha = 0.22f * intensity),
            topLeft = Offset(size.width * 0.16f, size.height * 0.38f),
            size = Size(size.width * 0.68f, size.height * 0.24f),
            style = stroke,
            blendMode = BlendMode.SrcAtop,
        )
    }
    val cx = size.width * (0.5f + tilt.x * 0.7f)
    val cy = size.height * (0.5f + tilt.y * 0.7f)
    val r = size.minDimension * 0.5f
    drawRect(
        Brush.linearGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = 0.34f * intensity), Color.Transparent),
            start = Offset(cx - r, cy - r),
            end = Offset(cx + r, cy + r),
        ),
        blendMode = BlendMode.SrcAtop,
    )
    if (diamond) {
        val spots = listOf(0.22f to 0.28f, 0.74f to 0.2f, 0.82f to 0.62f, 0.3f to 0.74f, 0.55f to 0.45f, 0.62f to 0.84f)
        val rad = Math.toRadians(angle.toDouble()).toFloat()
        spots.forEachIndexed { i, (x, y) ->
            val a = ((sin(rad * 4 + i * 1.7f) + 1f) / 2f).let { it * it }
            val c = Offset(size.width * x, size.height * y)
            val len = 6.dp.toPx() * a
            drawLine(Color.White.copy(alpha = a), Offset(c.x - len, c.y), Offset(c.x + len, c.y), 1.2.dp.toPx(), blendMode = BlendMode.SrcAtop)
            drawLine(Color.White.copy(alpha = a), Offset(c.x, c.y - len), Offset(c.x, c.y + len), 1.2.dp.toPx(), blendMode = BlendMode.SrcAtop)
        }
    }
}

@Composable
private fun LevelChip(level: StampLevel) {
    val color = levelColor(level)
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.55f), shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        CText(levelName(level).uppercase(), ColituText.kicker.copy(fontSize = 10.5.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold), color = color)
    }
}

/** Bottom sheet for one stamp: its live puzzle, level and count; share it or connect to its country. */
@Composable
internal fun StampSheet(
    c: ColituController,
    stamp: ColituStamp,
    onDismiss: () -> Unit,
    onConnectTo: (com.v2ray.ang.colitu.data.ColituServer) -> Unit,
) {
    val loc = ColituLoc
    val context = LocalContext.current
    val country = loc.countryName(stamp.code)
    val progress = ColituPassport.progressOf(stamp.code)
    val level = progress.level
    val inCountry = c.rankedServers.filter { !it.isMultihop && ColituPassport.stampFor(it.countryCode)?.code == stamp.code }
    val server = inCountry.firstOrNull { it.isAvailable }
    val shape = RoundedCornerShape(topStart = ColituRadius.lg, topEnd = ColituRadius.lg)
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(visible, enter = slideInVertically(tween(320)) { it / 2 } + fadeIn(tween(220))) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .border(1.dp, ColituColors.lineStrong, shape)
                        .background(ColituColors.surface)
                        .clickable(remember { MutableInteractionSource() }, indication = null) {},
                ) {
                    Image(painterResource(R.drawable.passport_sheet_bg), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                    Column(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 22.dp, top = 10.dp, end = 22.dp, bottom = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(50)).background(ColituColors.lineStrong))
                        Spacer(Modifier.height(14.dp))
                        StampArt(
                            stamp,
                            progress,
                            // Test build: a long press earns one piece (with its animation).
                            Modifier.width(240.dp).pointerInput(stamp.code) { detectTapGestures(onLongPress = { ColituPassport.debugPiece(stamp.code) }) },
                            lockSize = 48.dp,
                            puzzle = true,
                            live = true,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CText(country, ColituText.h1, size = 28.sp)
                            if (level != null) {
                                Spacer(Modifier.width(10.dp))
                                LevelChip(level)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        if (level != null) {
                            val meta = listOfNotNull(progress.city, usedTime(progress.minutes).takeIf { progress.minutes > 0 }).joinToString(" · ")
                            CText(meta.ifEmpty { loc.format("passport.stampNo", "n" to stampNumber(stamp)) }, ColituText.small, align = TextAlign.Center)
                            Spacer(Modifier.height(16.dp))
                            if (!progress.mastered) {
                                SegmentBar(progress.levelPieces, levelColor(level), Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                                Spacer(Modifier.height(10.dp))
                            }
                            val goal = when {
                                progress.mastered -> loc["passport.masteredShort"]
                                level == StampLevel.Diamond -> loc.format("passport.toMaster", "n" to progress.toNext)
                                else -> loc.format("passport.toNext", "level" to levelName(StampLevel.entries[level.ordinal + 1]), "n" to progress.toNext)
                            }
                            CText(goal, ColituText.label, color = levelColor(level), align = TextAlign.Center)
                        } else {
                            val hint = when {
                                server != null -> loc["passport.lockedHint"]
                                inCountry.isEmpty() -> loc.format("passport.planDesc", "country" to country)
                                else -> loc.format("passport.offlineDesc", "country" to country)
                            }
                            CText(hint, ColituText.muted, align = TextAlign.Center)
                        }
                        Spacer(Modifier.height(22.dp))
                        when {
                            level != null -> ColituButton(loc["passport.shareStamp"], { sharePassport(context, stamp, c.planActive) })
                            server != null -> ColituButton(loc.format("passport.connectTo", "country" to country), { onConnectTo(server) })
                        }
                        Spacer(Modifier.height(6.dp))
                        ColituLinkButton(loc["passport.close"], onDismiss)
                    }
                }
            }
        }
    }
}

/**
 * Shares a story-sized card (1080×1920): one stamp in its material with
 * "GOLD UNLOCKED", or the whole passport when [stamp] is null. Written to
 * cache/passport for the FileProvider.
 */
internal fun sharePassport(context: Context, stamp: ColituStamp?, premium: Boolean) {
    val loc = ColituLoc
    val w = 1080
    val h = 1920
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val res = context.resources
    val filter = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    BitmapFactory.decodeResource(res, R.drawable.passport_share_bg)?.let { canvas.drawBitmap(it, null, Rect(0, 0, w, h), filter) }

    val bold = runCatching { ResourcesCompat.getFont(context, R.font.colitu_sans_bold) }.getOrNull()
    val medium = runCatching { ResourcesCompat.getFont(context, R.font.colitu_sans_medium) }.getOrNull()
    fun argb(c: Color) = android.graphics.Color.argb((c.alpha * 255).toInt(), (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
    fun text(value: String, y: Float, size: Float, color: Int, face: android.graphics.Typeface?, spacing: Float = 0f) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            textAlign = Paint.Align.CENTER
            typeface = face
            letterSpacing = spacing
        }
        // Long titles shrink to fit the card.
        while (paint.measureText(value) > w - 120 && paint.textSize > 40f) paint.textSize -= 4f
        canvas.drawText(value, w / 2f, y, paint)
    }

    val total = ColituPassport.stamps.size
    text("COLITU PASSPORT", 170f, 40f, 0xFFC4B5FD.toInt(), medium, 0.35f)
    var message = loc.format("passport.shareText", "n" to ColituPassport.count, "total" to total)
    if (stamp != null) {
        val progress = ColituPassport.progressOf(stamp.code)
        val level = progress.level
        val art = levelImage(context, stamp.code, level)
        val paint = Paint(filter)
        if (art == 0 && level != null && level != StampLevel.Diamond) paint.colorFilter = ColorMatrixColorFilter(materialMatrix(level))
        (if (art != 0) art else stampImage(context, stamp.code)).takeIf { it != 0 }?.let { BitmapFactory.decodeResource(res, it) }?.let { canvas.drawBitmap(it, null, RectF(170f, 250f, 910f, 990f), paint) }
        val headline = level?.let { loc.format("passport.r.levelUnlocked", "level" to levelName(it)) }
            ?: loc.format("passport.r.unlocked", "country" to loc.countryName(stamp.code))
        text(headline.uppercase(), 1120f, 96f, 0xFFF4F3FA.toInt(), bold, 0.02f)
        text(
            "${loc.countryName(stamp.code)} · ${loc.format("passport.stampNo", "n" to stampNumber(stamp))}",
            1200f,
            46f,
            argb(level?.let { levelColor(it) } ?: ColituColors.lilac),
            medium,
        )
        message = if (level != null) {
            loc.format("passport.shareLevelText", "country" to loc.countryName(stamp.code), "level" to levelName(level))
        } else {
            loc.format("passport.shareStampText", "country" to loc.countryName(stamp.code))
        }
    } else {
        BitmapFactory.decodeResource(res, R.drawable.passport_cover)?.let {
            val height = 820f
            val width = height * it.width / it.height
            val paint = Paint(filter)
            coverMatrix(PassportGoals.activeCover(premium))?.let { paint.colorFilter = ColorMatrixColorFilter(it) }
            canvas.drawBitmap(it, null, RectF((w - width) / 2f, 240f, (w + width) / 2f, 240f + height), paint)
        }
        text(loc["passport.complete"].uppercase(), 1200f, 88f, 0xFFF4F3FA.toInt(), bold)
    }
    // Collection box: "9 / 10 · country stamps collected".
    val box = RectF(120f, 1320f, 960f, 1560f)
    canvas.drawRoundRect(box, 48f, 48f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x5A3A2F8F })
    canvas.drawRoundRect(
        box,
        48f,
        48f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x669F8CFF
            style = Paint.Style.STROKE
            strokeWidth = 3f
        },
    )
    text("${ColituPassport.count} / $total", 1440f, 92f, 0xFFF4F3FA.toInt(), bold)
    text(loc["passport.s.collected"].uppercase(), 1510f, 34f, 0xFFC4B5FD.toInt(), medium, 0.12f)
    qrMatrix(myDuelLink())?.let { m ->
        val side = 170f
        val cell = side / m.width
        val left = (w - side) / 2f
        val top = 1590f
        canvas.drawRoundRect(RectF(left - 14f, top - 14f, left + side + 14f, top + side + 14f), 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        val dot = Paint().apply { color = 0xFF16153A.toInt() }
        for (x in 0 until m.width) for (y in 0 until m.height) if (m[x, y]) canvas.drawRect(left + x * cell, top + y * cell, left + (x + 1) * cell, top + (y + 1) * cell, dot)
    }
    text(identityLine(), 1820f, 34f, 0xFFC4B5FD.toInt(), medium)
    text(loc["passport.s.cta"], 1872f, 34f, 0xFFA9ADC2.toInt(), medium)
    com.v2ray.ang.colitu.data.PassportWeekly.bump(com.v2ray.ang.colitu.data.ExpeditionGoal.Share)

    runCatching {
        val dir = File(context.cacheDir, "passport").apply { mkdirs() }
        val file = File(dir, "colitu-passport.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, message)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
