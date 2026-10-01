package com.v2ray.ang.colitu.design

import android.graphics.BlurMaskFilter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import kotlinx.coroutines.delay

/**
 * Colitu design system for the phone, ported one to one from the iOS app
 * (colitu_theme.dart): near-black surfaces, lavender accent, soft glass cards
 * and pill controls, set in Colitu Sans.
 */
object ColituColors {
    val bg = Color(0xFF0A0A0E)
    val bg2 = Color(0xFF0F0F15)
    val navy = Color(0xFF14141B)
    val surface = Color(0xFF15151C)
    val surface2 = Color(0xFF1C1C25)
    val blue = Color(0xFF7C6CF0)
    val blue2 = Color(0xFF9483FF)
    val violet = Color(0xFF9F8CFF)
    val violet2 = Color(0xFFB4A4FF)
    val lilac = Color(0xFFC4B5FD)
    val pink = Color(0xFFE2D6FF)
    val success = Color(0xFF5EE0A0)
    val warning = Color(0xFFF5C36B)
    val danger = Color(0xFFFF6B7A)

    val text = Color(0xFFF4F3FA)
    val muted = Color(0xFF9A9AAB)
    val dim = Color(0xFF63636F)
    val line = Color(0x14FFFFFF)
    val lineStrong = Color(0x26FFFFFF)
    val field = Color(0xFF15151C)
    val glass06 = Color(0x0FFFFFFF)
    val glass10 = Color(0x1AFFFFFF)

    val accent = violet
    val onAccent = Color(0xFF0B0A14)
}

object ColituGradients {
    val panel = Brush.verticalGradient(listOf(Color(0xFF191921), Color(0xFF131319)))
    val panelEdge = Brush.linearGradient(
        listOf(Color(0x33FFFFFF), Color(0x0AFFFFFF), Color(0x14FFFFFF)),
        start = Offset.Zero,
        end = Offset.Infinite,
    )
    val accent = Brush.linearGradient(
        listOf(Color(0xFFC4B5FD), Color(0xFF9F8CFF), Color(0xFF7C6CF0)),
        start = Offset.Zero,
        end = Offset.Infinite,
    )
    val badge = Brush.horizontalGradient(listOf(Color(0xFFB4A4FF), Color(0xFF8B78FF)))
    val activeOption = Brush.linearGradient(
        listOf(Color(0xFFB4A4FF), Color(0xFF9483FF)),
        start = Offset.Zero,
        end = Offset.Infinite,
    )
}

object ColituRadius {
    val sm = 14.dp
    val md = 20.dp
    val lg = 28.dp
}

val ColituSans = FontFamily(
    Font(R.font.colitu_sans_regular, FontWeight.Normal),
    Font(R.font.colitu_sans_medium, FontWeight.Medium),
    Font(R.font.colitu_sans_semibold, FontWeight.SemiBold),
    Font(R.font.colitu_sans_bold, FontWeight.Bold),
)

object ColituText {
    private fun style(
        size: Float,
        weight: FontWeight,
        color: Color,
        height: Float,
        spacing: Float = 0f,
    ) = TextStyle(
        fontFamily = ColituSans,
        fontSize = size.sp,
        fontWeight = weight,
        color = color,
        lineHeight = (size * height).sp,
        letterSpacing = spacing.sp,
    )

    val kicker = style(11f, FontWeight.SemiBold, ColituColors.muted, 1.2f, 2.2f)
    val display = style(32f, FontWeight.SemiBold, ColituColors.text, 1.12f, -1.0f)
    val h1 = style(26f, FontWeight.SemiBold, ColituColors.text, 1.18f, -0.6f)
    val h2 = style(18f, FontWeight.SemiBold, ColituColors.text, 1.25f, -0.2f)
    val body = style(15f, FontWeight.Normal, ColituColors.text, 1.5f)
    val muted = style(14.5f, FontWeight.Normal, ColituColors.muted, 1.5f)
    val small = style(12.5f, FontWeight.Medium, ColituColors.muted, 1.35f)
    val label = style(15f, FontWeight.SemiBold, ColituColors.text, 1.3f, -0.1f)
}

