package com.v2ray.ang.colitu.screens

import com.v2ray.ang.colitu.design.pressable
import androidx.compose.foundation.shape.CircleShape
import android.Manifest
import android.content.pm.PackageManager
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.v2ray.ang.colitu.data.ColituPassport
import com.v2ray.ang.colitu.data.ColituStamp
import com.v2ray.ang.colitu.data.ExpeditionGoal
import com.v2ray.ang.colitu.data.PassportGoals
import com.v2ray.ang.colitu.data.PassportWeekly
import com.v2ray.ang.colitu.data.StampLevel
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituButton
import com.v2ray.ang.colitu.design.ColituButtonKind
import com.v2ray.ang.colitu.design.ColituChip
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituFlag
import com.v2ray.ang.colitu.design.ColituGradients
import com.v2ray.ang.colitu.design.ColituIcon
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituPanel
import com.v2ray.ang.colitu.design.ColituQrCode
import com.v2ray.ang.colitu.design.ColituRadius
import com.v2ray.ang.colitu.design.ColituSegment
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.l10n.ColituLoc

// ── Chapters ──────────────────────────────────────────────────────────────

@Composable
internal fun ChapterChips(chapter: Int, onChange: (Int) -> Unit) {
    val loc = ColituLoc
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1, 2, 3).forEach { n ->
            val open = when (n) {
                1 -> true
                2 -> ColituPassport.chapter2Open
                else -> PassportGoals.chapter3Open
            }
            ColituChip(loc["passport.ch$n"], chapter == n, if (open) null else ColituIcons.Lock) { onChange(n) }
        }
    }
}

/** Chapter 02 before it opens: what opens it; the stamps below stay locked. */
@Composable
internal fun ChapterLocked() {
    val loc = ColituLoc
    ColituPanel(padding = PaddingValues(16.dp), radius = ColituRadius.md) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColituIcon(ColituIcons.Lock, ColituColors.lilac, 22.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                CText(loc["passport.ch.lockedTitle"], ColituText.label)
                Spacer(Modifier.height(2.dp))
                CText(loc.format("passport.ch.lockedBody", "n" to ColituPassport.count, "total" to ColituPassport.stamps.size), ColituText.small)
            }
        }
    }
}

/** Chapter 03 — Legacy: mastery goals with their progress, open or not. */
@Composable
internal fun LegacyChapter() {
    val loc = ColituLoc
    val goals = listOf(
        Triple(loc["passport.legacy.gold1"], PassportGoals.legacy(StampLevel.Gold, ColituPassport.stamps), StampLevel.Gold),
        Triple(loc["passport.legacy.diamond1"], PassportGoals.legacy(StampLevel.Diamond, ColituPassport.stamps), StampLevel.Diamond),
        Triple(loc["passport.legacy.gold2"], PassportGoals.legacy(StampLevel.Gold, ColituPassport.chapter2), StampLevel.Gold),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!PassportGoals.chapter3Open) {
            CText(loc["passport.legacy.locked"], ColituText.small, Modifier.fillMaxWidth(), align = TextAlign.Center)
        }
        goals.forEach { (label, have, level) ->
            val color = levelColor(level)
            ColituPanel(padding = PaddingValues(16.dp), radius = ColituRadius.md, glow = have >= 10) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CText(label, ColituText.label, Modifier.weight(1f))
                        CText("$have/10", ColituText.label, color = color)
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(ColituColors.glass10)) {
                        Box(Modifier.fillMaxWidth(have / 10f).fillMaxHeight().clip(RoundedCornerShape(50)).background(color))
                    }
                }
            }
        }
        CText(loc["passport.legacy.keep"], ColituText.small, Modifier.fillMaxWidth(), color = ColituColors.dim, align = TextAlign.Center)
    }
}

// ── Weekly expeditions ────────────────────────────────────────────────────

internal fun expeditionIcon(goal: ExpeditionGoal) = when (goal) {
    ExpeditionGoal.NewCountry -> ColituIcons.Globe
    ExpeditionGoal.Share -> ColituIcons.Send
    ExpeditionGoal.Customize -> ColituIcons.Person
    ExpeditionGoal.Days10 -> ColituIcons.Power
    ExpeditionGoal.SetStamp -> ColituIcons.Grid
    ExpeditionGoal.Pieces -> ColituIcons.Star
}

