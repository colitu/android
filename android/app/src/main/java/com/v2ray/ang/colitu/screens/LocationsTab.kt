package com.v2ray.ang.colitu.screens

import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.v2ray.ang.colitu.app.ColituController
import com.v2ray.ang.colitu.data.ColituQuickServers
import com.v2ray.ang.colitu.data.ColituServer
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituField
import com.v2ray.ang.colitu.design.ColituFlag
import com.v2ray.ang.colitu.design.ColituIcon
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituPanel
import com.v2ray.ang.colitu.design.ColituRoundIcon
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.ColituTv
import com.v2ray.ang.colitu.design.focusRing
import com.v2ray.ang.colitu.l10n.ColituLoc
import com.v2ray.ang.colitu.l10n.ColituServerNames
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Use-case categories from the panel ("all" plus ai, streaming, gaming,
 * speed, privacy, torrent). The panel adds streaming/ai itself when a node's
 * service checks pass; older panels only sent services, so those still count.
 */
private val categories = listOf("all", "ai", "streaming", "gaming", "speed", "privacy", "torrent")

private enum class SortBy { Ping, Name, Load }

private fun ColituServer.inCategory(category: String): Boolean = when (category) {
    "all" -> true
    "streaming" -> "streaming" in categories || opensStreaming
    "ai" -> "ai" in categories || opensAi
    else -> category in categories
}

private fun fold(value: String) = value.lowercase()
    .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u')
    .replace('ş', 's').replace('ö', 'o').replace('ç', 'c')

private fun loadPercent(server: ColituServer): Int? = when (server.load) {
    "low" -> 25
    "medium" -> 55
    "high" -> 85
    else -> null
}

private fun categoryIcon(category: String): ImageVector = when (category) {
    "all" -> ColituIcons.Grid
    "ai" -> ColituIcons.Robot
    "streaming" -> ColituIcons.Film
    "gaming" -> ColituIcons.Gamepad
    "speed" -> ColituIcons.Bolt
    "privacy" -> ColituIcons.Shield
    "torrent" -> ColituIcons.Download
    "adblock" -> ColituIcons.EyeSlash
    else -> ColituIcons.Globe
}

// ── Metrics and motion ─────────────────────────────────────────────────────
// The same numbers as the iOS list (lib/pages/colitu/shell/locations_tab.dart); keep the two in step.

private val CardRadius = 24.dp
private val CardPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
private val CardPaddingVertical = 16.dp
private val CardGap = 12.dp
private val RowMinHeight = 76.dp
private val FlagSize = 44.dp
private val FlagGap = 14.dp
private val FieldRadius = 18.dp

/** Border of the selected or connected row: a soft violet line, no fill. */
private val SelectedBorder = Color(0x999F8CFF)

/** Border of the recommended card: the accent-on-dark violet, kept faint. */
private val RecommendedBorder = Color(0x4D9B8CFF)

private const val ExpandMs = 250
private const val BorderMs = 220
private const val StaggerStepMs = 35
private const val StaggerRows = 8

/** Rows composed this long after the tab opened (scrolled into view later) appear without the fade. */
private const val RevealWindowMs = 600L

private val EaseOutCubic = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)
private val EaseInCubic = CubicBezierEasing(0.32f, 0f, 0.67f, 0f)

private val NameStyle = TextStyle(
    fontFamily = ColituText.label.fontFamily,
    fontSize = 17.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = (-0.2).sp,
    color = ColituColors.text,
    lineHeight = 21.sp,
)
private val SubStyle = TextStyle(
    fontFamily = ColituText.label.fontFamily,
    fontSize = 14.sp,
    fontWeight = FontWeight.Normal,
    color = ColituColors.muted,
    lineHeight = 18.sp,
)
private val ConnectedStyle = SubStyle.copy(color = ColituColors.lilac, fontWeight = FontWeight.SemiBold)
private val KickerStyle = TextStyle(
    fontFamily = ColituText.label.fontFamily,
    fontSize = 12.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 1.9.sp,
    color = ColituColors.muted,
    lineHeight = 14.5.sp,
)

/** True when the system animator duration scale is 0 ("Remove animations"): nothing moves. */
private val LocalStill = staticCompositionLocalOf { false }

