package com.v2ray.ang.colitu.screens

import androidx.compose.ui.graphics.graphicsLayer
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.v2ray.ang.R
import com.v2ray.ang.colitu.app.ColituController
import com.v2ray.ang.colitu.data.ColituPassport
import com.v2ray.ang.colitu.data.ColituStamp
import com.v2ray.ang.colitu.data.PassportAchievement
import com.v2ray.ang.colitu.data.PassportCollection
import com.v2ray.ang.colitu.data.PassportGoals
import com.v2ray.ang.colitu.data.PassportWeekly
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
import com.v2ray.ang.colitu.design.ColituRadius
import com.v2ray.ang.colitu.design.ColituSwitch
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.pressable
import com.v2ray.ang.colitu.l10n.ColituLoc

/** The four places a passport tile or button opens. */
private enum class PassportPanel { Next, Weekly, Sets, Badges, How }

/**
 * Passport page, kept calm: the cover and the count, one "next up" goal,
 * three small tiles (expeditions, collections, achievements) that open their
 * own sheets, then the stamps of the chosen chapter. Rules and settings live
 * behind the info button. The VPN service earns stamps and pieces
 * ([ColituPassport.tick]); the shell re-reads them.
 */
@Composable
fun PassportScreen(c: ColituController, onBack: () -> Unit, onConnect: () -> Unit) {
    val loc = ColituLoc
    var chapter by rememberSaveable { mutableStateOf(1) }
    var openCode by rememberSaveable { mutableStateOf<String?>(null) }
    var panel by rememberSaveable { mutableStateOf<PassportPanel?>(null) }
    var identityOpen by rememberSaveable { mutableStateOf(false) }
    var duelOpen by rememberSaveable { mutableStateOf(false) }
    var introOpen by rememberSaveable { mutableStateOf(!com.v2ray.ang.colitu.data.PassportSocial.introSeen) }
    BackHandler { onBack() }

    Box(Modifier.fillMaxSize()) {
        Image(painterResource(R.drawable.passport_bg), null, Modifier.matchParentSize().alpha(0.9f), contentScale = ContentScale.Crop)
        ShellScroll {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColituIconButton(ColituIcons.ChevronLeft, onBack, size = 40.dp, description = loc["passport.close"])
                CText(loc["passport.title"], ColituText.label, Modifier.weight(1f), align = TextAlign.Center)
                ColituIconButton(ColituIcons.QrScan, { duelOpen = true }, size = 40.dp, description = loc["passport.duel.title"])
                Spacer(Modifier.width(8.dp))
                ColituIconButton(ColituIcons.Info, { panel = PassportPanel.How }, size = 40.dp, description = loc["passport.how"])
            }
            Spacer(Modifier.height(18.dp))
            PassportHero(c.planActive) { identityOpen = true }
            Spacer(Modifier.height(24.dp))
            if (ColituPassport.count == 0) {
                FirstSteps(if (!c.connected && !c.busy) onConnect else null)
            } else {
                NextUpCard { panel = PassportPanel.Next }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val week = PassportWeekly.goals()
                StatTile(ColituIcons.Send, "${week.count { PassportWeekly.done(it) }}/${week.size}", loc["passport.tile.week"], Modifier.weight(1f)) { panel = PassportPanel.Weekly }
                StatTile(
                    ColituIcons.Grid,
                    "${PassportCollection.entries.count { PassportGoals.completed(it) }}/${PassportCollection.entries.size}",
                    loc["passport.tile.sets"],
                    Modifier.weight(1f),
                ) { panel = PassportPanel.Sets }
                StatTile(
                    ColituIcons.SealCheck,
                    "${PassportAchievement.entries.count { PassportGoals.achieved(it) }}/${PassportAchievement.entries.size}",
                    loc["passport.tile.badges"],
                    Modifier.weight(1f),
                ) { panel = PassportPanel.Badges }
            }
            Spacer(Modifier.height(30.dp))
            ChapterTabs(chapter) { chapter = it }
            Spacer(Modifier.height(18.dp))
            when (chapter) {
                3 -> LegacyChapter()
                else -> {
                    val list = if (chapter == 2) ColituPassport.chapter2 else ColituPassport.stamps
                    if (chapter == 2 && !ColituPassport.chapter2Open) {
                        CText(loc["passport.ch2.locked"], ColituText.small, Modifier.fillMaxWidth(), align = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                    }
                    CleanStampGrid(list) { openCode = it.code }
                }
            }
            SpecialStamps()
            Spacer(Modifier.height(26.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                ColituIcon(ColituIcons.Lock, ColituColors.dim, 12.dp)
                Spacer(Modifier.width(6.dp))
                CText(loc["passport.local"], ColituText.small, color = ColituColors.dim, size = 11.5.sp)
            }
        }
    }

    when (panel) {
        PassportPanel.Next -> PanelSheet(loc["passport.nx.title"], { panel = null }) {
            NextGoalDetail(c) { server ->
                panel = null
                if (server != null) c.selectServer(server)
                if (!c.connected && !c.busy) onConnect()
            }
        }
        PassportPanel.Weekly -> PanelSheet(loc["passport.week.title"], { panel = null }) { WeeklyCard() }
        PassportPanel.Sets -> PanelSheet(loc["passport.tab.sets"], { panel = null }) { CollectionsTab() }
        PassportPanel.Badges -> PanelSheet(loc["passport.tab.badges"], { panel = null }) { AchievementsTab() }
        PassportPanel.How -> PanelSheet(loc["passport.how"], { panel = null }) { HowItWorks() }
        null -> Unit
    }
    if (identityOpen) IdentitySheet(c.planActive) { identityOpen = false }
    if (introOpen) {
        PassportIntro(c.planActive) {
            com.v2ray.ang.colitu.data.PassportSocial.introSeen = true
            introOpen = false
        }
    }
    if (duelOpen) DuelDialog { duelOpen = false }
    openCode?.let { code ->
        val stamp = ColituPassport.allStamps.first { it.code == code }
        StampSheet(
            c,
            stamp,
            onDismiss = { openCode = null },
            onConnectTo = { server ->
                openCode = null
                c.selectServer(server)
                if (!c.connected && !c.busy) onConnect()
            },
        )
    }
}

/** Big cover, the count and the holder line; a tap edits the passport. */
@Composable
private fun PassportHero(premium: Boolean, onEdit: () -> Unit) {
    val loc = ColituLoc
    val count = ColituPassport.count
    val total = ColituPassport.stamps.size
    val progress by animateFloatAsState(count / total.toFloat(), tween(700), label = "passport")
    val coverHeight = if (LocalConfiguration.current.screenHeightDp < 700) 170.dp else 210.dp
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            // Test build: a long press fills a demo passport, a second one empties it.
            Modifier.pointerInput(Unit) { detectTapGestures(onTap = { onEdit() }, onLongPress = { ColituPassport.debugCycle() }) },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(coverHeight * 1.25f)) {
                drawCircle(Brush.radialGradient(listOf(Color(0x559F8CFF), Color.Transparent)), radius = size.minDimension / 2)
            }
            PassportCover(PassportGoals.activeCover(premium), Modifier.height(coverHeight))
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            CText("$count", ColituText.display, color = ColituColors.lilac, size = 40.sp, weight = FontWeight.Bold)
            CText(" / $total", ColituText.display, color = ColituColors.muted, size = 40.sp, weight = FontWeight.Bold)
        }
        CText(loc["passport.countries"], ColituText.small)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.width(160.dp).height(4.dp).clip(RoundedCornerShape(50)).background(ColituColors.glass10)) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().clip(RoundedCornerShape(50)).background(ColituGradients.accent))
        }
        Spacer(Modifier.height(10.dp))
        CText(identityLine(), ColituText.small, color = ColituColors.lilac, maxLines = 1, align = TextAlign.Center, size = 12.sp)
    }
}

