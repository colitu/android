package com.v2ray.ang.colitu.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.v2ray.ang.colitu.app.ColituController
import com.v2ray.ang.colitu.data.ColituServer
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituBadge
import com.v2ray.ang.colitu.design.ColituChip
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituField
import com.v2ray.ang.colitu.design.ColituFlag
import com.v2ray.ang.colitu.design.ColituGradients
import com.v2ray.ang.colitu.design.ColituIcon
import com.v2ray.ang.colitu.design.ColituIcons
import com.v2ray.ang.colitu.design.ColituPanel
import com.v2ray.ang.colitu.design.ColituRadius
import com.v2ray.ang.colitu.design.ColituRoundIcon
import com.v2ray.ang.colitu.design.ColituText
import com.v2ray.ang.colitu.design.ColituTile
import com.v2ray.ang.colitu.design.ColituTv
import com.v2ray.ang.colitu.design.pressable
import com.v2ray.ang.colitu.design.reveal
import com.v2ray.ang.colitu.l10n.ColituLoc

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

/**
 * Server list, the same as on iOS: title with the "fastest server" shortcut,
 * search, category chips, a sort menu, the recommended locations and then
 * every other one. Each card shows the round flag, the city, the ping and
 * what opens there.
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
    val byName = compareBy<ColituServer> { c.titleOf(it) }.thenBy { it.city.orEmpty() }.thenBy { it.displayName }
    val items = c.servers
        .filter { server ->
            server.inCategory(filter) && (q.isEmpty() || fold("${c.titleOf(server)} ${server.displayName} ${server.city.orEmpty()} ${server.countryCode.orEmpty()}").contains(q))
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
    // A search shows matches flat; otherwise a country with several servers becomes one expandable row.
    val restEntries = groupByCountry(rest, if (c.autoSelection) null else c.selectedServerId, flat = q.isNotEmpty())
    // Multihop routes have no use-case categories: they show under "All" only. The flag and name
    // of the exit and the entry both count for the search.
    val routeItems = if (filter != "all" || !advanced) emptyList() else c.routes
        .filter { route ->
            q.isEmpty() || fold("${route.displayName} ${route.route?.entry?.label.orEmpty()} ${route.route?.exit?.label.orEmpty()} ${route.route?.entry?.country.orEmpty()} ${route.route?.exit?.country.orEmpty()}").contains(q)
        }
        .sortedWith(
            when (sort) {
                SortBy.Ping -> compareBy<ColituServer> { c.pingOf(it) ?: Int.MAX_VALUE }.then(byName)
                SortBy.Load -> compareBy<ColituServer> { loadPercent(it) ?: Int.MAX_VALUE }.then(byName)
                SortBy.Name -> byName
            },
        )

    ShellScroll(tvMaxWidth = 900.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Long translations shrink instead of being cut off.
            BasicText(
                loc["locations.title"],
                Modifier.weight(1f),
                style = ColituText.h1,
                maxLines = 1,
                softWrap = false,
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 28.sp),
            )
            Spacer(Modifier.width(10.dp))
            FastestButton(c)
        }
        Spacer(Modifier.height(4.dp))
        CText(loc["locations.tagline"], ColituText.muted)
        Spacer(Modifier.height(16.dp))
        ColituField(
            value = query,
            onChange = { query = it },
            label = null,
            hint = loc["locations.search"] + "…",
            prefix = ColituIcons.Search,
            pill = true,
            fill = ColituColors.surface2,
            suffix = if (query.isEmpty()) null else ({
                Box(
                    Modifier.size(40.dp).clip(CircleShape).pressable({ query = "" }),
                    contentAlignment = Alignment.Center,
                ) { ColituIcon(ColituIcons.XCircleOutline, ColituColors.dim, 18.dp) }
            }),
        )
        if (advanced) Spacer(Modifier.height(14.dp))
        if (advanced) Row(Modifier.horizontalScroll(rememberScrollState())) {
            categories.forEach { value ->
                if (value == "all" || value == filter || c.servers.any { it.inCategory(value) }) {
                    ColituChip(
                        if (value == "all") loc["locations.all"] else loc["cat.$value"],
                        filter == value,
                        icon = categoryIcon(value),
                    ) { chosenFilter = value }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        if (!c.planActive) {
            ColituPanel(
                padding = PaddingValues(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 12.dp),
                radius = ColituRadius.md,
                onClick = onOpenPlan,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColituRoundIcon(ColituIcons.Globe, size = 42.dp, accent = true)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        CText(loc["locations.promo"], ColituText.label)
                        Spacer(Modifier.height(2.dp))
                        CText(loc["locations.promoSub"], ColituText.small)
                    }
                    ColituIcon(ColituIcons.ChevronRight, ColituColors.dim, 16.dp)
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        when {
            c.servers.isEmpty() && c.routes.isEmpty() -> EmptyText(if (c.planRequired) loc["plan.noneHint"] else loc["server.none"])
            items.isEmpty() && routeItems.isEmpty() -> EmptyText(if (filter != "all" && q.isEmpty()) loc["cat.empty"] else loc["locations.empty"])
            else -> {
                if (items.isNotEmpty()) {
                    SectionHeader(if (recommended.isNotEmpty()) loc["locations.recommended"] else loc["locations.allServers"]) {
                        if (advanced) SortButton(sort) { chosenSort = it }
                    }
                    Spacer(Modifier.height(12.dp))
                    ServerCards(c, recommended, startIndex = 0)
                    if (recommended.isNotEmpty() && rest.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        SectionHeader(loc["locations.allServers"])
                        Spacer(Modifier.height(12.dp))
                    }
                    ServerEntries(c, restEntries, startIndex = recommended.size)
                }
                if (routeItems.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    SectionHeader(loc["multihop.section"]) {
                        if (items.isEmpty()) SortButton(sort) { chosenSort = it }
                    }
                    CText(loc["multihop.sectionHint"], ColituText.small, Modifier.padding(top = 4.dp, bottom = 12.dp))
                    routeItems.forEachIndexed { i, route ->
                        RouteCard(c, route, Modifier.reveal(40 * (items.size + i).coerceAtMost(8)))
                        Spacer(Modifier.height(listGap()))
                    }
                }
            }
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

/**
 * One column everywhere, TV included, like iOS. A TV used to get two columns
 * in a Row(IntrinsicSize.Min), but the card's TagLine is a BoxWithConstraints,
 * which cannot answer intrinsic measurements: opening Locations crashed.
 */
