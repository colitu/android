package com.v2ray.ang.colitu.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.input.ImeAction
import com.v2ray.ang.colitu.design.ColituIconButton
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.v2ray.ang.R
import com.v2ray.ang.colitu.data.ColituPassport
import com.v2ray.ang.colitu.data.CoverTheme
import com.v2ray.ang.colitu.data.PassportAchievement
import com.v2ray.ang.colitu.data.PassportCollection
import com.v2ray.ang.colitu.data.PassportGoals
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituButton
import com.v2ray.ang.colitu.design.ColituChip
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituField
import com.v2ray.ang.colitu.design.ColituFlag
import com.v2ray.ang.colitu.design.ColituIcon
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituLinkButton
import com.v2ray.ang.colitu.design.ColituPanel
import com.v2ray.ang.colitu.design.ColituRadius
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.pressable
import com.v2ray.ang.colitu.l10n.ColituLoc
import java.time.Instant

// ── Covers ────────────────────────────────────────────────────────────────

/** Cover tints until each theme's drawn art ships (passport_cover_<key>). */
internal fun coverMatrix(theme: CoverTheme): FloatArray? = when (theme) {
    CoverTheme.Classic -> null
    CoverTheme.Premium -> premiumCoverMatrix()
    CoverTheme.Nordic -> coverTone(0.55f, 1.12f, 1.18f, 0f, 30f, 40f)
    CoverTheme.Baltic -> coverTone(1.28f, 0.86f, 0.4f, 44f, 20f, 0f)
    CoverTheme.Atlantic -> coverTone(0.42f, 0.78f, 1.38f, 0f, 18f, 52f)
    CoverTheme.Pacific -> coverTone(1.3f, 0.72f, 0.82f, 46f, 4f, 22f)
}

private fun coverTone(mr: Float, mg: Float, mb: Float, or: Float, og: Float, ob: Float) = floatArrayOf(
    .3f * mr, .59f * mr, .11f * mr, 0f, or,
    .3f * mg, .59f * mg, .11f * mg, 0f, og,
    .3f * mb, .59f * mb, .11f * mb, 0f, ob,
    0f, 0f, 0f, 1f, 0f,
)

internal fun coverName(theme: CoverTheme) = ColituLoc["passport.cover.${theme.key}"]

/** The passport cover in [theme]; drawn art when it ships, else the classic cover tinted. */
@Composable
internal fun PassportCover(theme: CoverTheme, modifier: Modifier) {
    val context = LocalContext.current
    @Suppress("DiscouragedApi")
    val art = remember(theme) { context.resources.getIdentifier("passport_cover_${theme.key}", "drawable", context.packageName) }
    val matrix = if (art == 0) coverMatrix(theme) else null
    Image(
        painterResource(if (art != 0) art else R.drawable.passport_cover),
        null,
        modifier.aspectRatio(720f / 900f),
        colorFilter = matrix?.let { ColorFilter.colorMatrix(ColorMatrix(it)) },
    )
}

// ── Seals ─────────────────────────────────────────────────────────────────

internal fun sealColor(c: PassportCollection): Color = when (c) {
    PassportCollection.Nordic -> Color(0xFF7FD6E0)
    PassportCollection.Baltic -> Color(0xFFE8B067)
    PassportCollection.Atlantic -> Color(0xFF8FB2FF)
    PassportCollection.Heart -> Color(0xFFC4B5FD)
    PassportCollection.Pacific -> Color(0xFFFFA98A)
    PassportCollection.GoldRush -> Color(0xFFF2C76E)
}

/**
 * Ink seal of a collection: double ring (the inner one dashed), the set's
 * name and year, slightly turned like a hand stamp. Grey outline until earned.
 */