/** This week's three goals; no countdown, a missed week costs nothing. */
@Composable
internal fun WeeklyCard() {
    val loc = ColituLoc
    // Read so the card redraws when the service or a reward changes the week.
    @Suppress("UNUSED_VARIABLE") val version = PassportWeekly.version
    ColituPanel(padding = PaddingValues(16.dp), radius = ColituRadius.md) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CText(loc["passport.week.title"], ColituText.label, Modifier.weight(1f))
                CText(loc.format("passport.week.total", "n" to PassportWeekly.total), ColituText.small, color = ColituColors.lilac)
            }
            Spacer(Modifier.height(12.dp))
            PassportWeekly.goals().forEach { goal ->
                val target = PassportWeekly.target(goal)
                val have = PassportWeekly.progress(goal).coerceAtMost(target)
                val done = PassportWeekly.done(goal) || have >= target
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(if (done) Color(0x339F8CFF) else ColituColors.surface2),
                        contentAlignment = Alignment.Center,
                    ) { ColituIcon(if (done) ColituIcons.Check else expeditionIcon(goal), if (done) ColituColors.lilac else ColituColors.muted, 16.dp) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        CText(loc["passport.x.${goal.id}"], ColituText.small, color = if (done) ColituColors.text else ColituColors.muted)
                        Spacer(Modifier.height(5.dp))
                        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)).background(ColituColors.glass10)) {
                            Box(Modifier.fillMaxWidth(have / target.toFloat()).fillMaxHeight().clip(RoundedCornerShape(50)).background(ColituGradients.accent))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    CText("$have/$target", ColituText.small, color = if (done) ColituColors.lilac else ColituColors.dim)
                }
            }
            Spacer(Modifier.height(6.dp))
            CText(loc["passport.week.note"], ColituText.small, color = ColituColors.dim, size = 11.5.sp)
        }
    }
}

/** A dated visa stamp for a finished expedition: rectangle ink with a dashed inner frame. */
@Composable
internal fun VisaStamp(goal: ExpeditionGoal, size: Dp) {
    val color = ColituColors.lilac
    Box(Modifier.size(width = size, height = size * 0.66f).rotate(-8f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(color, cornerRadius = CornerRadius(10.dp.toPx()), style = Stroke(3.dp.toPx()))
            drawRoundRect(
                color,
                topLeft = Offset(7.dp.toPx(), 7.dp.toPx()),
                size = Size(this.size.width - 14.dp.toPx(), this.size.height - 14.dp.toPx()),
                cornerRadius = CornerRadius(6.dp.toPx()),
                style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CText(ColituLoc["passport.week.visa"], ColituText.kicker.copy(fontSize = (size.value * 0.07f).sp, fontWeight = FontWeight.Bold), color = color)
            ColituIcon(expeditionIcon(goal), color, size * 0.16f)
            CText(PassportWeekly.weekKey(), ColituText.small.copy(fontSize = (size.value * 0.06f).sp), color = color)
        }
    }
}

// ── Placeholder stamp (art of the next chapter not shipped yet) ──────────

/** Perforated frame with the flag, the code and the number; [level] colours it, null is locked grey. */
@Composable
internal fun PlaceholderStamp(stamp: ColituStamp, level: StampLevel?, modifier: Modifier) {
    val color = level?.let { levelColor(it) } ?: Color(0xFF4A4A5A)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            val inset = size.minDimension * 0.1f
            val w = size.width - inset * 2
            val h = size.height - inset * 2
            drawRoundRect(Color(0xFF1B1840), topLeft = Offset(inset, inset), size = Size(w, h), cornerRadius = CornerRadius(4.dp.toPx()))
            drawRoundRect(color, topLeft = Offset(inset, inset), size = Size(w, h), cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(3.dp.toPx()))
            // Perforation: small holes punched along the edge.
            val r = size.minDimension * 0.025f
            val n = 9
            for (i in 0..n) {
                val x = inset + w * i / n
                val y = inset + h * i / n
                drawCircle(Color.Black, r, Offset(x, inset), blendMode = BlendMode.Clear)
                drawCircle(Color.Black, r, Offset(x, inset + h), blendMode = BlendMode.Clear)
                drawCircle(Color.Black, r, Offset(inset, y), blendMode = BlendMode.Clear)
                drawCircle(Color.Black, r, Offset(inset + w, y), blendMode = BlendMode.Clear)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.alpha(if (level != null) 1f else 0.4f)) { ColituFlag(stamp.code, 40.dp) }
            Spacer(Modifier.height(4.dp))
            CText(stamp.code, ColituText.label, color = color)
            CText("No. ${stampNumber(stamp)}", ColituText.small.copy(fontSize = 9.sp), color = color)
        }
    }
}

// ── Passport Duel ─────────────────────────────────────────────────────────

/** What a passport QR carries: no account data, only what the share card shows. */
internal data class DuelPassport(val nick: String, val collector: String, val title: String?, val levels: Map<String, Int>) {
    /** Level code per stamp: 0 locked, 1-4 Bronze..Diamond, 5 Diamond complete. */
    fun count(chapter: List<ColituStamp>) = chapter.count { (levels[it.code] ?: 0) > 0 }
}