@Composable
private fun ServerCards(c: ColituController, servers: List<ColituServer>, startIndex: Int) {
    servers.forEachIndexed { i, server ->
        ServerCard(c, server, Modifier.reveal(40 * (startIndex + i).coerceAtMost(8)))
        Spacer(Modifier.height(listGap()))
    }
}

// Phones get dense rows (about twice as many fit on a screen); a TV keeps the large, remote-friendly sizes.
@Composable
private fun listGap(): Dp = if (ColituTv.isTv) 10.dp else 6.dp

@Composable
private fun rowPadding(): PaddingValues = if (ColituTv.isTv) PaddingValues(14.dp) else PaddingValues(horizontal = 12.dp, vertical = 9.dp)

@Composable
private fun rowRadius(): Dp = if (ColituTv.isTv) ColituRadius.md else ColituRadius.sm

@Composable
private fun flagSize(): Dp = if (ColituTv.isTv) 46.dp else 36.dp

@Composable
private fun buttonSize(): Dp = if (ColituTv.isTv) 40.dp else 32.dp

/**
 * Country (or city) on one line with the secondary text after it ("Almanya · Frankfurt"). The
 * name is measured first, so it is never cut for the sake of the secondary text; on a TV the
 * secondary text keeps its own line below.
 */
@Composable
private fun NameLine(primary: String, secondary: String?, connected: Boolean, secondaryBelow: Boolean = false) {
    val loc = ColituLoc
    if (!ColituTv.isTv && secondaryBelow && secondary != null) {
        // Country headers: the "N locations" count always reads in full, on its own line.
        CText(primary, ColituText.label, size = 15.5.sp, maxLines = 2)
        CText(secondary, ColituText.small, maxLines = 1)
        return
    }
    if (ColituTv.isTv) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CText(primary, ColituText.label, Modifier.weight(1f, fill = false), size = 17.sp, maxLines = 1)
            if (connected) {
                Spacer(Modifier.width(8.dp))
                Pill(loc["server.connected"], accent = true)
            }
        }
        if (secondary != null) {
            Spacer(Modifier.height(2.dp))
            CText(secondary, ColituText.muted, maxLines = 1)
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CText(primary, ColituText.label, size = 15.5.sp, maxLines = 2)
        if (connected) {
            Spacer(Modifier.width(6.dp))
            Pill(loc["server.connected"], accent = true, small = true)
        }
        if (secondary != null) {
            Spacer(Modifier.width(6.dp))
            CText("· $secondary", ColituText.small, Modifier.weight(1f, fill = false), maxLines = 1)
        }
    }
}