private val EaseOutCubic = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

// ── Modifiers ─────────────────────────────────────────────────────────────

/** Soft coloured shadow (the iOS BoxShadow), drawn under the element. */
fun Modifier.colituGlow(
    color: Color,
    blur: Dp,
    shape: Shape,
    offsetY: Dp = 0.dp,
    spread: Dp = 0.dp,
): Modifier = drawBehind {
    // Blur mask filters need a hardware canvas that supports them (API 28+);
    // without the blur the shadow would render as a hard offset block.
    if (color.alpha <= 0.01f || android.os.Build.VERSION.SDK_INT < 28) return@drawBehind
    // Blur masks are costly on budget GPUs; low-tier phones get flat cards.
    if (!ColituPerformance.tier.blurShadows) return@drawBehind
    val spreadPx = spread.toPx()
    val outline = shape.createOutline(
        Size(size.width + spreadPx * 2, size.height + spreadPx * 2),
        layoutDirection,
        this,
    )
    drawIntoCanvas { canvas ->
        val paint = androidx.compose.ui.graphics.Paint().apply {
            this.color = color
            asFrameworkPaint().maskFilter = BlurMaskFilter(blur.toPx().coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL)
        }
        canvas.save()
        canvas.translate(-spreadPx, offsetY.toPx() - spreadPx)
        canvas.drawOutline(outline, paint)
        canvas.restore()
    }
}

/** Scale-on-press used by every tappable surface. */
@Composable
fun Modifier.pressable(
    onClick: (() -> Unit)?,
    scale: Float = 0.97f,
    role: Role = Role.Button,
    focusRadius: Dp = 22.dp,
    focusRing: Boolean = true,
): Modifier {
    if (onClick == null) return this
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    // On a TV the focused element grows a little, like the system launcher.
    val target = when {
        pressed -> scale
        focused && ColituTv.isTv -> 1.03f
        else -> 1f
    }
    val value by animateFloatAsState(target, tween(130, easing = EaseOutCubic), label = "press")
    return this
        .graphicsLayer {
            scaleX = value
            scaleY = value
        }
        .then(if (focusRing) Modifier.focusRing(interaction, focusRadius) else Modifier)
        .clickable(interactionSource = interaction, indication = null, role = role, onClick = onClick)
}

/** Fades and lifts content in once, with an optional delay (card stagger). */
@Composable
fun Modifier.reveal(delayMs: Int = 0, offset: Dp = 18.dp, durationMs: Int = 520): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (delayMs > 0) delay(delayMs.toLong())
        progress.animateTo(1f, tween(durationMs, easing = EaseOutCubic))
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = offset.toPx() * (1 - progress.value)
    }
}

// ── Text ──────────────────────────────────────────────────────────────────

@Composable
fun CText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    size: TextUnit = TextUnit.Unspecified,
    weight: FontWeight? = null,
) {
    var merged = style
    if (color != Color.Unspecified) merged = merged.copy(color = color)
    if (align != null) merged = merged.copy(textAlign = align)
    if (size != TextUnit.Unspecified) merged = merged.copy(fontSize = size, lineHeight = size * 1.3f)
    if (weight != null) merged = merged.copy(fontWeight = weight)
    BasicText(
        text = text,
        style = merged,
        modifier = modifier,
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

// ── Backdrop ──────────────────────────────────────────────────────────────

/**
 * Near-black background with a hex dot grid fading out from the top and a
 * soft lavender glow behind the top of the page.
 */
@Composable
fun ColituBackdrop(modifier: Modifier = Modifier, glow: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(ColituColors.bg)) {
        // Rendered once into a bitmap: ~2,000 dots replayed on every frame of
        // the particle animation cost budget phones a whole core.
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val bitmap = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
                CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(bitmap), size) {
                    drawBackdrop(glow)
                }
                onDrawBehind { drawImage(bitmap) }
            },
        )
        content()
    }
}