@Composable
internal fun PassportSeal(c: PassportCollection, earned: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    @Suppress("DiscouragedApi")
    val art = remember(c) { context.resources.getIdentifier("seal_${c.id}", "drawable", context.packageName) }
    // Drawn seal art when it ships; a hidden set stays "?" until earned.
    if (art != 0 && (earned || !c.secret)) {
        Image(
            painterResource(art),
            c.title,
            modifier.size(size).alpha(if (earned) 1f else 0.4f),
            colorFilter = if (earned) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
        )
        return
    }
    val color = if (earned) sealColor(c) else ColituColors.dim
    val year = PassportGoals.earnedAt("c_${c.id}").takeIf { it > 0 }
        ?.let { Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).year.toString() }
    Box(modifier.size(size).rotate(-12f).alpha(if (earned) 0.95f else 0.6f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2
            drawCircle(color, radius = r - 2.dp.toPx(), style = Stroke(2.5.dp.toPx()))
            drawCircle(
                color,
                radius = r - 7.dp.toPx(),
                style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))),
            )
        }
        if (earned || !c.secret) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ColituIcon(ColituIcons.Star, color, size * 0.16f)
                CText(
                    c.title.uppercase(),
                    ColituText.kicker.copy(fontSize = (size.value * 0.095f).sp, letterSpacing = 0.6.sp, fontWeight = FontWeight.Bold),
                    color = color,
                    align = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = size * 0.14f),
                )
                if (year != null) CText(year, ColituText.small.copy(fontSize = (size.value * 0.085f).sp), color = color)
            }
        } else {
            CText("?", ColituText.h1, color = color)
        }
    }
}

// ── Collections tab ───────────────────────────────────────────────────────

@Composable
internal fun CollectionsTab() {
    val loc = ColituLoc
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PassportCollection.entries.forEach { c ->
            val (have, need) = PassportGoals.progress(c)
            val earned = PassportGoals.completed(c)
            val hidden = c.secret && !earned
            ColituPanel(padding = PaddingValues(14.dp), radius = ColituRadius.md, glow = earned) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PassportSeal(c, earned, 72.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CText(if (hidden) "???" else c.title, ColituText.label, Modifier.weight(1f), maxLines = 1)
                            CText("$have/$need", ColituText.label, color = if (earned) sealColor(c) else ColituColors.muted)
                        }
                        Spacer(Modifier.height(4.dp))
                        when {
                            hidden -> CText(loc["passport.set.secret"], ColituText.small)
                            c == PassportCollection.GoldRush -> CText(loc["passport.set.goldRush"], ColituText.small)
                            else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                c.codes.forEach { code ->
                                    val has = ColituPassport.unlockedAnywhere(code)
                                    Box(Modifier.alpha(if (has) 1f else 0.3f)) { ColituFlag(code, 22.dp) }
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        val reward = when {
                            earned -> loc.format("passport.set.done", "date" to loc.date(Instant.ofEpochMilli(PassportGoals.earnedAt("c_${c.id}"))))
                            c.cover != null -> loc.format("passport.set.rewardCover", "cover" to coverName(c.cover))
                            else -> loc["passport.set.rewardSeal"]
                        }
                        CText(reward, ColituText.small, color = if (earned) sealColor(c) else ColituColors.dim, size = 11.5.sp)
                    }
                }
            }
        }
        CText(loc["passport.set.nextChapter"], ColituText.small, Modifier.fillMaxWidth(), align = TextAlign.Center)
    }
}

// ── Achievements tab ──────────────────────────────────────────────────────

internal fun achievementIcon(a: PassportAchievement): ImageVector = when (a) {
    PassportAchievement.First -> ColituIcons.Star
    PassportAchievement.Globetrotter -> ColituIcons.Globe
    PassportAchievement.Origin -> ColituIcons.SealCheck
    PassportAchievement.NordicMaster -> ColituIcons.ShieldCheck
    PassportAchievement.Night -> ColituIcons.Eye
    PassportAchievement.Early -> ColituIcons.Bolt
    PassportAchievement.LongHaul -> ColituIcons.Power
    PassportAchievement.Diamond -> ColituIcons.Star
    PassportAchievement.Pathfinder -> ColituIcons.ArrowUpRight
    PassportAchievement.LegacyGold -> ColituIcons.SealCheck
    PassportAchievement.LegacyDiamond -> ColituIcons.SealCheck
}

