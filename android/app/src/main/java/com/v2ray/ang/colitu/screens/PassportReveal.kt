package com.v2ray.ang.colitu.screens

import com.v2ray.ang.colitu.design.ColituIcon
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.v2ray.ang.colitu.app.ColituController
import com.v2ray.ang.colitu.data.ColituPassport
import com.v2ray.ang.colitu.data.StampEvent
import com.v2ray.ang.colitu.data.PassportAchievement
import com.v2ray.ang.colitu.data.PassportCollection
import com.v2ray.ang.colitu.data.PassportGoals
import com.v2ray.ang.colitu.data.PassportSync
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.v2ray.ang.colitu.data.StampLevel
import com.v2ray.ang.colitu.data.StampProgress
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituButton
import com.v2ray.ang.colitu.design.ColituButtonKind
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.l10n.ColituLoc
import kotlinx.coroutines.delay

/**
 * Plays the passport rewards the server created (unseen events, fetched by
 * [PassportSync]) one after another, oldest first: the cinematic reveal for a
 * new stamp or level, the flying piece for a puzzle piece. Each finished
 * reveal is acknowledged, so it plays once on every device.
 */
@Composable
fun PassportRevealHost(c: ColituController) {
    LaunchedEffect(Unit) { ColituPassport.reload() }
    val item = PassportSync.current ?: return
    val event = item.event ?: return
    // Once per reveal: a dismiss and the auto-close may both fire.
    val done: () -> Unit = { if (PassportSync.current?.id == item.id) PassportSync.revealDone() }
    // Taken away by an admin before it played: acknowledged without a reveal.
    if (!com.v2ray.ang.colitu.data.PassportJson.stillHolds(event, ColituPassport.server)) {
        LaunchedEffect(item.id) { done() }
        return
    }
    androidx.compose.runtime.key(item.id) {
        when (event) {
            is StampEvent.Piece -> PieceReveal(event, done)
            is StampEvent.Collected -> CollectionReveal(event, done)
            is StampEvent.Achieved -> AchievementReveal(event, done)
            is StampEvent.Chapter -> ChapterReveal(event, done)
            is StampEvent.Expedition -> ExpeditionReveal(event, done)
            is StampEvent.Special -> SpecialReveal(event, done)
            else -> CinematicReveal(event, c.planActive, done)
        }
    }
}

private fun clamp01(v: Float) = v.coerceIn(0f, 1f)

/** 0..1 progress of [t] inside [from]..[to]. */
private fun span(t: Float, from: Float, to: Float) = clamp01((t - from) / (to - from))

/** Haptic feedback unless the passport's vibration setting is off. */
internal fun passportHaptic(view: android.view.View, type: Int) {
    if (com.v2ray.ang.colitu.data.PassportSocial.haptics) view.performHapticFeedback(type)
}

