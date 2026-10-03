package com.v2ray.ang.colitu.design

import android.graphics.Paint
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.delay

/** Shapes the particle field can take. */
enum class ParticleMode {
    /** A wavy ring of dots that ripples in 3D; sits behind the power button. */
    Ring,

    /** A slowly turning globe of dots; hero of the onboarding and sign-in. */
    Sphere,

    /** A rotating wireframe gem with sparks; hero of the plan page. */
    Gem,
}

private class Particle(
    val a: Float,
    val r: Float,
    val phase: Float,
    val size: Float,
    val x: Float,
    val y: Float,
    val z: Float,
)

private class ParticleSet(val mode: ParticleMode, sizeDp: Float) {
    val particles = ArrayList<Particle>()

    init {
        val random = Random(7)
        when (mode) {
            ParticleMode.Ring -> {
                val n = if (sizeDp < 220) 700 else 1250
                repeat(n) {
                    val a = random.nextFloat() * PI.toFloat() * 2
                    val r = 0.6f + 0.4f * sqrt(random.nextFloat())
                    particles += Particle(a, r, random.nextFloat() * 6.283f, 0.9f + random.nextFloat() * 1.5f, 0f, 0f, 0f)
                }
            }
            ParticleMode.Sphere -> {
                val n = if (sizeDp < 220) 520 else 900
                val golden = (PI * (3 - sqrt(5.0))).toFloat()
                for (i in 0 until n) {
                    val y = 1 - (i / (n - 1f)) * 2
                    val radius = sqrt(1 - y * y)
                    val theta = golden * i
                    particles += Particle(
                        0f, 0f, random.nextFloat() * 6.283f, 0.8f + random.nextFloat() * 1.3f,
                        cos(theta) * radius, y, sin(theta) * radius,
                    )
                }
            }
            ParticleMode.Gem -> repeat(70) {
                val a = random.nextFloat() * PI.toFloat() * 2
                val r = 0.75f + 0.55f * random.nextFloat()
                particles += Particle(a, r, random.nextFloat() * 6.283f, 0.8f + random.nextFloat() * 1.6f, 0f, 0f, 0f)
            }
        }
    }

    /** Point buckets reused every frame (x, y pairs). */
    val buckets = Array(9) { FloatArray(particles.size * 2) }
    val counts = IntArray(9)
}

private const val FOCAL = 2.7f

/**
 * Live 3D particle field drawn on a canvas: a few hundred projected points
 * redrawn every frame, no assets or shaders. [energy] (0..1) drives speed and
 * brightness so the same field can idle, work and celebrate.
 */
@Composable
fun ColituParticles(
    mode: ParticleMode = ParticleMode.Ring,
    size: Dp = 280.dp,
    energy: Float = 0.35f,
    color: Color = ColituColors.violet,
    color2: Color = ColituColors.lilac,
    animate: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val set = remember(mode) { ParticleSet(mode, size.value) }
    val time = remember { mutableFloatStateOf(0f) }
    val eased = remember { mutableFloatStateOf(energy) }
    val target by rememberUpdatedState(energy)
    val paint = remember { Paint().apply { isAntiAlias = true; strokeCap = Paint.Cap.ROUND } }

    val tier = ColituPerformance.tier
    LaunchedEffect(animate, tier) {
        if (!animate) return@LaunchedEffect
        var last = 0L
        var previousFrame = 0L
        var slowFrames = 0
        var measuredFrames = 0
        while (true) {
            withFrameNanos { now ->
                // Watch the real frame pace; a phone that cannot keep up steps
                // down a tier instead of stuttering (and hogging the CPU).
                if (previousFrame != 0L && tier != PerformanceTier.Low) {
                    measuredFrames++
                    if (now - previousFrame > 34_000_000L) slowFrames++
                    if (measuredFrames >= 90) {
                        if (slowFrames > 30) ColituPerformance.reportSlowFrames()
                        measuredFrames = 0
                        slowFrames = 0
                    }
                }
                previousFrame = now
                // Low tier draws at most ~20 fps; the clock still uses real time.
                if (last != 0L && now - last < tier.frameIntervalNanos) return@withFrameNanos
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                last = now
                // Energy eases toward the target so state changes ramp instead of snap.
                eased.floatValue += (target - eased.floatValue) * min(1f, dt * 3.2f)
                time.floatValue += dt * (0.55f + eased.floatValue * 1.35f)
            }
            // Throttled tiers sleep between frames instead of waking at every
            // vsync only to skip the frame.
            if (tier.frameIntervalNanos > 0) delay(tier.frameIntervalNanos / 1_000_000L - 4)
        }
    }

    // The soft glow behind the field only follows the connection state, so it
    // lives in its own layer that is not repainted with every particle frame.
    Box(
        modifier
            .size(size)
            .drawBehind { drawFieldGlow(set.mode, energy, color) },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val e = if (animate) eased.floatValue else energy
            val t = time.floatValue
            // The particles are in random order, so a prefix is an even sample.
            val count = (set.particles.size * tier.particleShare).toInt().coerceAtLeast(24)
            when (set.mode) {
                ParticleMode.Ring -> paintRing(set, count, t, e, color, color2, paint)
                ParticleMode.Sphere -> paintSphere(set, count, t, e, color, color2, paint)
                ParticleMode.Gem -> paintGem(set, count, t, e, color, color2, paint)
            }
        }
    }
}