@Composable
internal fun AchievementBadge(a: PassportAchievement, earned: Boolean, size: Dp) {
    val hidden = a.secret && !earned
    val context = LocalContext.current
    @Suppress("DiscouragedApi")
    val art = remember(a) { context.resources.getIdentifier("badge_${a.id}", "drawable", context.packageName) }
    if (art != 0 && !hidden) {
        Image(
            painterResource(art),
            a.title,
            Modifier.size(size).alpha(if (earned) 1f else 0.35f),
            colorFilter = if (earned) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
        )
        return
    }
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (earned) Color(0x339F8CFF) else ColituColors.surface2)
            .border(1.5.dp, if (earned) ColituColors.lilac else ColituColors.lineStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (hidden) {
            CText("?", ColituText.h2, color = ColituColors.dim)
        } else {
            ColituIcon(achievementIcon(a), if (earned) ColituColors.lilac else ColituColors.dim, size * 0.46f)
        }
    }
}

@Composable
internal fun AchievementsTab() {
    val loc = ColituLoc
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PassportAchievement.entries.forEach { a ->
            val earned = PassportGoals.achieved(a)
            val hidden = a.secret && !earned
            val inUse = PassportGoals.title == a.id
            ColituPanel(padding = PaddingValues(14.dp), radius = ColituRadius.md, glow = inUse) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AchievementBadge(a, earned, 48.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        CText(if (hidden) "???" else a.title, ColituText.label, maxLines = 1, color = if (earned) ColituColors.text else ColituColors.muted)
                        Spacer(Modifier.height(2.dp))
                        CText(if (hidden) loc["passport.ach.secret"] else loc["passport.ach.${a.id}"], ColituText.small, maxLines = 2)
                        if (earned) {
                            Spacer(Modifier.height(8.dp))
                            ColituChip(if (inUse) loc["passport.ach.inUse"] else loc["passport.ach.use"], inUse) {
                                PassportGoals.updateTitle(if (inUse) null else a.id)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Identity sheet ────────────────────────────────────────────────────────

/**
 * Passport editor, full screen: a live preview (cover and the holder page)
 * on top, then the cover, title, favourite stamp and app icon choices. Locked
 * choices stay visible with what opens them. The page scrolls and keeps
 * clear of the status bar and the keyboard.
 */
@Composable
internal fun IdentitySheet(premium: Boolean, onDismiss: () -> Unit) {
    val loc = ColituLoc
    val context = LocalContext.current
    var nick by remember { mutableStateOf(PassportGoals.nickname) }
    val close = {
        if (nick.trim() != PassportGoals.nickname) PassportGoals.updateNickname(nick)
        onDismiss()
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0A22))) {
            Image(painterResource(R.drawable.passport_bg), null, Modifier.matchParentSize().alpha(0.85f), contentScale = ContentScale.Crop)
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ColituIconButton(ColituIcons.XMark, close, size = 40.dp, description = loc["passport.close"])
                    CText(loc["passport.id.edit"], ColituText.label, Modifier.weight(1f), align = TextAlign.Center)
                    Box(Modifier.size(40.dp))
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(6.dp))
                    PassportCover(PassportGoals.activeCover(premium), Modifier.height(170.dp))
                    Spacer(Modifier.height(18.dp))
                    HolderPage(nick)
                    Spacer(Modifier.height(22.dp))

                    EditorSection(loc["passport.id.nick"]) {
                        ColituField(nick, { nick = it.take(20) }, null, hint = loc["passport.id.nickHint"], imeAction = ImeAction.Done, onSubmit = {
                            PassportGoals.updateNickname(nick)
                        })
                    }

                    EditorSection(loc["passport.id.cover"]) {
                        val active = PassportGoals.activeCover(premium)
                        val open = PassportGoals.covers(premium)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CoverTheme.entries.forEach { theme ->
                                val unlocked = theme in open
                                val selected = theme == active
                                Column(
                                    Modifier.width(96.dp).pressable(if (unlocked) ({ PassportGoals.updateCover(theme) }) else null),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(if (selected) Color(0x339F8CFF) else Color(0x10FFFFFF))
                                            .border(if (selected) 2.dp else 1.dp, if (selected) ColituColors.lilac else ColituColors.line, RoundedCornerShape(16.dp))
                                            .padding(10.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        PassportCover(theme, Modifier.height(84.dp).alpha(if (unlocked) 1f else 0.35f))
                                        if (!unlocked) ColituIcon(ColituIcons.Lock, ColituColors.text, 18.dp)
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    CText(coverName(theme), ColituText.small, color = if (selected) ColituColors.lilac else ColituColors.text, size = 12.sp)
                                    if (!unlocked) CText(coverRequirement(theme), ColituText.small, color = ColituColors.dim, size = 10.5.sp, align = TextAlign.Center, maxLines = 2)
                                }
                            }
                        }
                    }

                    EditorSection(loc["passport.id.title"]) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ColituChip(loc["passport.id.noTitle"], PassportGoals.title == null) { PassportGoals.updateTitle(null) }
                            PassportAchievement.entries.forEach { a ->
                                val earned = PassportGoals.achieved(a)
                                if (earned) {
                                    ColituChip(a.title, PassportGoals.title == a.id) { PassportGoals.updateTitle(a.id) }
                                } else {
                                    LockedChip(if (a.secret) "???" else a.title)
                                }
                            }
                        }
                    }

                    EditorSection(loc["passport.id.favorite"]) {
                        val unlocked = ColituPassport.allStamps.filter { it.code in ColituPassport.progress }
                        if (unlocked.isEmpty()) {
                            CText(loc["passport.id.noStamps"], ColituText.small)
                        } else {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                unlocked.forEach { s ->
                                    val selected = PassportGoals.favorite == s.code
                                    Box(
                                        Modifier
                                            .size(78.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(if (selected) Color(0x339F8CFF) else Color(0x10FFFFFF))
                                            .border(if (selected) 2.dp else 1.dp, if (selected) ColituColors.lilac else ColituColors.line, RoundedCornerShape(14.dp))
                                            .pressable({ PassportGoals.updateFavorite(if (selected) null else s.code) })
                                            .padding(6.dp),
                                    ) { StampArt(s, ColituPassport.progressOf(s.code), Modifier.fillMaxSize(), lockSize = 0.dp) }
                                }
                            }
                        }
                    }

                    EditorSection(loc["passport.id.icon"]) {
                        var chosen by remember { mutableStateOf(com.v2ray.ang.colitu.app.ColituAppIcon.chosen(context)) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            com.v2ray.ang.colitu.app.AppIcon.entries.forEach { icon ->
                                AppIconChoice(icon, selected = icon == chosen, unlocked = iconUnlocked(icon)) {
                                    chosen = icon
                                    com.v2ray.ang.colitu.app.ColituAppIcon.request(icon)
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        CText(loc["passport.id.iconNote"], ColituText.small, color = ColituColors.dim, size = 11.5.sp)
                    }
                    Spacer(Modifier.height(24.dp))
                }
                // Hidden while the keyboard is up: it would cover the name field (the keyboard's Done saves).
                if (WindowInsets.ime.getBottom(LocalDensity.current) == 0) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                        ColituButton(loc["passport.id.save"], close)
                    }
                }
            }
        }
    }
}