/** Short sound from res/raw when it ships and the phone is not silent; otherwise nothing. */
private fun playSound(context: Context, name: String) {
    runCatching {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (!com.v2ray.ang.colitu.data.PassportSocial.sound || audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        @Suppress("DiscouragedApi")
        val id = context.resources.getIdentifier(name, "raw", context.packageName)
        if (id == 0) return
        MediaPlayer.create(context, id)?.apply {
            setOnCompletionListener { it.release() }
            start()
        }
    }
}

/**
 * New stamp, new level or a finished Diamond: the screen dims, the stamp's
 * outline appears, its material sweeps in from the left with a glint, then
 * "GERMANY UNLOCKED" and the Story button.
 */
@Composable
private fun CinematicReveal(event: StampEvent, premium: Boolean, onDone: () -> Unit) {
    val loc = ColituLoc
    val context = LocalContext.current
    val view = LocalView.current
    val stamp = ColituPassport.stampOf(event.code)
    if (stamp == null) {
        // A country of a later chapter: kept silently until that chapter opens.
        LaunchedEffect(event) { onDone() }
        return
    }
    val after = ColituPassport.progressOf(stamp.code)
    val before = when (event) {
        is StampEvent.Unlocked -> StampProgress(0, 0, null, 0L)
        else -> after.copy(pieces = (after.pieces - 1).coerceAtLeast(0))
    }
    val levelUp = event !is StampEvent.Unlocked
    val country = loc.countryName(stamp.code)
    val kicker = if (levelUp) loc["passport.r.newLevel"] else loc["passport.r.newStamp"]
    val title = when (event) {
        is StampEvent.LevelUp -> loc.format("passport.r.levelUnlocked", "level" to levelName(event.level))
        is StampEvent.Mastered -> loc["passport.masteredShort"]
        else -> loc.format("passport.r.unlocked", "country" to country)
    }
    val accent = after.level?.let { levelColor(it) } ?: ColituColors.lilac

    val time = remember(event) { Animatable(0f) }
    var buzzed by remember(event) { mutableStateOf(false) }
    LaunchedEffect(event) {
        playSound(context, if (levelUp) "passport_level" else "passport_reveal")
        withMotion { time.animateTo(1f, tween(2400, easing = LinearEasing)) }
    }
    val t = time.value
    if (t >= 0.62f && !buzzed) {
        buzzed = true
        passportHaptic(view, if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
    }

    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF05040F).copy(alpha = 0.94f * span(t, 0f, 0.12f))),
            contentAlignment = Alignment.Center,
        ) {
            // Light burst behind the stamp when the material lands.
            val burst = span(t, 0.6f, 0.95f)
            Canvas(Modifier.size(420.dp).alpha(if (burst > 0f) 1f - burst * 0.7f else 0f)) {
                drawCircle(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.45f), Color.Transparent)),
                    radius = size.minDimension / 2 * (0.4f + burst * 0.6f),
                )
                drawCircle(accent.copy(alpha = 0.5f * (1f - burst)), radius = size.minDimension / 2 * (0.3f + burst * 0.7f), style = Stroke(2.dp.toPx()))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 28.dp)) {
                val appear = FastOutSlowInEasing.transform(span(t, 0.08f, 0.32f))
                // Level-ups turn once around while the new material comes in.
                val spin = if (levelUp) FastOutSlowInEasing.transform(span(t, 0.2f, 0.62f)) * 360f else 0f
                val sweep = span(t, 0.32f, 0.62f)
                Box(
                    Modifier
                        .size(280.dp)
                        .graphicsLayer {
                            alpha = appear
                            scaleX = 0.82f + 0.18f * appear
                            scaleY = 0.82f + 0.18f * appear
                            rotationY = spin
                            cameraDistance = 14f * density
                        },
                ) {
                    StampArt(stamp, before, Modifier.fillMaxSize(), lockSize = 0.dp)
                    StampArt(
                        stamp,
                        after,
                        Modifier
                            .fillMaxSize()
                            .drawWithContent {
                                clipRect(right = size.width * sweep) { this@drawWithContent.drawContent() }
                            },
                        lockSize = 0.dp,
                    )
                    if (sweep in 0.01f..0.99f) {
                        Canvas(Modifier.fillMaxSize()) {
                            val x = size.width * sweep
                            drawRect(
                                Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent), startX = x - 40.dp.toPx(), endX = x + 12.dp.toPx()),
                                topLeft = Offset(x - 40.dp.toPx(), 0f),
                                size = Size(52.dp.toPx(), size.height),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
                val words = span(t, 0.62f, 0.8f)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.graphicsLayer {
                        alpha = words
                        translationY = (1f - words) * 24.dp.toPx()
                        scaleX = 1.12f - 0.12f * words
                        scaleY = 1.12f - 0.12f * words
                    },
                ) {
                    CText(kicker, ColituText.kicker, color = accent)
                    Spacer(Modifier.height(8.dp))
                    CText(title.uppercase(), ColituText.display, size = 30.sp, weight = FontWeight.Bold, align = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    CText("$country · ${loc.format("passport.stampNo", "n" to stampNumber(stamp))}", ColituText.muted, color = accent, align = TextAlign.Center)
                }
                Spacer(Modifier.height(28.dp))
                val buttons = span(t, 0.82f, 1f)
                Column(
                    Modifier.widthIn(max = 420.dp).fillMaxWidth().navigationBarsPadding().alpha(buttons),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ColituButton(loc["passport.r.story"], if (buttons > 0.5f) ({ sharePassport(context, stamp, premium); onDone() }) else null)
                    ColituButton(loc["passport.r.add"], if (buttons > 0.5f) onDone else null, kind = ColituButtonKind.Secondary)
                }
            }
        }
    }
}