private const val DUEL_PREFIX = "https://colitu.com/p#"

internal fun myDuelLink(): String {
    val levels = ColituPassport.allStamps.joinToString("") { s ->
        val p = ColituPassport.progressOf(s.code)
        when {
            !p.unlocked -> "0"
            p.mastered -> "5"
            else -> ((p.level?.ordinal ?: 0) + 1).toString()
        }
    }
    val raw = listOf("1", PassportGoals.nickname.replace("|", ""), PassportGoals.collectorNo, PassportGoals.title.orEmpty(), levels).joinToString("|")
    return DUEL_PREFIX + Base64.encodeToString(raw.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

internal fun parseDuel(text: String): DuelPassport? = runCatching {
    if (!text.startsWith(DUEL_PREFIX)) return null
    val raw = String(Base64.decode(text.removePrefix(DUEL_PREFIX), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
    val p = raw.split('|')
    if (p.size < 5 || p[0] != "1") return null
    val levels = ColituPassport.allStamps.mapIndexed { i, s -> s.code to (p[4].getOrNull(i)?.digitToIntOrNull() ?: 0) }.toMap()
    DuelPassport(p[1], p[2], p[3].ifEmpty { null }, levels)
}.getOrNull()

private fun levelLabel(n: Int): String = when (n) {
    0 -> "—"
    5 -> ColituLoc["passport.masteredShort"]
    else -> levelName(StampLevel.entries[n - 1])
}

private fun levelTint(n: Int): Color = if (n == 0) ColituColors.dim else levelColor(StampLevel.entries[(n - 1).coerceAtMost(3)])

/**
 * Passport Duel: show my QR to a friend, or scan theirs and compare side by
 * side. Everything stays on the two phones; nothing goes to a server.
 */
@Composable
internal fun DuelDialog(onDismiss: () -> Unit) {
    val loc = ColituLoc
    val context = LocalContext.current
    var mode by remember { mutableStateOf(0) }
    var friend by remember { mutableStateOf<DuelPassport?>(null) }
    var permitted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    LaunchedEffect(mode) { if (mode == 1 && !permitted) askCamera.launch(Manifest.permission.CAMERA) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(ColituRadius.lg)
        Column(
            Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .clip(shape)
                .background(ColituColors.surface)
                .border(1.dp, ColituColors.lineStrong, shape)
                .padding(18.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CText(loc["passport.duel.title"], ColituText.h2)
            Spacer(Modifier.height(4.dp))
            CText(loc["passport.duel.sub"], ColituText.small, align = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            val them = friend
            if (them != null) {
                DuelCompare(them)
                Spacer(Modifier.height(14.dp))
                ColituButton(loc["passport.duel.again"], { friend = null; mode = 1 }, kind = ColituButtonKind.Secondary)
            } else {
                ColituSegment(
                    listOf(0, 1, 2),
                    mode,
                    { mode = it },
                    { listOf(loc["passport.duel.mine"], loc["passport.duel.scan"], loc["passport.friends"])[it] },
                    Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                if (mode == 2) {
                    FriendsList { friend = it.toDuel() }
                } else if (mode == 0) {
                    Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp)) {
                        ColituQrCode(myDuelLink(), 220.dp)
                    }
                    Spacer(Modifier.height(10.dp))
                    CText(identityLine(), ColituText.small, color = ColituColors.lilac, align = TextAlign.Center)
                } else if (permitted) {
                    var bad by remember { mutableStateOf(false) }
                    Box(Modifier.size(260.dp).clip(RoundedCornerShape(16.dp))) {
                        QrCameraPreview(Modifier.fillMaxSize()) { text ->
                            val parsed = parseDuel(text)
                            if (parsed != null) {
                                friend = parsed
                                com.v2ray.ang.colitu.data.PassportSocial.addFriend(parsed.toFriend())
                            } else {
                                bad = true
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    CText(if (bad) loc["passport.duel.notPassport"] else loc["passport.duel.point"], ColituText.small, align = TextAlign.Center)
                } else {
                    CText(loc["passport.duel.camera"], ColituText.small, align = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(12.dp))
            ColituButton(loc["passport.close"], onDismiss, kind = ColituButtonKind.Secondary, height = 48.dp)
        }
    }
}

@Composable
private fun DuelCompare(them: DuelPassport) {
    val loc = ColituLoc
    val mine = ColituPassport.allStamps.associate { s ->
        val p = ColituPassport.progressOf(s.code)
        s.code to when {
            !p.unlocked -> 0
            p.mastered -> 5
            else -> (p.level?.ordinal ?: 0) + 1
        }
    }
    val theirName = them.nick.ifBlank { loc.format("passport.id.collector", "n" to them.collector) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CText(loc["passport.duel.you"], ColituText.small)
            CText("${ColituPassport.count}/10", ColituText.display, size = 34.sp, color = ColituColors.lilac)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CText(theirName, ColituText.small, maxLines = 1)
            CText("${them.count(ColituPassport.stamps)}/10", ColituText.display, size = 34.sp)
            them.title?.let { id -> CText(com.v2ray.ang.colitu.data.PassportAchievement.entries.firstOrNull { it.id == id }?.title ?: "", ColituText.small, color = ColituColors.lilac) }
        }
    }
    Spacer(Modifier.height(12.dp))
    ColituPassport.allStamps.filter { (mine[it.code] ?: 0) > 0 || (them.levels[it.code] ?: 0) > 0 }.forEach { s ->
        val a = mine[s.code] ?: 0
        val b = them.levels[s.code] ?: 0
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            ColituFlag(s.code, 24.dp)
            Spacer(Modifier.width(10.dp))
            CText(ColituLoc.countryName(s.code), ColituText.small, Modifier.weight(1f), color = ColituColors.text, maxLines = 1)
            CText(levelLabel(a), ColituText.small, Modifier.width(80.dp), color = levelTint(a), align = TextAlign.Center, weight = if (a > b) FontWeight.Bold else null)
            CText(levelLabel(b), ColituText.small, Modifier.width(80.dp), color = levelTint(b), align = TextAlign.Center, weight = if (b > a) FontWeight.Bold else null)
        }
    }
}

internal fun DuelPassport.toFriend() = com.v2ray.ang.colitu.data.PassportFriend(
    nick,
    collector,
    title,
    ColituPassport.allStamps.joinToString("") { (levels[it.code] ?: 0).toString() },
    System.currentTimeMillis(),
)

internal fun com.v2ray.ang.colitu.data.PassportFriend.toDuel() =
    DuelPassport(nick, collector, title, ColituPassport.allStamps.associate { it.code to level(it.code) })

/**
 * Friends scanned with the passport QR, kept on this device. With the
 * ranking on, you stand among them by Origin stamps, then by levels.
 */
@Composable
private fun FriendsList(onOpen: (com.v2ray.ang.colitu.data.PassportFriend) -> Unit) {
    val loc = ColituLoc
    val social = com.v2ray.ang.colitu.data.PassportSocial
    val friends = social.friends
    if (friends.isEmpty()) {
        CText(loc["passport.friends.empty"], ColituText.small, align = TextAlign.Center)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CText(loc["passport.friends.ranking"], ColituText.label, Modifier.weight(1f))
        com.v2ray.ang.colitu.design.ColituSwitch(social.showRanking) { social.updateRanking(it) }
    }
    Spacer(Modifier.height(10.dp))
    val myScore = ColituPassport.progress.values.sumOf { p -> if (p.mastered) 5 else (p.level?.ordinal ?: -1) + 1 }
    val me = com.v2ray.ang.colitu.data.PassportFriend(
        loc["passport.duel.you"],
        PassportGoals.collectorNo,
        PassportGoals.title,
        "",
        Long.MAX_VALUE,
    )
    val rows = if (social.showRanking) {
        (friends + me).sortedWith(
            compareByDescending<com.v2ray.ang.colitu.data.PassportFriend> { if (it === me) ColituPassport.count else it.count(ColituPassport.stamps) }
                .thenByDescending { if (it === me) myScore else it.score },
        )
    } else {
        friends
    }
    rows.forEachIndexed { i, f ->
        val isMe = f === me
        val shape = RoundedCornerShape(14.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(shape)
                .background(if (isMe) Color(0x269F8CFF) else Color(0x10FFFFFF))
                .then(if (isMe) Modifier else Modifier.pressable({ onOpen(f) }))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (social.showRanking) {
                CText("${i + 1}", ColituText.label, Modifier.width(26.dp), color = if (i == 0) ColituColors.lilac else ColituColors.muted)
            }
            Column(Modifier.weight(1f)) {
                CText(f.nick.ifBlank { loc.format("passport.id.collector", "n" to f.collector) }, ColituText.label, maxLines = 1)
                val title = com.v2ray.ang.colitu.data.PassportAchievement.entries.firstOrNull { it.id == f.title }?.title
                if (title != null) CText(title, ColituText.small, color = ColituColors.lilac, maxLines = 1)
            }
            CText("${if (isMe) ColituPassport.count else f.count(ColituPassport.stamps)}/10", ColituText.label)
            if (!isMe) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(32.dp).clip(CircleShape).pressable({ social.removeFriend(f.collector) }), contentAlignment = Alignment.Center) {
                    ColituIcon(ColituIcons.XMark, ColituColors.dim, 14.dp, description = loc["passport.friends.remove"])
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    CText(loc["passport.friends.local"], ColituText.small, color = ColituColors.dim, size = 11.5.sp, align = TextAlign.Center)
}