/** The grouped "all servers" list: single rows as before, countries with several cities as expandable groups. */
@Composable
private fun ServerEntries(c: ColituController, entries: List<LocationEntry>, startIndex: Int) {
    entries.forEachIndexed { i, entry ->
        val modifier = Modifier.reveal(40 * (startIndex + i).coerceAtMost(8))
        when (entry) {
            is LocationEntry.Single -> key(entry.server.id) { ServerCard(c, entry.server, modifier) }
            is LocationEntry.Group -> key("group-" + entry.countryCode) { CountryGroup(c, entry, modifier) }
        }
        Spacer(Modifier.height(listGap()))
    }
}

/**
 * A country with several servers: the header (flag, name, how many locations, best ping,
 * chevron) expands to one indented row per city. Both are ordinary focusable tiles, so a
 * TV remote opens the group with OK and walks into the cities with Down.
 */
@Composable
private fun CountryGroup(c: ColituController, group: LocationEntry.Group, modifier: Modifier) {
    val loc = ColituLoc
    var expanded by rememberSaveable { mutableStateOf(group.expandedByDefault) }
    val name = loc.countryName(group.countryCode).takeIf { it.isNotBlank() } ?: group.servers.first().displayName
    val holdsChosen = group.servers.any { s ->
        (!c.autoSelection && c.selectedServerId == s.id) || (c.connected && c.connectedServerId == s.id)
    }
    val best = bestPing(group.servers) { c.pingOf(it) }
    val anyOnline = group.servers.any { it.isAvailable }
    Column(modifier) {
        ColituTile(
            Modifier.alpha(if (anyOnline) 1f else 0.5f),
            // A collapsed header carries the highlight of the chosen city inside it.
            active = !expanded && holdsChosen,
            onClick = { expanded = !expanded },
            padding = rowPadding(),
            radius = rowRadius(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColituFlag(group.countryCode, flagSize())
                Spacer(Modifier.width(if (ColituTv.isTv) 12.dp else 10.dp))
                Column(Modifier.weight(1f)) {
                    // The name keeps priority (it wraps to two lines before it is cut: "Birleşik Krallık"
                    // on a 360dp phone); the location count follows on the same line when there is room.
                    NameLine(name, loc.count("locationCount", group.servers.size), connected = false, secondaryBelow = true)
                }
                Spacer(Modifier.width(8.dp))
                if (anyOnline) Ping(best) else CText(loc["server.offline"], ColituText.small)
                Spacer(Modifier.width(if (ColituTv.isTv) 12.dp else 8.dp))
                ExpandButton(expanded, holdsChosen)
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(220)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        ) {
            Column(Modifier.padding(start = if (ColituTv.isTv) 18.dp else 14.dp)) {
                group.servers.forEach { server ->
                    Spacer(Modifier.height(if (ColituTv.isTv) 8.dp else 5.dp))
                    key(server.id) { CityRow(c, server) }
                }
            }
        }
    }
}

/** One city inside an expanded country: city name, ping, a check when it is the chosen one. */
@Composable
private fun CityRow(c: ColituController, server: ColituServer) {
    val loc = ColituLoc
    val selected = !c.autoSelection && c.selectedServerId == server.id
    val connected = c.connected && c.connectedServerId == server.id
    val selectable = server.isAvailable
    val label = server.city?.trim().orEmpty().ifEmpty { server.displayName }
    val tags = tagsOf(server)
    ColituTile(
        Modifier.alpha(if (selectable) 1f else 0.5f),
        active = selected || connected,
        onClick = if (selectable) ({ c.selectServer(server) }) else null,
        padding = if (ColituTv.isTv) PaddingValues(horizontal = 14.dp, vertical = 12.dp) else PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        radius = ColituRadius.sm,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    CText(label, ColituText.label, Modifier.weight(1f, fill = false), size = if (ColituTv.isTv) 16.sp else 15.sp, maxLines = 1)
                    if (connected) {
                        Spacer(Modifier.width(8.dp))
                        Pill(loc["server.connected"], accent = true)
                    }
                }
                Spacer(Modifier.width(8.dp))
                if (selectable) Ping(c.pingOf(server)) else CText(loc["server.offline"], ColituText.small)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    if (selected || connected) ColituIcon(ColituIcons.Check, ColituColors.lilac, 18.dp)
                }
            }
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(if (ColituTv.isTv) 10.dp else 5.dp))
                TagLine(tags, Modifier, compact = !ColituTv.isTv)
            }
        }
    }
}