/**
 * One puzzle piece: the stamp with its puzzle, a piece flies up into its
 * slot, the slot flashes and the count ticks up. Closes by itself.
 */
@Composable
private fun PieceReveal(event: StampEvent.Piece, onDone: () -> Unit) {
    val loc = ColituLoc
    val context = LocalContext.current
    val view = LocalView.current
    val stamp = ColituPassport.stampOf(event.code)
    if (stamp == null) {
        LaunchedEffect(event) { onDone() }
        return
    }
    val now = ColituPassport.progressOf(stamp.code)
    // The slot this piece fills, counted inside its level's puzzle.
    val filled = event.levelPieces.coerceIn(1, StampProgress.PIECES)
    val pieceBase = (now.pieces - 1).coerceAtLeast(0) / StampProgress.PIECES * StampProgress.PIECES
    val before = now.copy(pieces = pieceBase + filled - 1)
    val after = now.copy(pieces = pieceBase + filled - if (filled == StampProgress.PIECES) 1 else 0)
    val level = before.level ?: StampLevel.Bronze
    val color = levelColor(level)

    val time = remember(event) { Animatable(0f) }
    var landed by remember(event) { mutableStateOf(false) }
    LaunchedEffect(event) {
        playSound(context, "passport_piece")
        val start = System.currentTimeMillis()
        withMotion { time.animateTo(1f, tween(1800, easing = LinearEasing)) }
        // With animations off the card still stays long enough to read.
        delay((2_300 - (System.currentTimeMillis() - start)).coerceAtLeast(500))
        onDone()
    }
    val t = time.value
    if (t >= 0.55f && !landed) {
        landed = true
        passportHaptic(view, HapticFeedbackConstants.KEYBOARD_TAP)
    }

    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF05040F).copy(alpha = 0.86f * span(t, 0f, 0.1f)))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDone),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(260.dp).alpha(span(t, 0f, 0.15f))) {
                    StampArt(stamp, if (landed) after else before, Modifier.fillMaxSize(), lockSize = 0.dp, puzzle = true)
                    val index = pieceSlot(filled)
                    Canvas(Modifier.fillMaxSize()) {
                        val inset = size.minDimension * 0.06f
                        val cw = (size.width - inset * 2) / 3
                        val ch = (size.height - inset * 2) / 4
                        val target = Offset(inset + (index % 3) * cw, inset + (index / 3) * ch)
                        val fly = FastOutSlowInEasing.transform(span(t, 0.12f, 0.55f))
                        if (fly < 1f) {
                            val start = Offset(size.width / 2 - cw / 2, size.height + 120.dp.toPx())
                            val pos = Offset(start.x + (target.x - start.x) * fly, start.y + (target.y - start.y) * fly)
                            val scale = 1.4f - 0.4f * fly
                            drawRoundRect(
                                color.copy(alpha = 0.9f),
                                topLeft = Offset(pos.x - cw * (scale - 1) / 2, pos.y - ch * (scale - 1) / 2),
                                size = Size(cw * scale, ch * scale),
                                cornerRadius = CornerRadius(6.dp.toPx()),
                            )
                        } else {
                            val flash = 1f - span(t, 0.55f, 0.9f)
                            drawRoundRect(Color.White.copy(alpha = 0.6f * flash), topLeft = target, size = Size(cw, ch), cornerRadius = CornerRadius(6.dp.toPx()))
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                val pop = span(t, 0.55f, 0.75f)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.graphicsLayer {
                        alpha = pop
                        scaleX = 0.8f + 0.2f * pop
                        scaleY = 0.8f + 0.2f * pop
                    },
                ) {
                    CText(loc["passport.r.piece"], ColituText.display, color = color, size = 34.sp, weight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    CText(
                        "${loc.countryName(stamp.code)} · ${levelName(level)} · ${loc.format("passport.pieces", "n" to filled)}",
                        ColituText.muted,
                        align = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(18.dp))
                CText(loc["passport.r.tap"], ColituText.small, color = ColituColors.dim, modifier = Modifier.alpha(span(t, 0.8f, 1f)))
            }
        }
    }
}