private fun DrawScope.drawFieldGlow(mode: ParticleMode, energy: Float, color: Color) {
    val half = size.width / 2
    when (mode) {
        ParticleMode.Ring -> glow(color, center, half * 0.95f, 0.10f + 0.30f * energy)
        ParticleMode.Sphere -> glow(color, center, half, 0.12f + 0.2f * energy)
        ParticleMode.Gem -> glow(color, center, half, 0.16f + 0.22f * energy)
    }
}

private fun DrawScope.glow(color: Color, center: Offset, radius: Float, alpha: Float) {
    if (alpha <= 0.01f) return
    drawCircle(
        Brush.radialGradient(
            0f to color.copy(alpha = alpha),
            0.45f to color.copy(alpha = alpha * 0.35f),
            1f to color.copy(alpha = 0f),
            center = center,
            radius = radius,
        ),
        radius,
        center,
    )
}

/** Draws each bucket with one drawPoints call, far cheaper than a circle per dot. */
private fun DrawScope.drawBuckets(
    set: ParticleSet,
    bucketCount: Int,
    widths: FloatArray,
    alphas: FloatArray,
    color: Color,
    color2: Color,
    paint: Paint,
) {
    drawIntoCanvas { canvas ->
        for (i in 0 until bucketCount) {
            val count = set.counts[i]
            if (count == 0) continue
            val tint = lerp(color, color2, i / (bucketCount - 1f).coerceAtLeast(1f))
            paint.color = tint.copy(alpha = alphas[i].coerceIn(0f, 1f)).toArgb()
            paint.strokeWidth = widths[i].dp.toPx()
            canvas.nativeCanvas.drawPoints(set.buckets[i], 0, count * 2, paint)
        }
    }
}

private fun DrawScope.paintRing(set: ParticleSet, count: Int, time: Float, energy: Float, color: Color, color2: Color, paint: Paint) {
    val center = this.center
    val radius = size.width / 2

    val tilt = 0.82f
    val cosT = cos(tilt)
    val sinT = sin(tilt)
    val spin = time * 0.22f
    val amp = 0.16f + 0.26f * energy
    set.counts.fill(0)
    for (i in 0 until minOf(count, set.particles.size)) {
        val p = set.particles[i]
        val a = p.a + spin
        val wave = sin(p.a * 3 + time * 1.5f + p.phase) * 0.5f +
            sin(p.a * 6 - time * 0.9f) * 0.28f +
            sin(p.r * 9 + time * 1.15f + p.phase) * 0.22f
        val r = p.r * (1 + 0.09f * wave * (0.5f + energy))
        val x = r * cos(a)
        val y = r * sin(a)
        val z = amp * wave
        val y2 = y * cosT - z * sinT
        val z2 = y * sinT + z * cosT
        val scale = FOCAL / (FOCAL + z2)
        val px = center.x + x * scale * radius * 0.92f
        val py = center.y + y2 * scale * radius * 0.92f
        val depth = ((z2 + 0.6f) / 1.2f).coerceIn(0f, 1f)
        val depthBucket = if (depth < 0.4f) 0 else if (depth < 0.7f) 1 else 2
        val sizeBucket = if (p.size < 1.4f) 0 else if (p.size < 1.9f) 1 else 2
        val b = depthBucket * 3 + sizeBucket
        val n = set.counts[b]
        set.buckets[b][n * 2] = px
        set.buckets[b][n * 2 + 1] = py
        set.counts[b] = n + 1
    }
    val base = 0.34f + 0.5f * energy
    drawBuckets(
        set, 9,
        floatArrayOf(1.6f, 2.2f, 2.9f, 1.7f, 2.4f, 3.1f, 1.9f, 2.6f, 3.4f),
        floatArrayOf(
            base * 0.28f, base * 0.3f, base * 0.34f,
            base * 0.55f, base * 0.6f, base * 0.66f,
            base * 0.95f, base * 1.0f, base * 1.0f,
        ),
        color, color2, paint,
    )
}