/** The nearest reward and how close it is, as a ring. */
private enum class GoalKind { Today, Level, Done }

private data class NextGoal(
    val text: String,
    val fraction: Float,
    val icon: ImageVector,
    val kind: GoalKind = GoalKind.Done,
    val code: String? = null,
    val target: String? = null,
    val left: Int = 0,
)

private fun nextGoal(): NextGoal {
    val loc = ColituLoc
    val (minutes, pieces) = ColituPassport.today
    if (!ColituPassport.tester && pieces < ColituPassport.PIECE_MINUTES.size) {
        val next = ColituPassport.PIECE_MINUTES[pieces]
        if (minutes < next) {
            return NextGoal(loc.format("passport.goal.today", "n" to next - minutes), minutes / next.toFloat(), ColituIcons.Star, GoalKind.Today, left = next - minutes)
        }
    }
    val (code, best) = ColituPassport.progress.entries.filter { !it.value.mastered }.minByOrNull { it.value.toNext }?.toPair()
        ?: return NextGoal(loc["passport.goal.done"], 1f, ColituIcons.Check)
    val level = best.level ?: return NextGoal(loc["passport.goal.done"], 1f, ColituIcons.Check)
    val target = if (level == StampLevel.Diamond) levelName(level) else levelName(StampLevel.entries[level.ordinal + 1])
    return NextGoal(
        loc.format("passport.goal.level", "country" to loc.countryName(code), "level" to target, "n" to best.toNext),
        best.levelPieces / StampProgress.PIECES.toFloat(),
        ColituIcons.ArrowUp,
        GoalKind.Level,
        code,
        target,
        best.toNext,
    )
}