/**
 * A finished collection set: the seal drops onto the page with a thud, ink
 * rings spread, the screen shakes once; then the set's name and its reward.
 */
@Composable
private fun CollectionReveal(event: StampEvent.Collected, onDone: () -> Unit) {
    val loc = ColituLoc
    val context = LocalContext.current
    val view = LocalView.current
    val set = PassportCollection.entries.firstOrNull { it.id == event.code }
    if (set == null) {
        LaunchedEffect(event) { onDone() }
        return
    }
    val color = sealColor(set)
    val time = remember(event) { Animatable(0f) }
    var hit by remember(event) { mutableStateOf(false) }
    LaunchedEffect(event) {
        playSound(context, "passport_seal")
        withMotion { time.animateTo(1f, tween(2200, easing = LinearEasing)) }
    }
    val t = time.value
    if (t >= 0.34f && !hit) {
        hit = true
        passportHaptic(view, HapticFeedbackConstants.LONG_PRESS)
    }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val shake = if (t in 0.34f..0.5f) kotlin.math.sin(span(t, 0.34f, 0.5f) * 6 * Math.PI.toFloat()) * (1f - span(t, 0.34f, 0.5f)) * 10f else 0f
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF05040F).copy(alpha = 0.94f * span(t, 0f, 0.1f)))
                .graphicsLayer { translationX = shake * density },
            contentAlignment = Alignment.Center,
        ) {
            val ink = span(t, 0.34f, 0.8f)
            Canvas(Modifier.size(380.dp).alpha(if (ink > 0f) 1f - ink else 0f)) {
                val r = size.minDimension / 2
                drawCircle(color.copy(alpha = 0.5f), radius = r * (0.35f + 0.65f * ink), style = Stroke(3.dp.toPx()))
                drawCircle(color.copy(alpha = 0.3f), radius = r * (0.3f + 0.45f * ink), style = Stroke(6.dp.toPx()))
                // Ink drops around the seal.
                for (i in 0 until 10) {
                    val a = i * 36f * Math.PI.toFloat() / 180f
                    val d = r * (0.38f + 0.5f * ink)
                    drawCircle(color.copy(alpha = 0.6f), radius = (4f - 2f * ink).dp.toPx(), center = Offset(center.x + kotlin.math.cos(a) * d, center.y + kotlin.math.sin(a) * d))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 28.dp)) {
                // Falls from big and transparent to its size: the stamp hitting paper.
                val drop = FastOutSlowInEasing.transform(span(t, 0.08f, 0.34f))
                Box(
                    Modifier.size(220.dp).graphicsLayer {
                        val scale = 2.6f - 1.6f * drop
                        scaleX = scale
                        scaleY = scale
                        alpha = drop
                    },
                    contentAlignment = Alignment.Center,
                ) { PassportSeal(set, earned = true, size = 220.dp) }
                Spacer(Modifier.height(26.dp))
                val words = span(t, 0.55f, 0.75f)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(words)) {
                    CText(loc["passport.r.setDone"], ColituText.kicker, color = color)
                    Spacer(Modifier.height(8.dp))
                    CText(set.title.uppercase(), ColituText.display, size = 30.sp, weight = FontWeight.Bold, align = TextAlign.Center)
                    set.cover?.let {
                        Spacer(Modifier.height(6.dp))
                        CText(loc.format("passport.r.newCover", "cover" to coverName(it)), ColituText.muted, color = color, align = TextAlign.Center)
                    }
                }
                Spacer(Modifier.height(28.dp))
                val buttons = span(t, 0.8f, 1f)
                Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().navigationBarsPadding().alpha(buttons), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    set.cover?.let { cover ->
                        ColituButton(loc["passport.r.useCover"], if (buttons > 0.5f) ({ PassportGoals.updateCover(cover); onDone() }) else null)
                    }
                    ColituButton(loc["passport.r.great"], if (buttons > 0.5f) onDone else null, kind = ColituButtonKind.Secondary)
                }
            }
        }
    }
}