private fun project(x: Float, y: Float, z: Float, cy: Float, sy: Float, cx: Float, sx: Float, center: Offset, radius: Float): Offset {
    val x1 = x * cy + z * sy
    val z1 = -x * sy + z * cy
    val y2 = y * cx - z1 * sx
    val z2 = y * sx + z1 * cx
    val scale = FOCAL / (FOCAL + z2 * 0.9f)
    return Offset(center.x + x1 * scale * radius, center.y + y2 * scale * radius)
}

private fun DrawScope.paintSphere(set: ParticleSet, count: Int, time: Float, energy: Float, color: Color, color2: Color, paint: Paint) {
    val center = this.center
    val radius = size.width / 2 * 0.78f

    val ry = time * 0.32f
    val rx = 0.42f
    val cy = cos(ry)
    val sy = sin(ry)
    val cx = cos(rx)
    val sx = sin(rx)
    // Faint equator and meridian for structure.
    for (ring in 0..1) {
        val path = Path()
        for (i in 0..96) {
            val t = i / 96f * PI.toFloat() * 2
            val p = if (ring == 0) project(cos(t), 0f, sin(t), cy, sy, cx, sx, center, radius)
            else project(cos(t), sin(t), 0f, cy, sy, cx, sx, center, radius)
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, color.copy(alpha = 0.18f + 0.12f * energy), style = Stroke(1.dp.toPx()))
    }
    set.counts.fill(0)
    for (i in 0 until minOf(count, set.particles.size)) {
        val p = set.particles[i]
        val breathe = 1 + 0.025f * sin(time * 1.3f + p.phase) * (0.4f + energy)
        val x1 = p.x * cy + p.z * sy
        val z1 = -p.x * sy + p.z * cy
        val y2 = p.y * cx - z1 * sx
        val z2 = p.y * sx + z1 * cx
        val scale = FOCAL / (FOCAL + z2 * 0.9f)
        val px = center.x + x1 * scale * radius * breathe
        val py = center.y + y2 * scale * radius * breathe
        val depth = ((z2 + 1) / 2).coerceIn(0f, 1f)
        val depthBucket = if (depth < 0.35f) 0 else if (depth < 0.62f) 1 else 2
        val sizeBucket = if (p.size < 1.45f) 0 else 1
        val b = depthBucket * 2 + sizeBucket
        val n = set.counts[b]
        set.buckets[b][n * 2] = px
        set.buckets[b][n * 2 + 1] = py
        set.counts[b] = n + 1
    }
    val base = 0.3f + 0.5f * energy
    drawBuckets(
        set, 6,
        floatArrayOf(1.3f, 1.9f, 1.6f, 2.3f, 2.0f, 2.9f),
        floatArrayOf(base * 0.18f, base * 0.22f, base * 0.5f, base * 0.58f, base * 0.95f, base * 1.0f),
        color, color2, paint,
    )
}