@Composable
private fun NextUpCard(onOpen: () -> Unit) {
    val goal = nextGoal()
    val fraction by animateFloatAsState(goal.fraction, tween(700), label = "next")
    GlassCard(onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 4.dp.toPx()
                    val inset = stroke / 2
                    drawArc(ColituColors.glass10, 0f, 360f, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                    drawArc(
                        Brush.sweepGradient(listOf(Color(0xFFB4A4FF), Color(0xFF8B78FF), Color(0xFFB4A4FF))),
                        -90f,
                        360f * fraction,
                        false,
                        Offset(inset, inset),
                        Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                ColituIcon(goal.icon, ColituColors.lilac, 18.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                CText(ColituLoc["passport.next"], ColituText.kicker, color = ColituColors.lilac)
                Spacer(Modifier.height(3.dp))
                CText(goal.text, ColituText.label, maxLines = 2)
            }
            ColituIcon(ColituIcons.ChevronRight, ColituColors.muted, 16.dp)
        }
    }
}

/** The next goal explained: what it is, how to reach it, today's numbers and a way to act. */
@Composable
private fun NextGoalDetail(c: ColituController, onGo: (com.v2ray.ang.colitu.data.ColituServer?) -> Unit) {
    val loc = ColituLoc
    val goal = nextGoal()
    val (minutes, pieces) = ColituPassport.today
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(110.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 7.dp.toPx()
                val inset = stroke / 2
                drawArc(ColituColors.glass10, 0f, 360f, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                drawArc(
                    Brush.sweepGradient(listOf(Color(0xFFB4A4FF), Color(0xFF8B78FF), Color(0xFFB4A4FF))),
                    -90f,
                    360f * goal.fraction,
                    false,
                    Offset(inset, inset),
                    Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            val stamp = goal.code?.takeIf { goal.kind == GoalKind.Level }?.let { ColituPassport.stampOf(it) }
            if (stamp != null) {
                StampArt(stamp, ColituPassport.progressOf(stamp.code), Modifier.size(70.dp), lockSize = 0.dp)
            } else {
                ColituIcon(goal.icon, ColituColors.lilac, 30.dp)
            }
        }
        Spacer(Modifier.height(16.dp))
        CText(goal.text, ColituText.h2, align = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        val country = goal.code?.let { loc.countryName(it) }
        val body = when (goal.kind) {
            GoalKind.Today -> loc["passport.nx.today"]
            GoalKind.Level -> loc.format("passport.nx.level", "country" to country, "level" to goal.target, "n" to goal.left)
            GoalKind.Done -> loc["passport.nx.done"]
        }
        CText(body, ColituText.muted, align = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GoalStat(loc["passport.nx.minutes"], "$minutes", Modifier.weight(1f))
            GoalStat(loc["passport.nx.pieces"], "$pieces/${ColituPassport.PIECE_MINUTES.size}", Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))
        val server = goal.code?.let { code ->
            c.rankedServers.firstOrNull { !it.isMultihop && it.isAvailable && ColituPassport.stampFor(it.countryCode)?.code == code }
        }
        val onThatCountry = c.connected && c.connectedServer?.let { ColituPassport.stampFor(it.countryCode)?.code } == goal.code
        when {
            goal.kind == GoalKind.Level && server != null && !onThatCountry ->
                ColituButton(loc.format("passport.connectTo", "country" to country), { onGo(server) })
            !c.connected && goal.kind != GoalKind.Done -> ColituButton(loc["home.connect"], if (c.busy) null else ({ onGo(null) }))
            c.connected -> CText(loc["passport.nx.counting"], ColituText.small, color = ColituColors.lilac, align = TextAlign.Center)
        }
    }
}

@Composable
private fun GoalStat(label: String, value: String, modifier: Modifier) {
    val shape = RoundedCornerShape(ColituRadius.md)
    Column(
        modifier.clip(shape).background(Color(0x14FFFFFF)).border(1.dp, ColituColors.line, shape).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CText(value, ColituText.h2)
        CText(label, ColituText.small, size = 11.5.sp)
    }
}

/** First visit: the whole game in three steps, and a way to start. */
@Composable
private fun FirstSteps(onConnect: (() -> Unit)?) {
    val loc = ColituLoc
    GlassCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth()) {
                listOf(
                    Triple(ColituIcons.Power, "passport.step1", "passport.step1b"),
                    Triple(ColituIcons.Star, "passport.step2", "passport.step2b"),
                    Triple(ColituIcons.ArrowUp, "passport.step3", "passport.step3b"),
                ).forEach { (icon, top, bottom) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0x269F8CFF)), contentAlignment = Alignment.Center) {
                            ColituIcon(icon, ColituColors.lilac, 18.dp)
                        }
                        Spacer(Modifier.height(8.dp))
                        CText(loc[top], ColituText.small, color = ColituColors.text, align = TextAlign.Center, size = 12.sp)
                        CText(loc[bottom], ColituText.small, color = ColituColors.lilac, align = TextAlign.Center, size = 12.sp)
                    }
                }
            }
            if (onConnect != null) {
                Spacer(Modifier.height(16.dp))
                ColituButton(ColituLoc["home.connect"], onConnect, Modifier.widthIn(min = 200.dp), expand = false, height = 46.dp)
            }
        }
    }
}