@Composable
private fun rememberStill(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/** Upper case that keeps the Turkish dotted capital İ. */
private fun upper(text: String): String =
    if (ColituLoc.language == "tr") text.uppercase(Locale.forLanguageTag("tr")) else text.uppercase()

/** Lazy list keys must be unique; a server id the panel repeats gets a suffix instead of a crash. */
private class Keys {
    private val seen = HashMap<String, Int>()
    fun of(base: String): String {
        val n = seen[base] ?: 0
        seen[base] = n + 1
        return if (n == 0) base else "$base#$n"
    }
}

/**
 * Server list, the same as on iOS: title with the "fastest server" shortcut,
 * search, category chips, a sort menu, the recommended locations and then
 * every other one. A row is one calm card: round flag, country, one muted
 * line (city or "N locations") and the ping. A country with several cities
 * opens inside its own card. The list is lazy: a long server list never
 * composes all rows at once.
 */
@Composable
fun LocationsTab(c: ColituController, onOpenPlan: () -> Unit) {
    val loc = ColituLoc
    var query by rememberSaveable { mutableStateOf("") }
    var chosenFilter by rememberSaveable { mutableStateOf("all") }
    var chosenSort by rememberSaveable { mutableStateOf(SortBy.Ping) }
    // Simple mode: one flat list by ping, no categories, sort menu or multihop routes.
    val advanced = c.advancedMode
    val filter = if (advanced) chosenFilter else "all"
    val sort = if (advanced) chosenSort else SortBy.Ping
    LaunchedEffect(Unit) { c.measurePings() }
    val q = fold(query.trim())
    // Cities of one country tie on the title, so they sort by city inside a group.
    val byName = compareBy<ColituServer> { c.titleOf(it) }.thenBy { ColituServerNames.city(it.city) }.thenBy { it.displayName }
    val items = c.servers
        .filter { server ->
            server.inCategory(filter) && (q.isEmpty() || fold("${c.titleOf(server)} ${ColituServerNames.city(server.city)} ${server.displayName} ${server.city.orEmpty()} ${server.countryCode.orEmpty()}").contains(q))
        }
        .sortedWith(
            compareBy<ColituServer> { if (it.isAvailable) 0 else 1 }.then(
                when (sort) {
                    SortBy.Ping -> compareBy<ColituServer> { c.pingOf(it) ?: Int.MAX_VALUE }.then(byName)
                    SortBy.Load -> compareBy<ColituServer> { loadPercent(it) ?: Int.MAX_VALUE }.then(byName)
                    SortBy.Name -> byName
                },
            ),
        )
    val recommended = recommended(c, items)
    val rest = items.filterNot { it in recommended }
    // A search shows matches flat; otherwise a country with several servers becomes one expandable card.
    val restEntries = groupByCountry(rest, if (c.autoSelection) null else c.selectedServerId, flat = q.isNotEmpty())
    // Multihop routes have no use-case categories: they show under "All" only. The flag and name
    // of the exit and the entry both count for the search.
    val routeItems = if (filter != "all" || !advanced) emptyList() else c.routes
        .filter { route ->
            q.isEmpty() || fold("${route.displayName} ${ColituServerNames.route(route.displayName)} ${route.route?.entry?.label.orEmpty()} ${route.route?.exit?.label.orEmpty()} ${route.route?.entry?.country.orEmpty()} ${route.route?.exit?.country.orEmpty()}").contains(q)
        }
        .sortedWith(
            when (sort) {
                SortBy.Ping -> compareBy<ColituServer> { c.pingOf(it) ?: Int.MAX_VALUE }.then(byName)
                SortBy.Load -> compareBy<ColituServer> { loadPercent(it) ?: Int.MAX_VALUE }.then(byName)
                SortBy.Name -> byName
            },
        )
    val openedAt = remember { SystemClock.uptimeMillis() }
    val still = rememberStill()
    val quick = quickListsOf(c)

    val content: LazyListScope.() -> Unit = {
        item(key = "title", contentType = "title") {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Long translations shrink instead of being cut off.
                    BasicText(
                        loc["locations.title"],
                        Modifier.weight(1f),
                        style = ColituText.h1,
                        maxLines = 1,
                        softWrap = false,
                        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 30.sp),
                    )
                    Spacer(Modifier.width(10.dp))
                    FastestButton(c)
                }
                Spacer(Modifier.height(6.dp))
                CText(loc["locations.tagline"], ColituText.muted)
                Spacer(Modifier.height(20.dp))
            }
        }
        item(key = "search", contentType = "search") {
            ColituField(
                value = query,
                onChange = { query = it },
                label = null,
                hint = loc["locations.search"] + "…",
                accessibilityLabel = loc["locations.search"],
                prefix = ColituIcons.Search,
                fill = ColituColors.surface,
                radius = FieldRadius,
                verticalPadding = 14.dp,
                suffix = if (query.isEmpty()) null else ({
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).press({ query = "" }, radius = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) { ColituIcon(ColituIcons.XCircleOutline, ColituColors.muted, 18.dp, description = loc["locations.clear"]) }
                }),
            )
        }
        // Favourites and recently used servers (this device only), while nothing is searched.
        if (q.isEmpty()) {
            val quickKeys = Keys()
            if (quick.favorites.isNotEmpty()) {
                item(key = "sec-fav", contentType = "section") {
                    Column {
                        Spacer(Modifier.height(18.dp))
                        SectionHeader(loc["quick.favorites"])
                    }
                }
                quick.favorites.forEach { server ->
                    item(key = quickKeys.of("fav-" + server.id), contentType = "server") { QuickRow(c, server) }
                }
            }
            if (quick.recent.isNotEmpty()) {
                item(key = "sec-recent", contentType = "section") {
                    Column {
                        Spacer(Modifier.height(if (quick.favorites.isEmpty()) 18.dp else 2.dp))
                        SectionHeader(loc["quick.recent"])
                    }
                }
                quick.recent.forEach { server ->
                    item(key = quickKeys.of("rec-" + server.id), contentType = "server") { QuickRow(c, server) }
                }
            }
        }
        item(key = "chips", contentType = "chips") {
            if (advanced) {
                Column {
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        categories.forEach { value ->
                            if (value == "all" || value == filter || c.servers.any { it.inCategory(value) }) {
                                FilterChip(
                                    if (value == "all") loc["locations.all"] else loc["cat.$value"],
                                    categoryIcon(value),
                                    filter == value,
                                ) { chosenFilter = value }
                                Spacer(Modifier.width(10.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(26.dp))
                }
            } else {
                Spacer(Modifier.height(26.dp))
            }
        }
        if (!c.planActive && (!PLAY_POLICY || ColituStore.playBilling)) item(key = "promo", contentType = "promo") {
            Column {
                ColituPanel(padding = CardPadding, radius = CardRadius, onClick = onOpenPlan) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ColituRoundIcon(ColituIcons.Globe, size = FlagSize, accent = true)
                        Spacer(Modifier.width(FlagGap))
                        Column(Modifier.weight(1f)) {
                            CText(loc["locations.promo"], ColituText.label)
                            Spacer(Modifier.height(2.dp))
                            CText(loc["locations.promoSub"], ColituText.small)
                        }
                        Spacer(Modifier.width(10.dp))
                        ColituIcon(ColituIcons.ChevronRight, ColituColors.muted, 16.dp)
                    }
                }
                Spacer(Modifier.height(26.dp))
            }
        }
        when {
            c.servers.isEmpty() && c.routes.isEmpty() -> item(key = "empty", contentType = "empty") {
                EmptyText(if (c.planRequired) loc["plan.noneHint"] else loc["server.none"])
            }
            items.isEmpty() && routeItems.isEmpty() -> item(key = "empty", contentType = "empty") {
                EmptyText(if (filter != "all" && q.isEmpty()) loc["cat.empty"] else loc["locations.empty"])
            }
            else -> {
                val keys = Keys()
                var row = 0
                if (items.isNotEmpty()) {
                    item(key = "sec-first", contentType = "section") {
                        SectionHeader(if (recommended.isNotEmpty()) loc["locations.recommended"] else loc["locations.allServers"]) {
                            if (advanced) SortButton(sort) { chosenSort = it }
                        }
                    }
                    recommended.forEach { server ->
                        val index = row++
                        item(key = keys.of("s-" + server.id), contentType = "server") {
                            ServerCard(c, server, recommended = true, Modifier.rowReveal(index, openedAt))
                        }
                    }
                    if (recommended.isNotEmpty() && rest.isNotEmpty()) {
                        item(key = "sec-all", contentType = "section") {
                            Column {
                                Spacer(Modifier.height(14.dp))
                                SectionHeader(loc["locations.allServers"])
                            }
                        }
                    }
                    restEntries.forEach { entry ->
                        val index = row++
                        when (entry) {
                            is LocationEntry.Single -> item(key = keys.of("s-" + entry.server.id), contentType = "server") {
                                ServerCard(c, entry.server, recommended = false, Modifier.rowReveal(index, openedAt))
                            }
                            is LocationEntry.Group -> item(key = keys.of("g-" + entry.countryCode), contentType = "group") {
                                CountryGroup(c, entry, Modifier.rowReveal(index, openedAt))
                            }
                        }
                    }
                }
                if (routeItems.isNotEmpty()) {
                    item(key = "sec-hop", contentType = "section") {
                        Column {
                            Spacer(Modifier.height(14.dp))
                            SectionHeader(loc["multihop.section"]) {
                                if (items.isEmpty()) SortButton(sort) { chosenSort = it }
                            }
                            CText(loc["multihop.sectionHint"], ColituText.small, Modifier.padding(start = 4.dp, top = 6.dp, end = 4.dp))
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    routeItems.forEach { route ->
                        val index = row++
                        item(key = keys.of("r-" + route.id), contentType = "route") {
                            RouteCard(c, route, Modifier.rowReveal(index, openedAt))
                        }
                    }
                }
            }
        }
    }

    CompositionLocalProvider(LocalStill provides still) {
        if (ColituTv.isTv) {
            // TV pages sit next to the side menu; no status bar or floating tab bar.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    Modifier.widthIn(max = 900.dp).fillMaxSize(),
                    contentPadding = PaddingValues(start = 24.dp, top = 28.dp, end = 24.dp, bottom = 32.dp),
                    content = content,
                )
            }
        } else {
            val density = LocalDensity.current
            val topInset = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
            val bottomInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
            // The bottom room keeps the floating navigation bar off the last row.
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, top = topInset + 8.dp, end = 20.dp, bottom = 116.dp + bottomInset),
                content = content,
            )
        }
    }
}