/**
 * An achievement: a card turns from its "???" back to the badge face, with
 * a few sparks; then the title it gives.
 */
@Composable
private fun AchievementReveal(event: StampEvent.Achieved, onDone: () -> Unit) {
    val loc = ColituLoc
    val context = LocalContext.current
    val view = LocalView.current
    val ach = PassportAchievement.entries.firstOrNull { it.id == event.code }
    if (ach == null) {
        LaunchedEffect(event) { onDone() }
        return
    }
    val time = remember(event) { Animatable(0f) }
    var flipped by remember(event) { mutableStateOf(false) }
    LaunchedEffect(event) {
        playSound(context, "passport_level")
        withMotion { time.animateTo(1f, tween(2200, easing = LinearEasing)) }
    }
    val t = time.value
    val turn = FastOutSlowInEasing.transform(span(t, 0.15f, 0.55f))
    if (turn >= 0.5f && !flipped) {
        flipped = true
        passportHaptic(view, if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
    }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier.fillMaxSize().background(Color(0xFF05040F).copy(alpha = 0.94f * span(t, 0f, 0.1f))),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 28.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    val spark = span(t, 0.5f, 0.9f)
                    Canvas(Modifier.size(300.dp).alpha(if (spark > 0f) 1f - spark else 0f)) {
                        for (i in 0 until 12) {
                            val a = i * 30f * Math.PI.toFloat() / 180f
                            val d = size.minDimension / 2 * (0.45f + 0.5f * spark)
                            val c = Offset(center.x + kotlin.math.cos(a) * d, center.y + kotlin.math.sin(a) * d)
                            val len = 6.dp.toPx()
                            drawLine(ColituColors.lilac, Offset(c.x - len, c.y), Offset(c.x + len, c.y), 1.5.dp.toPx())
                            drawLine(ColituColors.lilac, Offset(c.x, c.y - len), Offset(c.x, c.y + len), 1.5.dp.toPx())
                        }
                    }
                    val shape = RoundedCornerShape(22.dp)
                    Box(
                        Modifier
                            .size(width = 190.dp, height = 240.dp)
                            .graphicsLayer {
                                rotationY = 180f * (1f - turn)
                                cameraDistance = 14f * density
                                alpha = span(t, 0f, 0.15f)
                            }
                            .clip(shape)
                            .background(Brush.verticalGradient(listOf(Color(0xFF2A2468), Color(0xFF15133A))))
                            .border(1.5.dp, ColituColors.lilac.copy(alpha = 0.7f), shape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (turn < 0.5f) {
                            CText("???", ColituText.display, color = ColituColors.dim, size = 40.sp)
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                AchievementBadge(ach, earned = true, size = 96.dp)
                                Spacer(Modifier.height(16.dp))
                                CText(ach.title, ColituText.label, align = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(26.dp))
                val words = span(t, 0.6f, 0.8f)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(words)) {
                    CText(if (ach.secret) loc["passport.r.secretAch"] else loc["passport.r.ach"], ColituText.kicker, color = ColituColors.lilac)
                    Spacer(Modifier.height(8.dp))
                    CText(ach.title.uppercase(), ColituText.display, size = 28.sp, weight = FontWeight.Bold, align = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    CText(loc["passport.ach.${ach.id}"], ColituText.muted, align = TextAlign.Center)
                }
                Spacer(Modifier.height(28.dp))
                val buttons = span(t, 0.8f, 1f)
                Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().navigationBarsPadding().alpha(buttons), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ColituButton(loc["passport.ach.use"], if (buttons > 0.5f) ({ PassportGoals.updateTitle(ach.id); onDone() }) else null)
                    ColituButton(loc["passport.r.great"], if (buttons > 0.5f) onDone else null, kind = ColituButtonKind.Secondary)
                }
            }
        }
    }
}