@Composable
private fun StatTile(icon: ImageVector, value: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(ColituRadius.md)
    Column(
        modifier
            .pressable(onClick)
            .clip(shape)
            .background(Color(0x14FFFFFF))
            .border(1.dp, ColituColors.line, shape)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ColituIcon(icon, ColituColors.lilac, 18.dp)
        Spacer(Modifier.height(6.dp))
        CText(value, ColituText.label)
        CText(label, ColituText.small, size = 11.5.sp, maxLines = 1)
    }
}

/** Soft glass card used on this page (lighter than the app's panels). */
@Composable
private fun GlassCard(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(ColituRadius.lg)
    Box(
        Modifier
            .fillMaxWidth()
            .pressable(onClick)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0x229F8CFF), Color(0x0F9F8CFF))))
            .border(1.dp, Color(0x339F8CFF), shape)
            .padding(16.dp),
    ) { content() }
}

/** Chapter names as quiet text tabs with an underline; closed ones are dimmed. */
@Composable
private fun ChapterTabs(chapter: Int, onChange: (Int) -> Unit) {
    val loc = ColituLoc
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf(1, 2, 3).forEach { n ->
            val open = when (n) {
                1 -> true
                2 -> ColituPassport.chapter2Open
                else -> PassportGoals.chapter3Open
            }
            val active = chapter == n
            Column(Modifier.pressable({ onChange(n) }).padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CText("0$n", ColituText.kicker, color = if (active) ColituColors.lilac else ColituColors.dim)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!open) {
                        ColituIcon(ColituIcons.Lock, ColituColors.dim, 12.dp)
                        Spacer(Modifier.width(4.dp))
                    }
                    CText(loc["passport.chName$n"], ColituText.label, color = if (active) ColituColors.text else ColituColors.muted)
                }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.width(28.dp).height(2.dp).clip(RoundedCornerShape(50)).background(if (active) ColituColors.lilac else Color.Transparent))
            }
        }
    }
}