private fun DrawScope.drawBackdrop(glow: Boolean) {
    if (glow) {
        val center = Offset(size.width * 0.5f, -size.height * 0.05f)
        val radius = size.width * 0.95f
        drawCircle(
            Brush.radialGradient(
                0f to Color(0x449F8CFF),
                0.45f to Color(0x149F8CFF),
                1f to Color.Transparent,
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }
    val step = 18.dp.toPx()
    val rowHeight = step * 0.866f
    val fadeFrom = size.height * 0.05f
    val fadeTo = size.height * 0.62f
    val dot = 0.9.dp.toPx()
    var row = 0
    var y = 6.dp.toPx()
    while (y < fadeTo) {
        val t = ((y - fadeFrom) / (fadeTo - fadeFrom)).coerceIn(0f, 1f)
        val alpha = 0.16f * (1 - t) * (1 - t)
        if (alpha < 0.008f) break
        var x = if (row % 2 == 1) step / 2 else 0f
        val color = Color.White.copy(alpha = alpha)
        while (x < size.width + step) {
            drawCircle(color, dot, Offset(x, y))
            x += step
        }
        y += rowHeight
        row++
    }
}

// ── Panels ────────────────────────────────────────────────────────────────

/** Glass card: soft dark body, lit hairline edge, optional lavender glow. */
@Composable
fun ColituPanel(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(18.dp),
    radius: Dp = ColituRadius.lg,
    glow: Boolean = false,
    color: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .pressable(onClick)
            .colituGlow(
                if (glow) Color(0x559F8CFF) else Color(0x66000000),
                if (glow) 24.dp else 12.dp,
                shape,
                offsetY = if (glow) 16.dp else 10.dp,
            )
            .clip(shape)
            .background(ColituGradients.panelEdge)
            .padding(1.dp)
            .clip(RoundedCornerShape(radius - 1.dp))
            .then(if (color != null) Modifier.background(color) else Modifier.background(ColituGradients.panel))
            .padding(padding),
    ) { content() }
}

/** Flat tile inside a panel or list; highlighted when active. */
@Composable
fun ColituTile(
    modifier: Modifier = Modifier,
    active: Boolean = false,
    padding: PaddingValues = PaddingValues(14.dp),
    radius: Dp = ColituRadius.md,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    val bg by animateColorAsState(if (active) Color(0x1F9F8CFF) else ColituColors.surface, tween(180), label = "tile")
    val edge by animateColorAsState(if (active) Color(0x669F8CFF) else ColituColors.line, tween(180), label = "edge")
    Box(
        modifier
            .pressable(onClick)
            .clip(shape)
            .background(bg)
            .border(1.dp, edge, shape)
            .padding(padding),
    ) { content() }
}

// ── Buttons ───────────────────────────────────────────────────────────────

enum class ColituButtonKind { Primary, Secondary, Danger }

/** Pill button: lavender (primary), dark glass (secondary) or rose glass (danger). */
@Composable
fun ColituButton(
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    kind: ColituButtonKind = ColituButtonKind.Primary,
    loading: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    expand: Boolean = true,
    height: Dp = if (kind == ColituButtonKind.Primary) 56.dp else 52.dp,
) {
    val enabled = onClick != null && !loading
    val shape = RoundedCornerShape(50)
    val fg = when (kind) {
        ColituButtonKind.Primary -> ColituColors.onAccent
        ColituButtonKind.Secondary -> ColituColors.text
        ColituButtonKind.Danger -> Color(0xFFFFA3AE)
    }
    val opacity by animateFloatAsState(if (enabled || loading) 1f else 0.5f, tween(160), label = "btn")
    val base = when (kind) {
        ColituButtonKind.Primary -> Modifier
            .then(if (enabled) Modifier.colituGlow(Color(0x669F8CFF), 16.dp, shape, 10.dp) else Modifier)
            .clip(shape)
            .background(ColituGradients.accent)
        ColituButtonKind.Secondary -> Modifier
            .clip(shape)
            .background(ColituColors.surface2)
            .border(1.dp, ColituColors.lineStrong, shape)
        ColituButtonKind.Danger -> Modifier
            .clip(shape)
            .background(Color(0x22FF6B7A))
            .border(1.dp, Color(0x55FF6B7A), shape)
    }
    Row(
        modifier
            .semantics { contentDescription = label }
            .pressable(if (enabled) onClick else null)
            .alpha(opacity)
            .then(if (expand) Modifier.fillMaxWidth() else Modifier)
            .height(height)
            .then(base)
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            ColituSpinner(size = 18.dp, color = fg)
        } else {
            if (icon != null) {
                ColituIcon(icon, fg, 18.dp)
                Spacer(Modifier.width(8.dp))
            }
            CText(label, ColituText.label, color = fg, size = 15.5.sp, maxLines = 1)
        }
    }
}

