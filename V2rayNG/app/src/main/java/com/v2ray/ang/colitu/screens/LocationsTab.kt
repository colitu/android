package com.v2ray.ang.colitu.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v2ray.ang.colitu.app.ColituController
import com.v2ray.ang.colitu.data.ColituServer
import com.v2ray.ang.colitu.design.BadgeTone
import com.v2ray.ang.colitu.design.CText
import com.v2ray.ang.colitu.design.ColituBadge
import com.v2ray.ang.colitu.design.ColituChip
import com.v2ray.ang.colitu.design.ColituColors
import com.v2ray.ang.colitu.design.ColituField
import com.v2ray.ang.colitu.design.ColituFlag
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
 * Use-case categories from the panel ("all" plus streaming, gaming, privacy,
 * speed, torrent, ai). The panel adds streaming/ai itself when a node's
 * service checks pass; older panels only sent services, so those still count.
 */
private val categories = listOf("all", "streaming", "gaming", "privacy", "speed", "torrent", "ai")

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

/**
 * Server list: search, filter chips, the "best server" row and one round
 * flag row per location.
 */
@Composable
fun LocationsTab(c: ColituController, onOpenPlan: () -> Unit) {
    val loc = ColituLoc
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    val q = fold(query.trim())
    val items = c.servers
        .filter { server ->
            server.inCategory(filter) && (q.isEmpty() || fold("${c.titleOf(server)} ${server.displayName} ${server.city.orEmpty()} ${server.countryCode.orEmpty()}").contains(q))
        }
        .sortedWith(
            compareBy<ColituServer> { if (it.isAvailable) 0 else 1 }
                .thenBy { if (it.isRecommended) 0 else 1 }
                .thenBy { c.titleOf(it) },
        )
    val online = c.servers.count { it.isAvailable }

    ShellScroll(tvMaxWidth = 900.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CText(loc["locations.title"], ColituText.h1, Modifier.weight(1f))
            ColituBadge(loc.format("locations.online", "n" to online), BadgeTone.Neutral)
        }
        Spacer(Modifier.height(14.dp))
        ColituField(
            value = query,
            onChange = { query = it },
            label = null,
            hint = loc["locations.search"],
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
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            categories.forEach { value ->
                val count = c.servers.count { it.inCategory(value) }
                val label = if (value == "all") loc["locations.all"] else "${loc["cat.$value"]}  $count"
                Box(Modifier.alpha(if (value == "all" || count > 0 || filter == value) 1f else 0.5f)) {
                    ColituChip(label, filter == value) { filter = value }
                }
                Spacer(Modifier.width(8.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
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
            Spacer(Modifier.height(10.dp))
        }
        AutoRow(c)
        Spacer(Modifier.height(8.dp))
        when {
            c.servers.isEmpty() -> EmptyText(if (c.planRequired) loc["plan.noneHint"] else loc["server.none"])
            items.isEmpty() -> EmptyText(if (filter != "all" && q.isEmpty()) loc["cat.empty"] else loc["locations.empty"])
            // Two columns on a TV: the wide screen fits them and the remote needs fewer presses.
            ColituTv.isTv -> items.chunked(2).forEach { pair ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    pair.forEach { server -> ServerRow(c, server, Modifier.weight(1f).fillMaxHeight()) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
            }
            else -> items.forEachIndexed { i, server ->
                ServerRow(c, server, Modifier.reveal(40 * i.coerceAtMost(8)))
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun EmptyText(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
        CText(text, ColituText.muted)
    }
}

@Composable
private fun AutoRow(c: ColituController) {
    val loc = ColituLoc
    val active = c.autoSelection
    ColituTile(
        active = active,
        onClick = { c.selectAuto() },
        padding = PaddingValues(start = 12.dp, top = 12.dp, end = 14.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColituRoundIcon(ColituIcons.Bolt, size = 44.dp, accent = true)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                CText(loc["home.fastest"], ColituText.label)
                Spacer(Modifier.height(2.dp))
                CText(loc["server.autoHint"], ColituText.small)
            }
            Spacer(Modifier.width(10.dp))
            StateMark(
                text = if (active) (if (c.connected) loc["server.connected"] else loc["server.selected"]) else "",
                connected = active && c.connected,
            )
        }
    }
}

@Composable
private fun ServerRow(c: ColituController, server: ColituServer, modifier: Modifier) {
    val loc = ColituLoc
    val selected = !c.autoSelection && c.selectedServerId == server.id
    val connected = c.connected && c.connectedServerId == server.id
    val selectable = server.isAvailable
    val load = loadPercent(server)
    val loadText = when {
        !selectable -> loc["server.offline"]
        load == null -> ""
        load < 40 -> loc["server.load.low"]
        load < 75 -> loc["server.load.medium"]
        else -> loc["server.load.high"]
    }
    val loadColor = when {
        load == null -> ColituColors.dim
        load < 40 -> ColituColors.success
        load < 75 -> ColituColors.warning
        else -> ColituColors.danger
    }
    ColituTile(
        modifier.alpha(if (selectable) 1f else 0.5f),
        active = selected,
        onClick = if (selectable) ({ c.selectServer(server) }) else null,
        padding = PaddingValues(start = 12.dp, top = 10.dp, end = 14.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColituFlag(server.flagEmoji)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                CText(c.titleOf(server), ColituText.label, maxLines = 1)
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (loadText.isNotEmpty()) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(if (selectable) loadColor else ColituColors.dim))
                        Spacer(Modifier.width(6.dp))
                    }
                    val city = server.city?.takeIf { it.isNotBlank() }
                    CText(
                        listOfNotNull(city, loadText.takeIf { it.isNotEmpty() }).joinToString(" · ").ifEmpty { server.countryCode.orEmpty() },
                        ColituText.small,
                        maxLines = 1,
                    )
                }
                val cats = server.categories.filter { it in categories }.map { loc["cat.$it"] }
                if (cats.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    CText(cats.joinToString(" · "), ColituText.small, color = ColituColors.muted, maxLines = 1)
                }
                val services = ColituServer.SERVICE_NAMES.filterKeys { it in server.services }.values
                if (services.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    CText(services.joinToString(" · "), ColituText.small, color = ColituColors.lilac, maxLines = 1)
                }
            }
            Spacer(Modifier.width(10.dp))
            StateMark(
                text = when {
                    connected -> loc["server.connected"]
                    selected -> loc["server.selected"]
                    else -> ""
                },
                connected = connected,
            )
        }
    }
}

@Composable
private fun StateMark(text: String, connected: Boolean) {
    if (text.isEmpty()) {
        ColituIcon(ColituIcons.Ellipsis, ColituColors.dim, 18.dp)
        return
    }
    val color = if (connected) ColituColors.success else ColituColors.lilac
    Row(verticalAlignment = Alignment.CenterVertically) {
        ColituIcon(if (connected) ColituIcons.ShieldCheck else ColituIcons.CheckCircle, color, 16.dp)
        Spacer(Modifier.width(5.dp))
        CText(text, ColituText.small, color = color, weight = FontWeight.SemiBold)
    }
}