/** Three stamps per row; only the name and a dot in the level's colour. */
@Composable
private fun CleanStampGrid(stamps: List<ColituStamp>, onOpen: (ColituStamp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 12.dp
        val cell = (maxWidth - gap * 2) / 3
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            stamps.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)) {
                    row.forEach { stamp ->
                        val progress = ColituPassport.progressOf(stamp.code)
                        Column(Modifier.width(cell).pressable({ onOpen(stamp) }), horizontalAlignment = Alignment.CenterHorizontally) {
                            StampArt(stamp, progress, Modifier.fillMaxWidth(), lockSize = 30.dp)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                progress.level?.let {
                                    Box(Modifier.size(6.dp).clip(CircleShape).background(levelColor(it)))
                                    Spacer(Modifier.width(5.dp))
                                }
                                CText(
                                    ColituLoc.countryName(stamp.code),
                                    ColituText.small,
                                    color = if (progress.unlocked) ColituColors.text else ColituColors.dim,
                                    maxLines = 1,
                                    size = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Rules in three steps, today's count and the passport settings. */
@Composable
private fun HowItWorks() {
    val loc = ColituLoc
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf(
            Triple(ColituIcons.Power, "passport.how1", "passport.how1b"),
            Triple(ColituIcons.Star, "passport.how2", "passport.how2b"),
            Triple(ColituIcons.ArrowUp, "passport.how3", "passport.how3b"),
            Triple(ColituIcons.Grid, "passport.how4", "passport.how4b"),
        ).forEach { (icon, title, body) ->
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0x269F8CFF)), contentAlignment = Alignment.Center) {
                    ColituIcon(icon, ColituColors.lilac, 16.dp)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    CText(loc[title], ColituText.label)
                    CText(loc[body], ColituText.small)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        val (minutes, pieces) = ColituPassport.today
        CText(loc.format("passport.today", "m" to minutes, "p" to pieces), ColituText.small, color = ColituColors.lilac)
        var notify by remember { mutableStateOf(ColituPassport.notify) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CText(loc["passport.notify"], ColituText.label, Modifier.weight(1f))
            ColituSwitch(notify) {
                notify = it
                ColituPassport.notify = it
            }
        }
        val social = com.v2ray.ang.colitu.data.PassportSocial
        Row(verticalAlignment = Alignment.CenterVertically) {
            CText(loc["passport.set.sound"], ColituText.label, Modifier.weight(1f))
            ColituSwitch(social.sound) { social.updateSound(it) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CText(loc["passport.set.haptics"], ColituText.label, Modifier.weight(1f))
            ColituSwitch(social.haptics) { social.updateHaptics(it) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CText(loc["passport.set.metrics"], ColituText.label)
                CText(loc["passport.set.metricsBody"], ColituText.small, size = 11.5.sp)
            }
            Spacer(Modifier.width(10.dp))
            ColituSwitch(social.metrics) { social.updateMetrics(it) }
        }
        // Test build only.
        Row(verticalAlignment = Alignment.CenterVertically) {
            CText("Demo: animasyonlar hep açık", ColituText.label, Modifier.weight(1f))
            ColituSwitch(ColituPassport.demoMotion) { ColituPassport.updateDemoMotion(it) }
        }
        CText(loc["passport.local"], ColituText.small, color = ColituColors.dim)
    }
}

/** A tall bottom sheet with a title and scrolling content. */
@Composable
private fun PanelSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(topStart = ColituRadius.lg, topEnd = ColituRadius.lg)
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.86f).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(visible, enter = slideInVertically(tween(320)) { it / 2 } + fadeIn(tween(220))) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxHeight)
                        .clip(shape)
                        .border(1.dp, ColituColors.lineStrong, shape)
                        .background(Color(0xFF13112E))
                        .clickable(remember { MutableInteractionSource() }, indication = null) {},
                ) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 12.dp)) {
                        Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(RoundedCornerShape(50)).background(ColituColors.lineStrong))
                        Spacer(Modifier.height(14.dp))
                        CText(title, ColituText.h2)
                        Spacer(Modifier.height(14.dp))
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content() }
                        Spacer(Modifier.height(8.dp))
                        ColituLinkButton(ColituLoc["passport.close"], onDismiss, Modifier.align(Alignment.CenterHorizontally))
                    }
                }
            }
        }
    }
}