private val gemVertices: List<FloatArray> = run {
    val phi = 1.618034f
    val raw = listOf(
        floatArrayOf(-1f, phi, 0f), floatArrayOf(1f, phi, 0f), floatArrayOf(-1f, -phi, 0f), floatArrayOf(1f, -phi, 0f),
        floatArrayOf(0f, -1f, phi), floatArrayOf(0f, 1f, phi), floatArrayOf(0f, -1f, -phi), floatArrayOf(0f, 1f, -phi),
        floatArrayOf(phi, 0f, -1f), floatArrayOf(phi, 0f, 1f), floatArrayOf(-phi, 0f, -1f), floatArrayOf(-phi, 0f, 1f),
    )
    val norm = sqrt(1 + phi * phi)
    raw.map { v -> floatArrayOf(v[0] / norm, v[1] / norm, v[2] / norm) }
}

private val gemEdges = listOf(
    0 to 1, 0 to 5, 0 to 7, 0 to 10, 0 to 11,
    1 to 5, 1 to 7, 1 to 8, 1 to 9,
    2 to 3, 2 to 4, 2 to 6, 2 to 10, 2 to 11,
    3 to 4, 3 to 6, 3 to 8, 3 to 9,
    4 to 5, 4 to 9, 4 to 11,
    5 to 9, 5 to 11,
    6 to 7, 6 to 8, 6 to 10,
    7 to 8, 7 to 10,
    8 to 9, 10 to 11,
)