@Composable
fun ColituLinkButton(
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    color: Color = ColituColors.lilac,
) {
    Row(
        modifier.pressable(onClick).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            ColituIcon(icon, color, 16.dp)
            Spacer(Modifier.width(6.dp))
        }
        CText(label, ColituText.small, color = color, size = 13.5.sp, weight = FontWeight.SemiBold)
    }
}

/** Round glass icon button (steppers, corners). */
@Composable
fun ColituIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    accent: Boolean = false,
    description: String? = null,
) {
    Box(
        modifier
            .semantics { if (description != null) contentDescription = description }
            .pressable(onClick, 0.92f)
            .size(size)
            .then(if (accent) Modifier.colituGlow(Color(0x559F8CFF), 10.dp, CircleShape, 6.dp) else Modifier)
            .clip(CircleShape)
            .then(
                if (accent) Modifier.background(ColituGradients.accent)
                else Modifier.background(ColituColors.surface2).border(1.dp, ColituColors.lineStrong, CircleShape),
            ),
        contentAlignment = Alignment.Center,
    ) {
        ColituIcon(icon, if (accent) ColituColors.onAccent else ColituColors.text, size * 0.44f)
    }
}

// ── Small pieces ──────────────────────────────────────────────────────────

@Composable
fun ColituKicker(text: String, modifier: Modifier = Modifier, color: Color = ColituColors.muted) {
    CText(text.uppercase(), ColituText.kicker, modifier, color = color)
}

enum class BadgeTone { Accent, Success, Warning, Danger, Neutral }

@Composable
fun ColituBadge(text: String, tone: BadgeTone = BadgeTone.Accent, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    val (bg, fg) = when (tone) {
        BadgeTone.Accent -> null to ColituColors.onAccent
        BadgeTone.Success -> Color(0x265EE0A0) to ColituColors.success
        BadgeTone.Warning -> Color(0x26F5C36B) to ColituColors.warning
        BadgeTone.Danger -> Color(0x26FF6B7A) to ColituColors.danger
        BadgeTone.Neutral -> ColituColors.glass10 to ColituColors.text
    }
    Box(
        modifier
            .clip(shape)
            .then(if (bg == null) Modifier.background(ColituGradients.badge) else Modifier.background(bg))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        CText(
            text.uppercase(),
            ColituText.kicker.copy(fontSize = 10.5.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold),
            color = fg,
            maxLines = 1,
        )
    }
}

@Composable
fun ColituPulseDot(color: Color, pulsing: Boolean, size: Dp = 8.dp) {
    // The pulse is read while drawing only, so it never recomposes the page;
    // budget phones keep a steady dot.
    val animate = pulsing && ColituPerformance.tier != PerformanceTier.Low
    val wave = if (animate) {
        rememberInfiniteTransition(label = "pulse")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "dot")
    } else {
        null
    }
    Box(
        Modifier
            .size(size)
            .drawBehind {
                val glow = if (wave != null) 0.4f + 0.6f * wave.value else 0.8f
                val r = this.size.minDimension / 2
                drawCircle(
                    Brush.radialGradient(
                        (r / (r + 5.dp.toPx())) to color.copy(alpha = glow),
                        1f to color.copy(alpha = 0f),
                        center = center,
                        radius = r + 5.dp.toPx(),
                    ),
                    radius = r + 5.dp.toPx(),
                )
                drawCircle(color, r)
            },
    )
}