/** A chapter opens: its name sweeps in over the dimmed page, then its stamps follow. */
@Composable
private fun ChapterReveal(event: StampEvent.Chapter, onDone: () -> Unit) {
    val loc = ColituLoc
    val context = LocalContext.current
    val view = LocalView.current
    val time = remember(event) { Animatable(0f) }
    var buzzed by remember(event) { mutableStateOf(false) }
    LaunchedEffect(event) {
        playSound(context, "passport_reveal")
        withMotion { time.animateTo(1f, tween(2200, easing = LinearEasing)) }
    }
    val t = time.value
    if (t >= 0.5f && !buzzed) {
        buzzed = true
        passportHaptic(view, if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
    }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF05040F).copy(alpha = 0.96f * span(t, 0f, 0.12f))), contentAlignment = Alignment.Center) {
            val glow = span(t, 0.2f, 0.7f)
            Canvas(Modifier.size(420.dp).alpha(glow * (1f - span(t, 0.8f, 1f) * 0.5f))) {
                drawCircle(Brush.radialGradient(listOf(ColituColors.lilac.copy(alpha = 0.35f), Color.Transparent)), radius = size.minDimension / 2 * (0.5f + glow * 0.5f))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 28.dp)) {
                val words = FastOutSlowInEasing.transform(span(t, 0.15f, 0.55f))
                CText(loc["passport.r.chapterOpen"], ColituText.kicker, color = ColituColors.lilac, modifier = Modifier.alpha(words))
                Spacer(Modifier.height(10.dp))
                CText(
                    loc["passport.ch${event.code}"].uppercase(),
                    ColituText.display,
                    size = 30.sp,
                    weight = FontWeight.Bold,
                    align = TextAlign.Center,
                    modifier = Modifier.graphicsLayer {
                        alpha = words
                        translationY = (1f - words) * 30.dp.toPx()
                    },
                )
                Spacer(Modifier.height(10.dp))
                CText(loc["passport.r.chapterBody${event.code}"], ColituText.muted, align = TextAlign.Center, modifier = Modifier.alpha(span(t, 0.55f, 0.75f)))
                Spacer(Modifier.height(28.dp))
                val buttons = span(t, 0.8f, 1f)
                Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().navigationBarsPadding().alpha(buttons)) {
                    ColituButton(loc["passport.r.great"], if (buttons > 0.5f) onDone else null)
                }
            }
        }
    }
}

/** A weekly expedition done: a visa stamp drops in with a light thud. Closes by itself. */
@Composable
private fun ExpeditionReveal(event: StampEvent.Expedition, onDone: () -> Unit) {
    val loc = ColituLoc
    val view = LocalView.current
    val goal = com.v2ray.ang.colitu.data.ExpeditionGoal.entries.firstOrNull { it.id == event.code }
    if (goal == null) {
        LaunchedEffect(event) { onDone() }
        return
    }
    val time = remember(event) { Animatable(0f) }
    var hit by remember(event) { mutableStateOf(false) }
    LaunchedEffect(event) {
        val start = System.currentTimeMillis()
        withMotion { time.animateTo(1f, tween(1600, easing = LinearEasing)) }
        delay((2_600 - (System.currentTimeMillis() - start)).coerceAtLeast(600))
        onDone()
    }
    val t = time.value
    if (t >= 0.4f && !hit) {
        hit = true
        passportHaptic(view, HapticFeedbackConstants.KEYBOARD_TAP)
    }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier.fillMaxSize().background(Color(0xFF05040F).copy(alpha = 0.86f * span(t, 0f, 0.1f)))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDone),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val drop = FastOutSlowInEasing.transform(span(t, 0.05f, 0.4f))
                Box(Modifier.graphicsLayer {
                    val scale = 2.2f - 1.2f * drop
                    scaleX = scale
                    scaleY = scale
                    alpha = drop
                }) { VisaStamp(goal, 220.dp) }
                Spacer(Modifier.height(24.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(span(t, 0.45f, 0.7f))) {
                    CText(loc["passport.r.expedition"], ColituText.kicker, color = ColituColors.lilac)
                    Spacer(Modifier.height(6.dp))
                    CText(loc["passport.x.${goal.id}"], ColituText.h2, align = TextAlign.Center)
                    Spacer(Modifier.height(4.dp))
                    CText(loc.format("passport.week.total", "n" to com.v2ray.ang.colitu.data.PassportWeekly.total), ColituText.small, color = ColituColors.lilac)
                }
            }
        }
    }
}