/**
 * The automatic choice (the first of ColituServerRanking, exactly what
 * "Fastest server" connects to); when it is filtered out, the three fastest.
 */
private fun recommended(c: ColituController, items: List<ColituServer>): List<ColituServer> {
    c.recommendedServer?.let { best -> items.firstOrNull { it.id == best.id } }?.let { return listOf(it) }
    val fastest = items.filter { it.isAvailable && c.pingOf(it) != null }
        .sortedBy { c.pingOf(it) }
        .take(3)
        .toSet()
    return items.filter { it in fastest }
}

/** Favourites and recently used servers that are still in the list (a removed server never shows). */
internal class QuickLists(val favorites: List<ColituServer>, val recent: List<ColituServer>)

/** Simple mode has no multihop routes, so only nodes count there. */
internal fun quickListsOf(c: ColituController): QuickLists {
    val pool = if (c.advancedMode) c.allServers else c.servers
    val favorites = ColituQuickServers.resolve(ColituQuickServers.favoriteIds, pool)
    val recent = ColituQuickServers.recentWithoutFavorites(ColituQuickServers.resolve(ColituQuickServers.recentIds, pool), favorites)
    return QuickLists(favorites, recent)
}

@Composable
private fun QuickRow(c: ColituController, server: ColituServer) {
    if (server.route != null) RouteCard(c, server, Modifier) else ServerCard(c, server, recommended = false, Modifier)
}

// ── Motion helpers ─────────────────────────────────────────────────────────