private fun DrawScope.paintGem(set: ParticleSet, count: Int, time: Float, energy: Float, color: Color, color2: Color, paint: Paint) {
    val center = this.center
    val radius = size.width / 2 * 0.62f

    val ry = time * 0.5f
    val rx = 0.38f + 0.18f * sin(time * 0.7f)
    val cy = cos(ry)
    val sy = sin(ry)
    val cx = cos(rx)
    val sx = sin(rx)
    val float = sin(time * 1.1f) * size.height * 0.018f
    val origin = Offset(center.x, center.y + float)

    val projected = ArrayList<Offset>(gemVertices.size)
    val depths = FloatArray(gemVertices.size)
    gemVertices.forEachIndexed { i, v ->
        val x1 = v[0] * cy + v[2] * sy
        val z1 = -v[0] * sy + v[2] * cy
        val y2 = v[1] * cx - z1 * sx
        val z2 = v[1] * sx + z1 * cx
        val scale = FOCAL / (FOCAL + z2 * 0.8f)
        projected += Offset(origin.x + x1 * scale * radius, origin.y + y2 * scale * radius)
        depths[i] = ((z2 + 1) / 2).coerceIn(0f, 1f)
    }
    // Faces are not filled; a translucent core makes the wireframe read as glass.
    drawCircle(
        Brush.radialGradient(listOf(color.copy(alpha = 0.45f), color.copy(alpha = 0.05f)), center = origin, radius = radius * 0.72f),
        radius * 0.72f,
        origin,
    )
    val edges = gemEdges.sortedBy { depths[it.first] + depths[it.second] }
    for ((a, b) in edges) {
        val d = (depths[a] + depths[b]) / 2
        drawLine(
            lerp(color, color2, d).copy(alpha = 0.25f + 0.7f * d),
            projected[a],
            projected[b],
            strokeWidth = (1.1f + 1.1f * d).dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
    projected.forEachIndexed { i, p ->
        val d = depths[i]
        drawCircle(color2.copy(alpha = 0.5f + 0.5f * d), (1.6f + 2.2f * d).dp.toPx(), p)
        if (d > 0.75f) {
            drawCircle(color2.copy(alpha = 0.12f * (d - 0.75f) / 0.25f), (7 + 6 * d).dp.toPx(), p)
        }
    }
    // Sparks orbiting the gem.
    set.counts.fill(0)
    for (i in 0 until minOf(count, set.particles.size)) {
        val p = set.particles[i]
        val a = p.a + time * (0.35f + 0.3f * p.r)
        val x = cos(a) * p.r
        val z = sin(a) * p.r
        val y = sin(a * 2 + p.phase) * 0.35f
        val x1 = x * cy + z * sy
        val z1 = -x * sy + z * cy
        val y2 = y * cx - z1 * sx
        val z2 = y * sx + z1 * cx
        val scale = FOCAL / (FOCAL + z2 * 0.8f)
        val n = set.counts[0]
        set.buckets[0][n * 2] = origin.x + x1 * scale * radius
        set.buckets[0][n * 2 + 1] = origin.y + y2 * scale * radius
        set.counts[0] = n + 1
    }
    drawIntoCanvas { canvas ->
        paint.color = color2.copy(alpha = 0.35f + 0.35f * energy).toArgb()
        paint.strokeWidth = 2.2.dp.toPx()
        canvas.nativeCanvas.drawPoints(set.buckets[0], 0, set.counts[0] * 2, paint)
    }
}

/** Connection state of the power control. */
enum class PowerState { Off, Busy, On }

/**
 * The big round connect button with the particle ring behind it. The ring
 * idles when off, churns while connecting and glows steadily when on. The
 * centre shows the power glyph, or [content] (the session clock) when set.
 */
@Composable
fun ColituPowerButton(
    state: PowerState,
    onClick: (() -> Unit)?,
    size: Dp = 300.dp,
    description: String? = null,
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null,
) {
    val energy = when (state) {
        PowerState.Off -> 0.22f
        PowerState.Busy -> 1.0f
        PowerState.On -> 0.62f
    }
    val core = size * 0.46f
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        ColituParticles(
            mode = ParticleMode.Ring,
            size = size,
            energy = energy,
            color = if (state == PowerState.On) ColituColors.violet else ColituColors.blue2,
            color2 = if (state == PowerState.On) ColituColors.pink else ColituColors.lilac,
        )
        Box(
            Modifier
                .semantics {
                    role = Role.Button
                    if (description != null) contentDescription = description
                }
                .then(modifier)
                .pressable(onClick, 0.94f, focusRadius = size),
        ) {
            PowerCore(state, core, content)
        }
    }
}

@Composable
private fun PowerCore(state: PowerState, size: Dp, content: (@Composable () -> Unit)?) {
    val on = state == PowerState.On
    val busy = state == PowerState.Busy
    // The pulse is read inside drawBehind (draw phase only, no recomposition)
    // and is off on budget phones, where the particle ring is motion enough.
    val wave = if (ColituPerformance.tier != PerformanceTier.Low) {
        rememberInfiniteTransition(label = "core")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "pulse")
    } else {
        null
    }
    Box(
        Modifier
            .size(size)
            // A plain radial gradient: a blur mask redrawn at 60 fps kept
            // low-end phones busy.
            .drawBehind {
                val w = wave?.value ?: 0.5f
                val t = w * w * (3 - 2 * w)
                val glow = if (on) 0.55f + 0.25f * t else if (busy) 0.35f + 0.45f * t else 0.28f
                val spread = if (busy) 4 + 6 * t else if (on) 4 + 3 * t else 0f
                val r = this.size.minDimension / 2
                val c = Offset(this.size.width / 2, this.size.height / 2)
                drawCircle(
                    Brush.radialGradient(
                        0.55f to Color(0x66000000),
                        1f to Color.Transparent,
                        center = c.copy(y = c.y + 18.dp.toPx()),
                        radius = r + 26.dp.toPx(),
                    ),
                    radius = r + 26.dp.toPx(),
                    center = c.copy(y = c.y + 18.dp.toPx()),
                )
                val outer = r + (24 + spread).dp.toPx()
                drawCircle(
                    Brush.radialGradient(
                        (r / outer) to ColituColors.violet.copy(alpha = glow),
                        1f to ColituColors.violet.copy(alpha = 0f),
                        center = c,
                        radius = outer,
                    ),
                    radius = outer,
                    center = c,
                )
            }
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFFD0C4FF), Color(0xFF9F8CFF), Color(0xFF7A69EE)),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                ),
            )
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .border(1.2.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                .drawBehind {
                    // Gloss from the upper left, like light on glass.
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0f)),
                            center = Offset(this.size.width * 0.35f, this.size.height * 0.3f),
                            radius = this.size.width * 0.75f,
                        ),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = if (content != null) -1 else state.ordinal,
                transitionSpec = { (fadeIn(tween(260)) + scaleIn(tween(260), 0.85f)) togetherWith fadeOut(tween(200)) },
                label = "coreContent",
            ) { key ->
                if (key == -1 && content != null) {
                    content()
                } else {
                    ColituIcon(
                        if (busy) ColituIcons.ShieldHalf else ColituIcons.Power,
                        Color.White,
                        size * 0.30f,
                    )
                }
            }
        }
    }
}