@Composable
fun ColituSpinner(size: Dp = 22.dp, color: Color = ColituColors.lilac) {
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a")
    val sweep by transition.animateFloat(40f, 250f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "s")
    Canvas(Modifier.size(size).rotate(angle)) {
        val stroke = 2.2.dp.toPx()
        drawArc(
            color,
            startAngle = 0f,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/** Flag images from assets/flags (square flag-icons renders, MIT), decoded once. */
private object FlagImages {
    private val cache = HashMap<String, androidx.compose.ui.graphics.ImageBitmap?>()

    fun get(context: android.content.Context, code: String): androidx.compose.ui.graphics.ImageBitmap? =
        synchronized(cache) {
            cache.getOrPut(code) {
                runCatching<androidx.compose.ui.graphics.ImageBitmap?> {
                    val bitmap: android.graphics.Bitmap? = context.assets.open("flags/$code.png").use { stream ->
                        android.graphics.BitmapFactory.decodeStream(stream)
                    }
                    bitmap?.asImageBitmap()
                }.getOrNull()
            }
        }
}

/**
 * Round country flag, centred in its circle. Emoji flags render differently
 * per font and not at all on some phones and TVs, so the bundled images are
 * used; [countryCode] is ISO 3166-1 alpha-2 ("UK" counts as Great Britain).
 */
@Composable
fun ColituFlag(countryCode: String?, size: Dp = 44.dp) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val code = countryCode?.trim()?.lowercase()?.let { if (it == "uk") "gb" else it }
        ?.takeIf { Regex("^[a-z]{2}$").matches(it) }
    val image = remember(code) { code?.let { FlagImages.get(context, it) } }
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(ColituColors.surface2)
            .border(1.dp, ColituColors.lineStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            androidx.compose.foundation.Image(
                image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                alignment = Alignment.Center,
            )
        } else {
            ColituIcon(ColituIcons.Globe, ColituColors.muted, size * 0.55f)
        }
    }
}

/** Round icon well with a lavender tint (feature rows, stat tiles). */
@Composable
fun ColituRoundIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    size: Dp = 40.dp,
    accent: Boolean = false,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (accent) Modifier.background(ColituGradients.accent)
                else Modifier.background(ColituColors.surface2).border(1.dp, ColituColors.line, CircleShape),
            ),
        contentAlignment = Alignment.Center,
    ) {
        ColituIcon(icon, if (accent) ColituColors.onAccent else ColituColors.lilac, size * 0.46f)
    }
}

/** Segmented control: sliding lavender pill. */
@Composable
fun <T> ColituSegment(
    values: List<T>,
    selected: T,
    onChange: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
) {
    val index = values.indexOf(selected).coerceAtLeast(0)
    val shape = RoundedCornerShape(50)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(ColituColors.surface)
            .border(1.dp, ColituColors.line, shape)
            .padding(3.dp),
    ) {
        val width = maxWidth / values.size
        val left by animateDpAsState(width * index, tween(260, easing = EaseOutCubic), label = "seg")
        Box(
            Modifier
                .offset(x = left)
                .width(width)
                .fillMaxHeight()
                .clip(shape)
                .background(ColituGradients.activeOption),
        )
        Row(Modifier.fillMaxSize()) {
            values.forEach { value ->
                val active = value == selected
                val color by animateColorAsState(if (active) ColituColors.onAccent else ColituColors.muted, tween(200), label = "segtext")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics { this.selected = active; role = Role.Tab }
                        .let { base ->
                            val interaction = remember { MutableInteractionSource() }
                            base.focusRing(interaction).clickable(interaction, null) { onChange(value) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    CText(label(value), ColituText.label, color = color, size = 13.5.sp, maxLines = 1)
                }
            }
        }
    }
}

/** Filter chip (locations), with an optional leading icon. */
@Composable
fun ColituChip(
    label: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .pressable(onClick)
            .clip(shape)
            .then(
                if (selected) Modifier.background(ColituGradients.activeOption)
                else Modifier.background(ColituColors.surface2).border(1.dp, ColituColors.line, shape),
            )
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = if (selected) ColituColors.onAccent else ColituColors.text
            if (icon != null) {
                ColituIcon(icon, color, 18.dp)
                Spacer(Modifier.width(8.dp))
            }
            CText(label, ColituText.label, color = color, size = 13.5.sp)
        }
    }
}