/**
 * The first rows fade in one after another and lift 10 dp (35 ms apart, 250 ms each); later
 * rows share the last delay. Rows that only appear when scrolled to, and every row when the
 * system animations are off, show at once.
 */
@Composable
private fun Modifier.rowReveal(index: Int, openedAt: Long): Modifier {
    val still = LocalStill.current
    val animate = remember { !still && SystemClock.uptimeMillis() - openedAt < RevealWindowMs }
    if (!animate) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(StaggerStepMs.toLong() * minOf(index, StaggerRows))
        progress.animateTo(1f, tween(250, easing = EaseOutCubic))
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = 10.dp.toPx() * (1 - progress.value)
    }
}

/** Shrinks to 0.98 while pressed and springs back on release; a TV-focused card grows a little. */
@Composable
private fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val still = LocalStill.current
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val target = when {
        pressed -> 0.98f
        focused && ColituTv.isTv -> 1.03f
        else -> 1f
    }
    val scale by animateFloatAsState(
        target,
        when {
            still -> snap()
            pressed -> tween(110, easing = EaseOutCubic)
            else -> spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)
        },
        label = "press",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Tap target with the press scale and the TV focus ring. */
@Composable
private fun Modifier.press(onClick: (() -> Unit)?, radius: Dp = CardRadius, role: Role = Role.Button): Modifier {
    if (onClick == null) return this
    val interaction = remember { MutableInteractionSource() }
    return pressScale(interaction)
        .focusRing(interaction, radius)
        .clickable(interactionSource = interaction, indication = null, role = role, onClick = onClick)
}

private enum class Edge { Plain, Recommended, Selected }

/** Border colour and width that move to the new state (220 ms). */
@Composable
private fun animatedEdge(edge: Edge): Pair<Color, Dp> {
    val still = LocalStill.current
    val color by animateColorAsState(
        when (edge) {
            Edge.Selected -> SelectedBorder
            Edge.Recommended -> RecommendedBorder
            Edge.Plain -> ColituColors.line
        },
        if (still) snap() else tween(BorderMs, easing = EaseOutCubic),
        label = "edge",
    )
    val width by animateDpAsState(
        if (edge == Edge.Selected) 1.5.dp else 1.dp,
        if (still) snap() else tween(BorderMs, easing = EaseOutCubic),
        label = "edgeWidth",
    )
    return color to width
}

/** The row card: dark surface, 24 radius, a hairline that turns violet (animated) when chosen. */
@Composable
private fun RowCard(
    modifier: Modifier,
    edge: Edge,
    padding: PaddingValues = CardPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(CardRadius)
    val (color, width) = animatedEdge(edge)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ColituColors.surface)
            .border(width, color, shape)
            .padding(padding),
        content = content,
    )
}

// ── Header controls ────────────────────────────────────────────────────────

@Composable
private fun EmptyText(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
        CText(text, ColituText.muted)
    }
}

/** Quiet small-caps section label with an optional control on the right. */
@Composable
private fun SectionHeader(title: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 12.dp).heightIn(min = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(upper(title), Modifier.weight(1f), style = KickerStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.invoke()
    }
}