/** The group header's round button: the look of [GoButton], with a chevron that turns over. */
@Composable
private fun ExpandButton(expanded: Boolean, active: Boolean) {
    val turn by animateFloatAsState(if (expanded) 180f else 0f, tween(200), label = "chevron")
    val lit = active && !expanded
    Box(
        Modifier
            .size(buttonSize())
            .clip(CircleShape)
            .then(
                if (lit) Modifier.background(ColituGradients.accent)
                else Modifier.background(ColituColors.surface2).border(1.dp, ColituColors.lineStrong, CircleShape),
            ),
        contentAlignment = Alignment.Center,
    ) {
        ColituIcon(
            ColituIcons.ChevronDown,
            if (lit) ColituColors.onAccent else ColituColors.text,
            if (ColituTv.isTv) 17.dp else 15.dp,
            Modifier.graphicsLayer { rotationZ = turn },
        )
    }
}

@Composable
private fun EmptyText(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
        CText(text, ColituText.muted)
    }
}

@Composable
private fun SectionHeader(title: String, trailing: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CText(title, ColituText.h2.copy(fontSize = 20.sp), Modifier.weight(1f), maxLines = 1)
        trailing?.invoke()
    }
}

/** "Fastest server" in the title row: picks the server automatically. */
@Composable
private fun FastestButton(c: ColituController) {
    val active = c.connectsAutomatically
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .pressable({ c.selectAuto() })
            .clip(shape)
            .background(if (active) ColituColors.violet.copy(alpha = 0.18f) else ColituColors.surface2)
            .border(1.dp, if (active) ColituColors.violet else ColituColors.lineStrong, shape)
            .padding(start = 12.dp, top = 10.dp, end = 10.dp, bottom = 10.dp),
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

@Composable
private fun SortButton(sort: SortBy, onChange: (SortBy) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box {
        Row(
            Modifier
                .pressable({ open = !open })
                .clip(shape)
                .background(ColituColors.surface2)
                .border(1.dp, ColituColors.line, shape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColituIcon(ColituIcons.Sort, ColituColors.muted, 16.dp)
            Spacer(Modifier.width(6.dp))
            CText(ColituLoc["locations.sort.${sort.name.lowercase()}"], ColituText.small, color = ColituColors.text, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            ColituIcon(ColituIcons.ChevronDown, ColituColors.muted, 12.dp)
        }
        if (open) {
            val offsetY = with(LocalDensity.current) { 44.dp.roundToPx() }
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
                        .clip(RoundedCornerShape(ColituRadius.md))
                        .background(ColituColors.surface2)
                        .border(1.dp, ColituColors.lineStrong, RoundedCornerShape(ColituRadius.md))
                        .padding(vertical = 6.dp),
                ) {
                    SortBy.entries.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pressable({ onChange(option); open = false })
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

private data class Tag(val label: String, val service: String? = null, val category: String? = null)

/** Services the panel verified on this node first, then the use cases they do not already cover. */
private fun tagsOf(server: ColituServer): List<Tag> {
    val out = mutableListOf<Tag>()
    val serviceTags = ColituServer.SERVICE_NAMES.filterKeys { it in server.services }.map { (key, name) ->
        Tag(if (key == "youtube_adfree") ColituLoc["service.youtube_adfree"] else name, service = key)
    }
    // Ad-free YouTube and the ad-block DNS come first, so they never fold into the "+N" pill.
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

@Composable
private fun ServerCard(c: ColituController, server: ColituServer, modifier: Modifier) {
    val loc = ColituLoc
    val selected = !c.autoSelection && c.selectedServerId == server.id
    val connected = c.connected && c.connectedServerId == server.id
    val selectable = server.isAvailable
    val country = server.countryCode?.let { loc.countryName(it) }?.takeIf { it.isNotBlank() } ?: server.displayName
    val city = server.city?.trim().orEmpty()
    val tags = tagsOf(server)
    ColituTile(
        modifier.alpha(if (selectable) 1f else 0.5f),
        active = selected || connected,
        onClick = if (selectable) ({ c.selectServer(server) }) else null,
        padding = rowPadding(),
        radius = rowRadius(),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColituFlag(server.countryCode, flagSize())
                Spacer(Modifier.width(if (ColituTv.isTv) 12.dp else 10.dp))
                Column(Modifier.weight(1f)) {
                    NameLine(country, city.takeIf { it.isNotEmpty() && !it.equals(country, ignoreCase = true) }, connected)
                }
                Spacer(Modifier.width(8.dp))
                if (selectable) Ping(c.pingOf(server)) else CText(loc["server.offline"], ColituText.small)
                Spacer(Modifier.width(if (ColituTv.isTv) 12.dp else 8.dp))
                GoButton(selected || connected)
            }
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(if (ColituTv.isTv) 12.dp else 5.dp))
                TagLine(tags, Modifier.padding(start = if (ColituTv.isTv) 58.dp else 46.dp), compact = !ColituTv.isTv)
            }
        }
    }
}

/**
 * A multihop route: entry flag, arrow and exit flag, the route name and the
 * ping to the entry node (an estimate: the second hop is not measured).
 */
@Composable
private fun RouteCard(c: ColituController, route: ColituServer, modifier: Modifier) {
    val loc = ColituLoc
    val ends = route.route ?: return
    val selected = !c.autoSelection && c.selectedServerId == route.id
    val connected = c.connected && c.connectedServerId == route.id
    ColituTile(
        modifier,
        active = selected || connected,
        onClick = { c.selectServer(route) },
        padding = rowPadding(),
        radius = rowRadius(),
    ) {
        if (!ColituTv.isTv) {
            // Phone: small flags, the route name on one line (ellipsis on the exit if it must), and
            // the estimate note with the ping on the second line.
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColituFlag(ends.entry.country, 22.dp)
                Spacer(Modifier.width(3.dp))
                ColituIcon(ColituIcons.ArrowRight, ColituColors.muted, 10.dp)
                Spacer(Modifier.width(3.dp))
                ColituFlag(ends.exit.country, 22.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CText(route.displayName, ColituText.label, Modifier.weight(1f, fill = false), size = 14.5.sp, maxLines = 1)
                        if (connected) {
                            Spacer(Modifier.width(6.dp))
                            Pill(loc["server.connected"], accent = true, small = true)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CText(loc["multihop.ping"], ColituText.small, Modifier.weight(1f), size = 11.sp, maxLines = 1)
                        Spacer(Modifier.width(6.dp))
                        Ping(c.pingOf(route))
                    }
                }
                Spacer(Modifier.width(8.dp))
                GoButton(selected || connected)
            }
            return@ColituTile
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColituFlag(ends.entry.country, 30.dp)
            Spacer(Modifier.width(4.dp))
            ColituIcon(ColituIcons.ArrowRight, ColituColors.muted, 12.dp)
            Spacer(Modifier.width(4.dp))
            ColituFlag(ends.exit.country, 30.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                CText(route.displayName, ColituText.label, size = 16.sp, maxLines = 2)
                Spacer(Modifier.height(2.dp))
                CText(loc["multihop.ping"], ColituText.small, maxLines = 1)
                if (connected) {
                    Spacer(Modifier.height(4.dp))
                    Pill(loc["server.connected"], accent = true)
                }
            }
            Spacer(Modifier.width(8.dp))
            Ping(c.pingOf(route))
            Spacer(Modifier.width(if (ColituTv.isTv) 12.dp else 8.dp))
            GoButton(selected || connected)
        }
    }
}

private val tagStyle = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = ColituColors.text)
private val pillStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ColituColors.muted)
private val tagIcon = 17.dp
private val tagIconGap = 5.dp
private val tagSpacing = 10.dp