/** iOS-style switch in the lavender accent. */
@Composable
fun ColituSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)?) {
    val knob by animateDpAsState(if (checked) 22.dp else 2.dp, tween(200, easing = EaseOutCubic), label = "knob")
    val track by animateColorAsState(if (checked) ColituColors.violet else Color(0x33FFFFFF), tween(200), label = "track")
    Box(
        Modifier
            .size(51.dp, 31.dp)
            .clip(RoundedCornerShape(50))
            .background(track)
            .then(
                if (onChange != null) {
                    val interaction = remember { MutableInteractionSource() }
                    Modifier.focusRing(interaction).clickable(interaction, null, role = Role.Switch) { onChange(!checked) }
                } else Modifier,
            ),
    ) {
        Box(
            Modifier
                .offset(x = knob, y = 2.dp)
                .size(27.dp)
                .colituGlow(Color(0x40000000), 3.dp, CircleShape, 2.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
private fun RowLabels(title: String, hint: String?, modifier: Modifier) {
    Column(modifier) {
        CText(title, ColituText.label)
        if (!hint.isNullOrEmpty()) {
            Spacer(Modifier.height(2.dp))
            CText(hint, ColituText.small, maxLines = 3)
        }
    }
}

/** Setting row: icon well, title, hint and a switch (or a custom trailing). */
@Composable
fun ColituSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    hint: String?,
    value: Boolean,
    onChange: ((Boolean) -> Unit)?,
    trailing: (@Composable () -> Unit)? = null,
) {
    ColituTile(
        onClick = onChange?.let { { it(!value) } },
        padding = PaddingValues(start = 12.dp, top = 11.dp, end = 12.dp, bottom = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColituRoundIcon(icon)
            Spacer(Modifier.width(12.dp))
            RowLabels(title, hint, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            // On a TV the whole row is the one focus stop; the switch only shows the state.
            if (trailing != null) trailing() else ColituSwitch(value, if (ColituTv.isTv) null else onChange)
        }
    }
}

/** Setting row that opens something: icon, title, hint and a chevron. */
@Composable
fun ColituActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    hint: String? = null,
    onClick: (() -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ColituTile(
        onClick = onClick,
        padding = PaddingValues(start = 12.dp, top = 11.dp, end = 12.dp, bottom = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColituRoundIcon(icon)
            Spacer(Modifier.width(12.dp))
            RowLabels(title, hint, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            if (badge != null) {
                badge()
                Spacer(Modifier.width(8.dp))
            }
            if (trailing != null) trailing() else ColituIcon(ColituIcons.ChevronRight, ColituColors.dim, 16.dp)
        }
    }
}

/** Text field with label and inline error. */
@Composable
fun ColituField(
    value: String,
    onChange: (String) -> Unit,
    label: String?,
    modifier: Modifier = Modifier,
    hint: String? = null,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onSubmit: (() -> Unit)? = null,
    error: String? = null,
    prefix: androidx.compose.ui.graphics.vector.ImageVector? = null,
    suffix: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    pill: Boolean = false,
    fill: Color = ColituColors.field,
) {
    var focused by remember { mutableStateOf(false) }
    val hasError = !error.isNullOrEmpty()
    val shape = if (pill) RoundedCornerShape(50) else RoundedCornerShape(ColituRadius.md)
    val edge by animateColorAsState(
        when {
            hasError -> ColituColors.danger
            focused -> if (ColituTv.isTv) Color.White else ColituColors.violet
            else -> ColituColors.line
        },
        tween(160),
        label = "field",
    )
    Column(modifier) {
        if (label != null) {
            CText(label, ColituText.small, Modifier.padding(start = 4.dp, bottom = 6.dp), color = ColituColors.muted, weight = FontWeight.SemiBold)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(fill)
                .border(if (focused && ColituTv.isTv) 2.dp else 1.dp, edge, shape)
                .padding(start = if (prefix != null) 14.dp else 16.dp, end = if (suffix != null) 6.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (prefix != null) {
                ColituIcon(prefix, ColituColors.dim, 20.dp)
                Spacer(Modifier.width(10.dp))
            }
            Box(Modifier.weight(1f).padding(vertical = 15.dp)) {
                if (value.isEmpty() && hint != null) {
                    CText(hint, ColituText.body.copy(fontSize = 16.sp), color = ColituColors.dim, maxLines = 1)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = ColituText.body.copy(fontSize = 16.sp),
                    cursorBrush = SolidColor(ColituColors.lilac),
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (password) KeyboardType.Password else keyboardType,
                        imeAction = imeAction,
                        autoCorrectEnabled = false,
                    ),
                    // "Next" moves on to the following field unless the screen handles it.
                    keyboardActions = KeyboardActions(onAny = { if (onSubmit != null) onSubmit() else defaultKeyboardAction(imeAction) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFieldEscape()
                        .onFocusChanged { focused = it.isFocused },
                )
            }
            if (suffix != null) suffix()
        }
        if (hasError) {
            CText(error!!, ColituText.small, Modifier.padding(start = 4.dp, top = 6.dp), color = ColituColors.danger)
        }
    }
}

@Composable
fun ColituCheck(value: Boolean, onChange: (Boolean) -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.focusRing(interaction, 8.dp).clickable(interaction, null, role = Role.Checkbox) { onChange(!value) }) {
        Box(
            Modifier
                .padding(top = 1.dp)
                .size(22.dp)
                .clip(shape)
                .then(
                    if (value) Modifier.background(ColituGradients.accent)
                    else Modifier.background(ColituColors.field).border(1.dp, ColituColors.lineStrong, shape),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (value) ColituIcon(ColituIcons.Check, ColituColors.onAccent, 15.dp)
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

// ── Brand ─────────────────────────────────────────────────────────────────

/** "COLITU" wordmark, optionally followed by a gradient "VPN". */
@Composable
fun ColituWordmark(height: Dp = 18.dp, withVpn: Boolean = true, color: Color = ColituColors.text) {
    val colitu = remember { BrandPaths.build(BrandPaths.colitu) }
    val vpn = remember { BrandPaths.build(BrandPaths.vpn) }
    val gap = 46f
    val units = BrandPaths.COLITU_WIDTH + if (withVpn) gap + BrandPaths.VPN_WIDTH else 0f
    Canvas(
        Modifier
            .height(height)
            .width(height * (units / 100f))
            .semantics { contentDescription = if (withVpn) "Colitu VPN" else "Colitu" },
    ) {
        val s = size.height / 100f
        scale(s, s, pivot = Offset.Zero) {
            drawPath(colitu, color)
            if (withVpn) {
                translate(left = BrandPaths.COLITU_WIDTH + gap) {
                    drawPath(
                        vpn,
                        Brush.linearGradient(
                            listOf(Color(0xFFC4B5FD), Color(0xFF9F8CFF), Color(0xFF7C6CF0)),
                            start = Offset.Zero,
                            end = Offset(BrandPaths.VPN_WIDTH, 100f),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
fun ColituMark(size: Dp = 40.dp) {
    Image(
        painterResource(R.drawable.colitu_mark),
        contentDescription = null,
        modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)),
    )
}

// ── Navigation ────────────────────────────────────────────────────────────

class ColituNavItem(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val badge: Int = 0)

/** Floating pill with one round button per tab; the active one is lavender. */
@Composable
fun ColituNavBar(items: List<ColituNavItem>, index: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .wrapContentWidth()
            .colituGlow(Color(0xB3000000), 18.dp, shape, 12.dp)
            .clip(shape)
            .background(Color(0xF2141419))
            .border(1.dp, ColituColors.lineStrong, shape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEachIndexed { i, item ->
            val active = i == index
            val iconColor by animateColorAsState(if (active) ColituColors.onAccent else ColituColors.muted, tween(240), label = "nav")
            val lit by animateFloatAsState(if (active) 1f else 0f, tween(240, easing = EaseOutCubic), label = "lit")
            Box(
                Modifier
                    .semantics {
                        selected = active
                        contentDescription = item.label
                        role = Role.Tab
                    }
                    .pressable({ onChange(i) }, 0.9f, Role.Tab)
                    .size(54.dp)
                    .colituGlow(Color(0x669F8CFF).copy(alpha = 0.4f * lit), 10.dp, CircleShape, 6.dp)
                    .clip(CircleShape)
                    .background(ColituColors.surface2)
                    .background(ColituGradients.accent, alpha = lit),
                contentAlignment = Alignment.Center,
            ) {
                ColituIcon(item.icon, iconColor, 22.dp)
                if (item.badge > 0) {
                    // Unread count (live support), pinned to the top-right of the button.
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 6.dp, end = 6.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0E0E14))
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(ColituColors.danger),
                        contentAlignment = Alignment.Center,
                    ) {
                        CText(if (item.badge > 9) "9+" else item.badge.toString(), ColituText.small, color = Color.White, size = 9.sp, weight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ── Notices ───────────────────────────────────────────────────────────────

/** Inline notice inside a page (errors, offline hints). */
@Composable
fun ColituNotice(message: String, error: Boolean = true, action: (@Composable () -> Unit)? = null) {
    val color = if (error) ColituColors.danger else ColituColors.lilac
    val shape = RoundedCornerShape(ColituRadius.md)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color.copy(alpha = 0.1f))
            .border(1.dp, color.copy(alpha = 0.35f), shape)
            .padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 12.dp),
    ) {
        ColituIcon(if (error) ColituIcons.Warning else ColituIcons.Info, color, 18.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            CText(message, ColituText.body.copy(fontSize = 14.sp))
            if (action != null) {
                Spacer(Modifier.height(8.dp))
                action()
            }
        }
    }
}

/** Floating toast shown above the nav bar. */
@Composable
fun ColituToast(message: String?, error: Boolean, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = message,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
        label = "toast",
        modifier = modifier,
    ) { text ->
        if (text == null) {
            Spacer(Modifier.height(0.dp))
        } else {
            val shape = RoundedCornerShape(ColituRadius.md)
            Row(
                Modifier
                    .fillMaxWidth()
                    .colituGlow(Color(0x99000000), 14.dp, shape, 8.dp)
                    .clip(shape)
                    .background(if (error) Color(0xF2331419) else Color(0xF21B1B24))
                    .border(1.dp, if (error) Color(0x66FF6B7A) else ColituColors.lineStrong, shape)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ColituIcon(
                    if (error) ColituIcons.Warning else ColituIcons.CheckCircle,
                    if (error) Color(0xFFFFA3AE) else ColituColors.success,
                    18.dp,
                )
                Spacer(Modifier.width(10.dp))
                CText(text, ColituText.body, Modifier.weight(1f))
            }
        }
    }
}

/** Small lavender outline pill with text (auth kicker, onboarding badge). */
@Composable
fun ColituPill(text: String, arrow: Boolean = false, kicker: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(Color(0x149F8CFF))
            .border(1.dp, Color(0x669F8CFF), shape)
            .padding(start = 14.dp, top = 7.dp, end = if (arrow) 12.dp else 14.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (kicker) {
            CText(text, ColituText.kicker.copy(fontSize = 10.5.sp), color = ColituColors.lilac)
        } else {
            CText(text, ColituText.small, color = ColituColors.lilac, weight = FontWeight.SemiBold)
        }
        if (arrow) {
            Spacer(Modifier.width(6.dp))
            ColituIcon(ColituIcons.ArrowUpRight, ColituColors.lilac, 13.dp)
        }
    }
}

/** Horizontal hairline. */
@Composable
fun ColituDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(ColituColors.line))
}

/** Thin progress bar (traffic quota). */
@Composable
fun ColituProgress(value: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(ColituColors.glass10)) {
        Box(
            Modifier
                .fillMaxWidth(value.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(ColituColors.lilac),
        )
    }
}