/** The holder page of the passport: name, title, collector number and favourite stamp. */
@Composable
private fun HolderPage(nick: String) {
    val loc = ColituLoc
    val shape = RoundedCornerShape(ColituRadius.md)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0x269F8CFF), Color(0x0F9F8CFF))))
            .border(1.dp, Color(0x339F8CFF), shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            CText(loc["passport.id.holder"], ColituText.kicker, color = ColituColors.dim)
            CText(nick.ifBlank { "—" }, ColituText.h2, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            CText(loc["passport.id.title"].uppercase(), ColituText.kicker, color = ColituColors.dim)
            CText(PassportGoals.titleOf(PassportGoals.title) ?: loc["passport.id.noTitle"], ColituText.label, color = ColituColors.lilac, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            CText(loc.format("passport.id.collector", "n" to PassportGoals.collectorNo), ColituText.small, color = ColituColors.muted)
        }
        val fav = PassportGoals.favorite?.let { ColituPassport.stampOf(it) }
        if (fav != null) {
            Spacer(Modifier.width(12.dp))
            StampArt(fav, ColituPassport.progressOf(fav.code), Modifier.size(84.dp), lockSize = 0.dp)
        }
    }
}

@Composable
private fun EditorSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
        CText(title, ColituText.label)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun LockedChip(label: String) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape).border(1.dp, ColituColors.line, shape).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColituIcon(ColituIcons.Lock, ColituColors.dim, 12.dp)
        Spacer(Modifier.width(6.dp))
        CText(label, ColituText.small, color = ColituColors.dim)
    }
}