/** One line of tags: as many as fit, then "+N" for the rest. */
@Composable
private fun TagLine(tags: List<Tag>, modifier: Modifier, compact: Boolean = false) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Compact (phone rows): smaller chips and at most two tags, then "+N".
    val style = if (compact) tagStyle.copy(fontSize = 11.sp) else tagStyle
    val icon = if (compact) 14.dp else tagIcon
    val spacingDp = if (compact) 6.dp else tagSpacing
    val limit = if (compact) 2 else Int.MAX_VALUE
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val max = with(density) { maxWidth.toPx() }
        fun textPx(text: String, style: TextStyle) = measurer.measure(text, style.copy(fontFamily = ColituText.label.fontFamily)).size.width.toFloat()
        val slack = with(density) { 6.dp.toPx() }
        val spacing = with(density) { spacingDp.toPx() }
        val fixed = with(density) { (icon + tagIconGap).toPx() }
        val pillPad = with(density) { (if (compact) 16.dp else 22.dp).toPx() }
        val pillText = if (compact) pillStyle.copy(fontSize = 11.sp) else pillStyle
        var used = 0f
        var count = 0
        for (i in tags.indices) {
            if (i >= limit) break
            val width = slack + fixed + textPx(tags[i].label, style)
            val next = used + (if (i == 0) 0f else spacing) + width
            val left = tags.size - i - 1
            val reserve = if (left > 0) spacing + textPx("+$left", pillText) + pillPad else 0f
            if (next + reserve > max && i > 0) break
            used = next
            count = i + 1
        }
        val hidden = tags.size - count
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until count) {
                if (i > 0) Spacer(Modifier.width(spacingDp))
                // Wraps its content: a weighted chip would share the row equally and cut the first label.
                TagView(tags[i], Modifier, style, icon)
            }
            if (hidden > 0) {
                Spacer(Modifier.width(spacingDp))
                Pill("+$hidden", small = compact)
            }
        }
    }
}