/** "Fastest server" in the title row: picks the server automatically. */
@Composable
private fun FastestButton(c: ColituController) {
    val active = c.connectsAutomatically
    val shape = RoundedCornerShape(50)
    val still = LocalStill.current
    val edge by animateColorAsState(
        if (active) SelectedBorder else ColituColors.lineStrong,
        if (still) snap() else tween(BorderMs, easing = EaseOutCubic),
        label = "fastestEdge",
    )
    Row(
        Modifier
            .press({ c.selectAuto() }, radius = 22.dp)
            .height(44.dp)
            .clip(shape)
            .background(ColituColors.surface)
            .border(if (active) 1.5.dp else 1.dp, edge, shape)
            .padding(start = 14.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColituIcon(ColituIcons.Bolt, ColituColors.violet, 18.dp)
        Spacer(Modifier.width(6.dp))
        CText(ColituLoc["locations.fastest"], ColituText.label, size = 14.sp, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        ColituIcon(
            if (active) ColituIcons.Check else ColituIcons.ChevronRight,
            if (active) ColituColors.lilac else ColituColors.muted,
            14.dp,
        )
    }
}

/** Category filter: calm 44 dp chip with the field's 18 radius, violet outline when chosen. */
@Composable
private fun FilterChip(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FieldRadius)
    val still = LocalStill.current
    val spec: AnimationSpec<Color> = if (still) snap() else tween(BorderMs, easing = EaseOutCubic)
    val bg by animateColorAsState(if (selected) Color(0x1F9F8CFF) else ColituColors.surface, spec, label = "chipBg")
    val edge by animateColorAsState(if (selected) SelectedBorder else ColituColors.line, spec, label = "chipEdge")
    Row(
        Modifier
            // Named and marked chosen for a screen reader, also when the strip cuts the chip off.
            .semantics {
                contentDescription = label
                this.selected = selected
            }
            .press(onClick, radius = FieldRadius)
            .height(44.dp)
            .clip(shape)
            .background(bg)
            .border(if (selected) 1.5.dp else 1.dp, edge, shape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColituIcon(icon, if (selected) ColituColors.lilac else ColituColors.muted, 18.dp)
        Spacer(Modifier.width(8.dp))
        CText(label, ColituText.label, size = 14.sp, weight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun SortButton(sort: SortBy, onChange: (SortBy) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .press({ open = !open }, radius = 14.dp)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColituIcon(ColituIcons.Sort, ColituColors.muted, 16.dp)
            Spacer(Modifier.width(6.dp))
            CText(ColituLoc["locations.sort.${sort.name.lowercase()}"], ColituText.small, color = ColituColors.text, size = 13.sp, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            ColituIcon(ColituIcons.ChevronDown, ColituColors.muted, 12.dp)
        }
        if (open) {
            val offsetY = with(LocalDensity.current) { 40.dp.roundToPx() }
            Popup(
                alignment = Alignment.TopEnd,
                offset = androidx.compose.ui.unit.IntOffset(0, offsetY),
                onDismissRequest = { open = false },
                // Focusable so a TV remote can move into the menu and Back closes it.
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier
                        .width(IntrinsicSize.Max)
                        .widthIn(min = 170.dp)
                        .clip(RoundedCornerShape(FieldRadius))
                        .background(ColituColors.surface2)
                        .border(1.dp, ColituColors.lineStrong, RoundedCornerShape(FieldRadius))
                        .padding(vertical = 6.dp),
                ) {
                    SortBy.entries.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .press({ onChange(option); open = false }, radius = 12.dp)
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CText(
                                ColituLoc["locations.sort.${option.name.lowercase()}"],
                                ColituText.label,
                                Modifier.weight(1f),
                                size = 14.sp,
                                color = if (option == sort) ColituColors.lilac else ColituColors.text,
                            )
                            if (option == sort) ColituIcon(ColituIcons.Check, ColituColors.lilac, 16.dp)
                        }
                    }
                }
            }
        }
    }
}

// ── Rows ───────────────────────────────────────────────────────────────────

/** Flag, title (with at most one small badge) and one muted line; [trailing] sits on the right. */
@Composable
private fun RowBody(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String,
    connected: Boolean,
    badge: (@Composable () -> Unit)? = null,
    ping: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit,
) {
    // Large text: the ping moves under the name so the name keeps its width and never breaks mid-word.
    val pingBelow = ping != null && largeText()
    Row(
        Modifier.fillMaxWidth().heightIn(min = RowMinHeight - CardPaddingVertical * 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(FlagGap))
        Column(Modifier.weight(1f)) {
            // Two lines before an ellipsis: "Birleşik Krallık" on a 360 dp phone. The badge sits on
            // the second line: next to the name, with the ping and the star, it left the name too
            // narrow on a 360 dp phone ("Swede" / "n" on the connected card).
            BasicText(title, style = NameStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (connected || subtitle.isNotEmpty() || badge != null) {
                Spacer(Modifier.height(3.dp))
                SubLine(subtitle, connected, badge)
            }
            if (pingBelow) {
                Spacer(Modifier.height(4.dp))
                ping!!()
            }
        }
        Spacer(Modifier.width(12.dp))
        if (ping != null && !pingBelow) ping()
        trailing()
    }
}

/** The muted second line; "Connected" in violet goes first when it applies, the badge last. */
@Composable
private fun SubLine(text: String, connected: Boolean, badge: (@Composable () -> Unit)? = null) {
    // "Connected" with the city or a badge does not fit next to the ping and the star on a 360 dp
    // phone: the connected card shows only "Connected" then (Home shows the city).
    val city = if (connected && badge != null) "" else text
    val shownBadge = if (connected) null else badge
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (connected) {
            BasicText(ColituLoc["server.connected"], style = ConnectedStyle, maxLines = 1)
            if (city.isNotEmpty()) BasicText(" · ", style = SubStyle, maxLines = 1)
        }
        if (city.isNotEmpty()) {
            // Next to a badge one line with an ellipsis: two lines broke "Stockholm" mid-word.
            BasicText(city, Modifier.weight(1f, fill = false), style = SubStyle, maxLines = if (badge != null) 1 else 2, overflow = TextOverflow.Ellipsis)
        }
        if (shownBadge != null) {
            if (city.isNotEmpty()) Spacer(Modifier.width(8.dp))
            shownBadge()
        }
    }
}

@Composable
private fun Offline() {
    CText(ColituLoc["server.offline"], ColituText.small, maxLines = 1)
}

@Composable
private fun ServerCard(c: ColituController, server: ColituServer, recommended: Boolean, modifier: Modifier) {
    val loc = ColituLoc
    val selected = !c.autoSelection && c.selectedServerId == server.id
    val connected = c.connected && c.connectedServerId == server.id
    val selectable = server.isAvailable
    // Panel names (Turkish) in the app language; recomposes on a language switch.
    val country = server.countryCode?.let { loc.countryName(it) }?.takeIf { it.isNotBlank() }
        ?: ColituServerNames.displayName(server.displayName, countryCode = server.countryCode)
    val city = ColituServerNames.city(server.city)
    val subtitle = if (city.isNotEmpty() && !city.equals(country, ignoreCase = true)) city else ""
    Column(modifier.padding(bottom = CardGap)) {
        RowCard(
            Modifier.alpha(if (selectable) 1f else 0.5f).press(if (selectable) ({ c.selectServer(server) }) else null),
            edge = when {
                selected || connected -> Edge.Selected
                recommended -> Edge.Recommended
                else -> Edge.Plain
            },
        ) {
            RowBody(
                leading = { ColituFlag(server.countryCode, FlagSize) },
                title = country,
                subtitle = subtitle,
                connected = connected,
                badge = badgeFor(server),
                ping = { if (selectable) Ping(c.pingOf(server), unreachable(c, server)) else Offline() },
            ) {
                FavoriteButton(server.id)
            }
        }
    }
}

/** The system font size is 120 % or more. */
@Composable
private fun largeText(): Boolean = LocalDensity.current.fontScale >= 1.2f

/** A ping was tried on this list and failed (no entry at all means not measured yet). */
private fun unreachable(c: ColituController, server: ColituServer): Boolean =
    c.pings[server.id]?.let { it.ms == null } == true

/**
 * Star on every server row: adds or removes a favourite (stored on this device).
 * 48 dp target; a screen reader says "Add to favourites" or "Remove from favourites".
 */
@Composable
private fun FavoriteButton(id: String) {
    val favorite = ColituQuickServers.isFavorite(id)
    val label = ColituLoc[if (favorite) "quick.removeFavorite" else "quick.addFavorite"]
    Box(
        Modifier
            .padding(start = 4.dp)
            .size(48.dp)
            .clip(CircleShape)
            .press({ ColituQuickServers.toggleFavorite(id) }, radius = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        ColituIcon(
            if (favorite) ColituIcons.Star else ColituIcons.StarOutline,
            if (favorite) ColituColors.warning else ColituColors.muted,
            20.dp,
            description = label,
        )
    }
}

/** The one small badge a row may carry: ad-free YouTube, else the ad blocker when the node has it. */
private fun badgeFor(server: ColituServer): (@Composable () -> Unit)? = when {
    "youtube_adfree" in server.services -> ({ MiniBadge { ServiceMark("youtube_adfree", 12.dp) } })
    server.hostsAdBlockDns -> ({ MiniBadge { ColituIcon(ColituIcons.EyeSlash, ColituColors.muted, 12.dp) } })
    else -> null
}

/** Icon-only round badge so the country name keeps its room. */
@Composable
private fun MiniBadge(mark: @Composable () -> Unit) {
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(ColituColors.surface2)
            .border(1.dp, ColituColors.line, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) { mark() }
    }
}

/**
 * A country with several servers: one card whose header (flag, country, how many locations,
 * best ping, a thin chevron) opens and closes the city rows inside it, under a hairline. Both
 * header and cities are ordinary focusable targets, so a TV remote opens the group with OK and
 * walks into the cities with Down.
 */
@Composable
private fun CountryGroup(c: ColituController, group: LocationEntry.Group, modifier: Modifier) {
    val loc = ColituLoc
    val still = LocalStill.current
    var expanded by rememberSaveable { mutableStateOf(group.expandedByDefault) }
    val name = loc.countryName(group.countryCode).takeIf { it.isNotBlank() }
        ?: ColituServerNames.displayName(group.servers.first().displayName, countryCode = group.countryCode)
    val hasSelected = !c.autoSelection && group.servers.any { it.id == c.selectedServerId }
    val hasConnected = c.connected && group.servers.any { it.id == c.connectedServerId }
    val best = bestPing(group.servers) { c.pingOf(it) }
    val anyOnline = group.servers.any { it.isAvailable }
    val interaction = remember { MutableInteractionSource() }
    val turn by animateFloatAsState(
        if (expanded) 180f else 0f,
        if (still) snap() else tween(ExpandMs, easing = EaseOutCubic),
        label = "chevron",
    )
    Column(modifier.padding(bottom = CardGap)) {
        RowCard(
            // The TV focus ring goes round the whole card, outside its clip.
            Modifier.pressScale(interaction).focusRing(interaction, CardRadius),
            // Open, the chosen city carries the outline instead of the card.
            edge = if (!expanded && (hasSelected || hasConnected)) Edge.Selected else Edge.Plain,
            padding = PaddingValues(0.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button) { expanded = !expanded }
                    .alpha(if (anyOnline) 1f else 0.5f)
                    .padding(CardPadding),
            ) {
                RowBody(
                    leading = { ColituFlag(group.countryCode, FlagSize) },
                    title = name,
                    subtitle = loc.count("locationCount", group.servers.size),
                    connected = !expanded && hasConnected,
                    ping = { if (anyOnline) Ping(best, group.servers.filter { it.isAvailable }.all { unreachable(c, it) }) else Offline() },
                ) {
                    Spacer(Modifier.width(12.dp))
                    ColituIcon(ColituIcons.ChevronDown, ColituColors.muted, 16.dp, Modifier.graphicsLayer { rotationZ = turn })
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = if (still) EnterTransition.None
                else expandVertically(tween(ExpandMs, easing = EaseOutCubic), expandFrom = Alignment.Top) + fadeIn(tween(ExpandMs, easing = EaseOutCubic)),
                exit = if (still) ExitTransition.None
                else shrinkVertically(tween(ExpandMs, easing = EaseInCubic), shrinkTowards = Alignment.Top) + fadeOut(tween(ExpandMs, easing = EaseInCubic)),
            ) {
                Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                    Box(
                        Modifier
                            .padding(start = 10.dp, end = 10.dp, bottom = 6.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(ColituColors.line),
                    )
                    group.servers.forEach { server ->
                        androidx.compose.runtime.key(server.id) {
                            Spacer(Modifier.height(4.dp))
                            CityRow(c, server)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One city inside an open country card: city, up to three small service tags (only those that
 * fit), ping, and a check when it is the chosen one. Tapping selects it like a single row.
 */
@Composable
private fun CityRow(c: ColituController, server: ColituServer) {
    val loc = ColituLoc
    val still = LocalStill.current
    val selected = !c.autoSelection && c.selectedServerId == server.id
    val connected = c.connected && c.connectedServerId == server.id
    val active = selected || connected
    val selectable = server.isAvailable
    val label = ColituServerNames.city(server.city)
        .ifEmpty { ColituServerNames.displayName(server.displayName, countryCode = server.countryCode) }
    val tags = tagsOf(server)
    val large = largeText()
    val shape = RoundedCornerShape(FieldRadius)
    val edge by animateColorAsState(
        if (active) SelectedBorder else Color.Transparent,
        if (still) snap() else tween(BorderMs, easing = EaseOutCubic),
        label = "cityEdge",
    )
    val checkAlpha by animateFloatAsState(
        if (active) 1f else 0f,
        if (still) snap() else tween(BorderMs, easing = EaseOutCubic),
        label = "cityCheck",
    )
    Row(
        Modifier
            .alpha(if (selectable) 1f else 0.5f)
            .press(if (selectable) ({ c.selectServer(server) }) else null, radius = FieldRadius)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .border(1.5.dp, edge, shape)
            .padding(start = 14.dp, top = 4.dp, end = 0.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    label,
                    Modifier.weight(1f, fill = false),
                    style = NameStyle.copy(fontSize = 15.5.sp, lineHeight = 19.5.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (connected) {
                    Spacer(Modifier.width(8.dp))
                    BasicText(loc["server.connected"], style = ConnectedStyle.copy(fontSize = 12.5.sp), maxLines = 1)
                }
            }
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                TagLine(tags)
            }
            if (large) {
                Spacer(Modifier.height(4.dp))
                if (selectable) Ping(c.pingOf(server), unreachable(c, server)) else Offline()
            }
        }
        Spacer(Modifier.width(10.dp))
        if (!large) {
            if (selectable) Ping(c.pingOf(server), unreachable(c, server)) else Offline()
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (checkAlpha > 0f) ColituIcon(ColituIcons.Check, ColituColors.lilac, 17.dp, Modifier.alpha(checkAlpha))
        }
        FavoriteButton(server.id)
    }
}

/**
 * A multihop route: the entry and exit flags, the route name and the ping to the entry (an
 * estimate: the exit adds a hop).
 */
@Composable
private fun RouteCard(c: ColituController, route: ColituServer, modifier: Modifier) {
    val ends = route.route ?: return
    val selected = !c.autoSelection && c.selectedServerId == route.id
    val connected = c.connected && c.connectedServerId == route.id
    Column(modifier.padding(bottom = CardGap)) {
        RowCard(
            Modifier.press({ c.selectServer(route) }),
            edge = if (selected || connected) Edge.Selected else Edge.Plain,
        ) {
            RowBody(
                leading = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ColituFlag(ends.entry.country, 32.dp)
                        ColituIcon(ColituIcons.ArrowRight, ColituColors.muted, 13.dp, Modifier.padding(horizontal = 4.dp))
                        ColituFlag(ends.exit.country, 32.dp)
                    }
                },
                title = ColituServerNames.route(route.displayName),
                subtitle = ColituLoc["multihop.ping"],
                connected = connected,
                ping = { Ping(c.pingOf(route), unreachable(c, route)) },
            ) {
                FavoriteButton(route.id)
            }
        }
    }
}

// ── Tags ───────────────────────────────────────────────────────────────────

private data class Tag(val label: String, val service: String? = null, val category: String? = null)

/** Services the panel verified on this node first, then the use cases they do not already cover. */
private fun tagsOf(server: ColituServer): List<Tag> {
    val out = mutableListOf<Tag>()
    val serviceTags = ColituServer.SERVICE_NAMES.filterKeys { it in server.services }.map { (key, name) ->
        Tag(if (key == "youtube_adfree") ColituLoc["service.youtube_adfree"] else name, service = key)
    }
    // Ad-free YouTube and the ad-block DNS come first, so they are the ones that fit.
    out += serviceTags.filter { it.service == "youtube_adfree" }
    if (server.hostsAdBlockDns) out += Tag(ColituLoc["cat.adblock"], category = "adblock")
    out += serviceTags.filter { it.service != "youtube_adfree" }
    val hasAi = server.services.any { it in ColituServer.REQUIRED_AI_SERVICES }
    val hasStreaming = server.services.any { it in ColituServer.STREAMING_SERVICES }
    categories.drop(1).forEach { category ->
        if (category !in server.categories) return@forEach
        if (category == "ai" && hasAi) return@forEach
        if (category == "streaming" && hasStreaming) return@forEach
        out += Tag(ColituLoc["cat.$category"], category = category)
    }
    return out
}

private val TagStyle = TextStyle(
    fontFamily = ColituText.label.fontFamily,
    fontSize = 12.sp,
    lineHeight = 14.5.sp,
    fontWeight = FontWeight.Medium,
    color = ColituColors.muted,
)
private val TagIcon = 13.dp
private val TagIconGap = 5.dp
private val TagSpacing = 12.dp

/** Tags an open city row may show; the rest stay hidden. */
private const val MaxTags = 3

/** Slack per tag so rounding never squeezes a label into an ellipsis. */
private val TagSlack = 6.dp

/** One quiet line of at most three tags, as many as fit whole; no "+N", nothing cut. */
@Composable
private fun TagLine(tags: List<Tag>) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val max = with(density) { maxWidth.toPx() }
        val fixed = with(density) { (TagSlack + TagIcon + TagIconGap).toPx() }
        val spacing = with(density) { TagSpacing.toPx() }
        var used = 0f
        var count = 0
        for (i in tags.indices) {
            if (i >= MaxTags) break
            val width = fixed + measurer.measure(tags[i].label, TagStyle).size.width
            val next = used + (if (i == 0) 0f else spacing) + width
            if (next > max && i > 0) break
            used = next
            count = i + 1
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until count) {
                if (i > 0) Spacer(Modifier.width(TagSpacing))
                // A single tag wider than the row ellipsizes; several always fit whole (measured above).
                TagView(tags[i], if (count == 1) Modifier.weight(1f, fill = false) else Modifier)
            }
        }
    }
}

@Composable
private fun TagView(tag: Tag, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(TagIcon), contentAlignment = Alignment.Center) {
            if (tag.service != null) ServiceMark(tag.service, TagIcon)
            else ColituIcon(categoryIcon(tag.category.orEmpty()), ColituColors.muted, TagIcon - 1.dp)
        }
        Spacer(Modifier.width(TagIconGap))
        BasicText(tag.label, style = TagStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── Ping ───────────────────────────────────────────────────────────────────

/** Signal bars and the ping in milliseconds, in calm tones; a new value cross-fades in. */
@Composable
private fun Ping(ms: Int?, unreachable: Boolean = false) {
    val still = LocalStill.current
    if (ms == null && unreachable) {
        // A failed ping says so in words, not only with grey bars.
        BasicText(ColituLoc["server.unreachable"], style = PingTextStyle, maxLines = 2)
        return
    }
    AnimatedContent(
        targetState = ms,
        transitionSpec = {
            if (still) EnterTransition.None togetherWith ExitTransition.None
            else fadeIn(tween(220)) togetherWith fadeOut(tween(220))
        },
        contentAlignment = Alignment.CenterEnd,
        label = "ping",
    ) { value -> PingView(value) }
}

private val PingTextStyle = TextStyle(
    fontFamily = ColituText.label.fontFamily,
    fontSize = 13.sp,
    lineHeight = 17.sp,
    fontWeight = FontWeight.Medium,
    color = ColituColors.muted,
    fontFeatureSettings = "tnum",
)

@Composable
private fun PingView(ms: Int?) {
    val color = when {
        ms == null -> ColituColors.dim
        ms < 60 -> ColituColors.success.copy(alpha = 0.8f)
        ms < 150 -> ColituColors.warning.copy(alpha = 0.8f)
        else -> ColituColors.danger.copy(alpha = 0.8f)
    }
    val bars = when {
        ms == null -> 0
        ms < 60 -> 4
        ms < 100 -> 3
        ms < 200 -> 2
        else -> 1
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.Bottom) {
            for (i in 0 until 4) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height((5 + i * 3).dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(if (i < bars) color else ColituColors.lineStrong),
                )
                if (i < 3) Spacer(Modifier.width(2.dp))
            }
        }
        Spacer(Modifier.width(7.dp))
        BasicText(if (ms == null) "—" else "$ms ms", style = PingTextStyle, maxLines = 1)
    }
}

// ── Service marks ──────────────────────────────────────────────────────────

/** Small marks for the services a node opens, drawn here rather than shipped as logo files. */
@Composable
private fun ServiceMark(service: String, size: Dp) {
    when (service) {
        "netflix" -> BasicText(
            "N",
            style = TextStyle(fontSize = (size.value * 1.05f).sp, fontWeight = FontWeight.Black, color = Color(0xFFE50914), fontFamily = ColituText.label.fontFamily),
        )
        "claude" -> BasicText(
            "A\\",
            style = TextStyle(fontSize = (size.value * 0.92f).sp, fontWeight = FontWeight.ExtraBold, color = ColituColors.text, letterSpacing = (-1).sp, fontFamily = ColituText.label.fontFamily),
        )
        "gemini" -> Canvas(Modifier.size(size)) {
            val w = this.size.width
            val c = w / 2
            val path = Path().apply {
                moveTo(c, 0f)
                quadraticTo(c, c, w, c)
                quadraticTo(c, c, c, w)
                quadraticTo(c, c, 0f, c)
                quadraticTo(c, c, c, 0f)
                close()
            }
            drawPath(path, Brush.linearGradient(listOf(Color(0xFF4796E3), Color(0xFF9177C7)), start = Offset(0f, w), end = Offset(w, 0f)))
        }
        "youtube_premium", "youtube_adfree" -> Canvas(Modifier.size(size)) {
            val w = this.size.width
            drawRoundRect(Color(0xFFFF0033), topLeft = Offset(0f, w * 0.18f), size = Size(w, w * 0.64f), cornerRadius = CornerRadius(w * 0.18f))
            val play = Path().apply {
                moveTo(w * 0.40f, w * 0.34f)
                lineTo(w * 0.66f, w * 0.5f)
                lineTo(w * 0.40f, w * 0.66f)
                close()
            }
            drawPath(play, Color.White)
        }
        "chatgpt" -> Canvas(Modifier.size(size)) {
            val w = this.size.width
            val stroke = Stroke(width = w * 0.09f)
            for (i in 0 until 6) {
                rotate(i * 60f, pivot = Offset(w / 2, w / 2)) {
                    drawRoundRect(
                        ColituColors.text,
                        topLeft = Offset(w / 2 - w * 0.15f, w / 2 - w * 0.16f - w * 0.28f),
                        size = Size(w * 0.30f, w * 0.56f),
                        cornerRadius = CornerRadius(w * 0.15f),
                        style = stroke,
                    )
                }
            }
        }
        else -> ColituIcon(ColituIcons.SealCheck, ColituColors.text, size)
    }
}