/** What opens a locked cover. */
private fun coverRequirement(theme: CoverTheme): String {
    val loc = ColituLoc
    if (theme == CoverTheme.Premium) return loc["passport.req.premium"]
    val set = PassportCollection.entries.firstOrNull { it.cover == theme } ?: return ""
    return loc.format("passport.req.set", "set" to set.title)
}

private fun iconUnlocked(icon: com.v2ray.ang.colitu.app.AppIcon): Boolean = when (icon) {
    com.v2ray.ang.colitu.app.AppIcon.Aurora -> PassportGoals.completed(PassportCollection.Nordic)
    com.v2ray.ang.colitu.app.AppIcon.Gold -> PassportGoals.completed(PassportCollection.GoldRush)
    com.v2ray.ang.colitu.app.AppIcon.Diamond -> PassportGoals.achieved(PassportAchievement.Diamond)
    com.v2ray.ang.colitu.app.AppIcon.Sunset -> PassportGoals.completed(PassportCollection.Pacific)
    com.v2ray.ang.colitu.app.AppIcon.Stamp -> PassportGoals.achieved(PassportAchievement.Origin)
    else -> true
}

private fun iconRequirement(icon: com.v2ray.ang.colitu.app.AppIcon): String = when (icon) {
    com.v2ray.ang.colitu.app.AppIcon.Aurora -> ColituLoc.format("passport.req.set", "set" to PassportCollection.Nordic.title)
    com.v2ray.ang.colitu.app.AppIcon.Gold -> ColituLoc.format("passport.req.set", "set" to PassportCollection.GoldRush.title)
    com.v2ray.ang.colitu.app.AppIcon.Diamond -> PassportAchievement.Diamond.title
    com.v2ray.ang.colitu.app.AppIcon.Sunset -> ColituLoc.format("passport.req.set", "set" to PassportCollection.Pacific.title)
    com.v2ray.ang.colitu.app.AppIcon.Stamp -> PassportAchievement.Origin.title
    else -> ""
}

/** One launcher icon to pick: its art in a rounded tile, its name, and what opens it when locked. */
@Composable
private fun AppIconChoice(icon: com.v2ray.ang.colitu.app.AppIcon, selected: Boolean, unlocked: Boolean, onPick: () -> Unit) {
    Column(Modifier.width(76.dp).pressable(if (unlocked) onPick else null), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(68.dp)
                .border(if (selected) 2.dp else 0.dp, if (selected) ColituColors.lilac else Color.Transparent, RoundedCornerShape(20.dp))
                .padding(4.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(icon.preview), null, Modifier.fillMaxSize().alpha(if (unlocked) 1f else 0.3f), contentScale = ContentScale.Crop)
            if (!unlocked) ColituIcon(ColituIcons.Lock, ColituColors.text, 18.dp)
        }
        Spacer(Modifier.height(6.dp))
        CText(ColituLoc["passport.icon.${icon.key}"], ColituText.small, color = if (selected) ColituColors.lilac else ColituColors.text, size = 12.sp, maxLines = 1)
        if (!unlocked) CText(iconRequirement(icon), ColituText.small, color = ColituColors.dim, size = 10.sp, align = TextAlign.Center, maxLines = 2)
    }
}

/** "Night Explorer · Collector #048213", or the nickname first when set. */
internal fun identityLine(): String {
    val loc = ColituLoc
    return listOfNotNull(
        PassportGoals.nickname.takeIf { it.isNotBlank() },
        PassportGoals.titleOf(PassportGoals.title),
        loc.format("passport.id.collector", "n" to PassportGoals.collectorNo),
    ).joinToString(" · ")
}