@Composable
private fun TagView(tag: Tag, modifier: Modifier, style: TextStyle = tagStyle, iconSize: Dp = tagIcon) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
            if (tag.service != null) ServiceMark(tag.service, iconSize)
            else ColituIcon(categoryIcon(tag.category.orEmpty()), ColituColors.text, iconSize - 1.dp)
        }
        Spacer(Modifier.width(tagIconGap))
        BasicText(tag.label, style = style.copy(fontFamily = ColituText.label.fontFamily), maxLines = 1)
    }
}

@Composable
private fun Pill(text: String, accent: Boolean = false, small: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (accent) ColituColors.violet.copy(alpha = 0.22f) else ColituColors.surface2)
            .then(if (accent) Modifier else Modifier.border(1.dp, ColituColors.line, shape))
            .padding(horizontal = if (small) 7.dp else 9.dp, vertical = if (small) 2.dp else 3.dp),
    ) {
        BasicText(
            text,
            style = pillStyle.copy(
                fontFamily = ColituText.label.fontFamily,
                fontSize = if (small) 11.sp else pillStyle.fontSize,
                color = if (accent) ColituColors.lilac else ColituColors.muted,
            ),
            maxLines = 1,
        )
    }
}

/** Signal bars and the ping in milliseconds. */
@Composable
private fun Ping(ms: Int?) {
    val color = when {
        ms == null -> ColituColors.dim
        ms < 60 -> ColituColors.success
        ms < 150 -> ColituColors.warning
        else -> ColituColors.danger
    }
    val bars = when {
        ms == null -> 0
        ms < 60 -> 4
        ms < 100 -> 3
        ms < 200 -> 2
        else -> 1
    }
    Row(verticalAlignment = Alignment.Bottom) {
        for (i in 0 until 4) {
            Box(
                Modifier
                    .width(3.5.dp)
                    .height((6 + i * 3).dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(if (i < bars) color else ColituColors.lineStrong),
            )
            if (i < 3) Spacer(Modifier.width(2.dp))
        }
        Spacer(Modifier.width(8.dp))
        CText(if (ms == null) "—" else "$ms ms", ColituText.small, maxLines = 1)
    }
}

@Composable
private fun GoButton(active: Boolean) {
    Box(
        Modifier
            .size(buttonSize())
            .clip(CircleShape)
            .then(
                if (active) Modifier.background(ColituGradients.accent)
                else Modifier.background(ColituColors.surface2).border(1.dp, ColituColors.lineStrong, CircleShape),
            ),
        contentAlignment = Alignment.Center,
    ) {
        ColituIcon(ColituIcons.ChevronRight, if (active) ColituColors.onAccent else ColituColors.text, if (ColituTv.isTv) 17.dp else 15.dp)
    }
}

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