/** Twelve short segments: the level's puzzle as a bar. */
@Composable
internal fun SegmentBar(filled: Int, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(StampProgress.PIECES) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (i < filled) color else ColituColors.glass10),
            )
        }
    }
}

/** Special stamps outside the chapters (the Companion stamp), shown once there is one or as a hint. */
@Composable
private fun SpecialStamps() {
    val loc = ColituLoc
    val social = com.v2ray.ang.colitu.data.PassportSocial
    Spacer(Modifier.height(28.dp))
    CText(loc["passport.special"], ColituText.kicker, color = ColituColors.dim, modifier = Modifier.fillMaxWidth(), align = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        // Test build: a long press grants the Companion stamp (with its reveal).
        CompanionStamp(social.companion, Modifier.size(96.dp).pointerInput(Unit) { detectTapGestures(onLongPress = { social.markCompanion() }) })
        Spacer(Modifier.width(14.dp))
        Column(Modifier.widthIn(max = 200.dp)) {
            CText("Companion", ColituText.label, color = if (social.companion) ColituColors.text else ColituColors.muted)
            CText(
                if (social.companion) loc["passport.companion.have"] else loc["passport.companion.how"],
                ColituText.small,
                size = 12.sp,
            )
        }
    }
}

/**
 * First visit: the cover turns in, the passport is named, the three steps
 * arrive one by one, then "Start". Shown once.
 */
@Composable
private fun PassportIntro(premium: Boolean, onDone: () -> Unit) {
    val loc = ColituLoc
    val time = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        withMotion { time.animateTo(1f, tween(2600, easing = androidx.compose.animation.core.LinearEasing)) }
    }
    val t = time.value
    fun span(from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0A22))) {
            Image(painterResource(R.drawable.passport_bg), null, Modifier.matchParentSize().alpha(0.9f), contentScale = ContentScale.Crop)
            Column(
                Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val cover = androidx.compose.animation.core.FastOutSlowInEasing.transform(span(0f, 0.35f))
                Box(contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(260.dp).alpha(cover)) {
                        drawCircle(Brush.radialGradient(listOf(Color(0x669F8CFF), Color.Transparent)), radius = size.minDimension / 2)
                    }
                    PassportCover(
                        PassportGoals.activeCover(premium),
                        Modifier.height(200.dp).graphicsLayer {
                            alpha = cover
                            rotationY = (1f - cover) * -70f
                            scaleX = 0.8f + 0.2f * cover
                            scaleY = 0.8f + 0.2f * cover
                            cameraDistance = 14f * density
                        },
                    )
                }
                Spacer(Modifier.height(26.dp))
                val title = span(0.3f, 0.5f)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(title).graphicsLayer { translationY = (1f - title) * 20.dp.toPx() }) {
                    CText("COLITU PASSPORT", ColituText.kicker, color = ColituColors.lilac)
                    Spacer(Modifier.height(8.dp))
                    CText(loc["passport.intro.title"], ColituText.h1, align = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    CText(loc["passport.intro.body"], ColituText.muted, align = TextAlign.Center)
                }
                Spacer(Modifier.height(24.dp))
                listOf(
                    Triple(ColituIcons.Power, "passport.step1", "passport.step1b"),
                    Triple(ColituIcons.Star, "passport.step2", "passport.step2b"),
                    Triple(ColituIcons.ArrowUp, "passport.step3", "passport.step3b"),
                ).forEachIndexed { i, (icon, top, bottom) ->
                    val step = span(0.5f + i * 0.12f, 0.66f + i * 0.12f)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp)
                            .alpha(step)
                            .graphicsLayer { translationX = (1f - step) * 40.dp.toPx() }
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0x149F8CFF))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0x269F8CFF)), contentAlignment = Alignment.Center) {
                            ColituIcon(icon, ColituColors.lilac, 16.dp)
                        }
                        Spacer(Modifier.width(12.dp))
                        CText(loc[top], ColituText.label, Modifier.weight(1f))
                        CText(loc[bottom], ColituText.small, color = ColituColors.lilac)
                    }
                }
                Spacer(Modifier.height(26.dp))
                val button = span(0.9f, 1f)
                Box(Modifier.fillMaxWidth().alpha(button)) {
                    ColituButton(loc["passport.intro.start"], if (button > 0.5f) onDone else null)
                }
            }
        }
    }
}