/**
 * The Companion stamp: drawn art (stamp_companion) when it ships, else two
 * linked holders in a perforated lilac frame.
 */
@Composable
internal fun CompanionStamp(earned: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    @Suppress("DiscouragedApi")
    val art = remember { context.resources.getIdentifier("stamp_companion", "drawable", context.packageName) }
    val dim = Modifier.alpha(if (earned) 1f else 0.35f)
    if (art != 0) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(art), "Companion", modifier.then(dim))
        return
    }
    val color = if (earned) ColituColors.lilac else ColituColors.dim
    Box(modifier.then(dim), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = size.minDimension * 0.1f
            val w = size.width - inset * 2
            val h = size.height - inset * 2
            drawRoundRect(Color(0xFF1B1840), topLeft = Offset(inset, inset), size = Size(w, h), cornerRadius = CornerRadius(4.dp.toPx()))
            drawRoundRect(color, topLeft = Offset(inset, inset), size = Size(w, h), cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(3.dp.toPx()))
            drawRoundRect(
                color.copy(alpha = 0.5f),
                topLeft = Offset(inset + 6.dp.toPx(), inset + 6.dp.toPx()),
                size = Size(w - 12.dp.toPx(), h - 12.dp.toPx()),
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = Stroke(1.dp.toPx()),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.foundation.layout.Row {
                ColituIcon(com.v2ray.ang.colitu.design.ColituIcons.Person, color, 22.dp)
                ColituIcon(com.v2ray.ang.colitu.design.ColituIcons.Person, color, 22.dp)
            }
            Spacer(Modifier.height(4.dp))
            CText("COMPANION", ColituText.kicker.copy(fontSize = 9.sp, letterSpacing = 1.sp), color = color)
        }
    }
}

/** The Companion stamp arrives: it drops in like a seal, then why you got it. */
@Composable
private fun SpecialReveal(event: StampEvent.Special, onDone: () -> Unit) {
    val loc = ColituLoc
    val context = LocalContext.current
    val view = LocalView.current
    val time = remember(event) { Animatable(0f) }
    var hit by remember(event) { mutableStateOf(false) }
    LaunchedEffect(event) {
        playSound(context, "passport_seal")
        withMotion { time.animateTo(1f, tween(2200, easing = LinearEasing)) }
    }
    val t = time.value
    if (t >= 0.34f && !hit) {
        hit = true
        passportHaptic(view, HapticFeedbackConstants.LONG_PRESS)
    }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF05040F).copy(alpha = 0.94f * span(t, 0f, 0.1f))), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 28.dp)) {
                val drop = FastOutSlowInEasing.transform(span(t, 0.08f, 0.34f))
                Box(Modifier.size(220.dp).graphicsLayer {
                    val scale = 2.4f - 1.4f * drop
                    scaleX = scale
                    scaleY = scale
                    alpha = drop
                }) { CompanionStamp(earned = true, modifier = Modifier.fillMaxSize()) }
                Spacer(Modifier.height(24.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(span(t, 0.5f, 0.75f))) {
                    CText(loc["passport.r.special"], ColituText.kicker, color = ColituColors.lilac)
                    Spacer(Modifier.height(8.dp))
                    CText("COMPANION", ColituText.display, size = 30.sp, weight = FontWeight.Bold, align = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    CText(loc["passport.companion.body"], ColituText.muted, align = TextAlign.Center)
                }
                Spacer(Modifier.height(28.dp))
                val buttons = span(t, 0.8f, 1f)
                Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().navigationBarsPadding().alpha(buttons)) {
                    ColituButton(loc["passport.r.great"], if (buttons > 0.5f) onDone else null)
                }
            }
        }
    }
}
