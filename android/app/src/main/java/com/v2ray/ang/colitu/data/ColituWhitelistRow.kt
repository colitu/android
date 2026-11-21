package com.v2ray.ang.colitu.data

/**
 * The virtual "Whitelist" row: for a client in Russia, every server that is
 * whitelist_ok and offers a whitelist transport gets an extra row at the very
 * top (one per server). Picking it connects to that node with only the
 * whitelist transports, the relay one preferred; the node's normal row is
 * untouched. Display and protocol only, never the AC decision.
 */
object ColituWhitelistRow {
    const val WS = "vless-ws"
    const val RELAY = "vless-reality-relay"

    /** The whitelist transports, preferred first. They never take part in a normal row's fallback order. */
    val PROTOCOLS: List<String> = listOf(RELAY, WS)
    private const val PREFIX = "whitelist:"

    fun offers(server: ColituServer): Boolean = PROTOCOLS.any { it in server.protocols }

    fun eligible(server: ColituServer): Boolean =
        server.whitelistOk && offers(server) && server.isAvailable && !server.isMultihop

    fun applies(clientCountry: String?): Boolean = clientCountry?.trim().equals("RU", ignoreCase = true)

    /** The servers that get a virtual row (empty unless the last known country is RU), in list order. */
    fun rows(servers: List<ColituServer>, clientCountry: String?): List<ColituServer> =
        if (applies(clientCountry)) servers.filter { eligible(it) } else emptyList()

    /** Words that find every whitelist row in the search, whatever the app language. */
    val SEARCH_WORDS: List<String> = listOf("beyaz liste", "whitelist", "белый список", "白名单", "لیست سفید")

    /**
     * What a whitelist row is found by: the whitelist words, the country and city in every
     * app language, and the panel's own name, city and country code; lower-cased.
     */
    fun searchText(server: ColituServer): String {
        val parts = ArrayList<String>(SEARCH_WORDS)
        for (lang in com.v2ray.ang.colitu.l10n.ColituLoc.languages) {
            server.countryCode?.takeIf { it.isNotBlank() }?.let { parts += com.v2ray.ang.colitu.l10n.ColituLoc.countryName(it, lang) }
            parts += com.v2ray.ang.colitu.l10n.ColituServerNames.city(server.city, lang)
            parts += com.v2ray.ang.colitu.l10n.ColituServerNames.displayName(server.displayName, countryCode = server.countryCode, language = lang)
        }
        parts += listOf(server.displayName, server.city.orEmpty(), server.countryCode.orEmpty())
        return parts.joinToString(" ").lowercase()
    }

    /** Whether the search [query] (any case, trimmed or not) finds the row of [server]; an empty query finds every row. */
    fun matches(server: ColituServer, query: String): Boolean {
        val q = query.trim().lowercase()
        return q.isEmpty() || searchText(server).contains(q)
    }

    /**
     * The home protocol chip while a whitelist row is picked: Whitelist is a way in, not a
     * protocol, so the chip shows it (locked, no menu) whatever method the user picked; the
     * user's pick stays stored and is back once a normal server is picked. Null: the normal chip.
     */
    fun chipLabel(rowSelected: Boolean, automatic: Boolean, whitelistLabel: String): String? =
        if (rowSelected && !automatic) whitelistLabel else null

    /** The stored selection of a whitelist row: "whitelist:<nodeId>". */
    fun selectionKey(nodeId: String): String = PREFIX + nodeId

    /** The node id of a stored selection key, or null when it is not a whitelist row. */
    fun nodeOf(key: String?): String? = key?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { it.isNotEmpty() }

    /** The whitelist transport a connect to [server] prefers (relay before ws), or null when it offers none. */
    fun preferred(server: ColituServer): String? = PROTOCOLS.firstOrNull { it in server.protocols }

    /**
     * The preferred whitelist transport when a connect to [server] must use one: its virtual row
     * is the selection, or the automatic choice in whitelist mode landed on a whitelist_ok node that offers one.
     */
    fun forcedProtocol(server: ColituServer, rowSelected: Boolean, whitelistMode: Boolean, automatic: Boolean): String? =
        if (rowSelected || (whitelistMode && automatic && server.whitelistOk)) preferred(server) else null

    /** Only the whitelist transports, relay first: no fallback to the normal ones for this choice. */
    fun <T> forcedOrder(configs: List<T>, protocolOf: (T) -> String?): List<T> =
        PROTOCOLS.flatMap { p -> configs.filter { protocolOf(it) == p } }

    /** A normal connect: whitelist transports left out (kept only when nothing else is on offer). */
    fun <T> normalOrder(configs: List<T>, protocolOf: (T) -> String?): List<T> =
        configs.filter { protocolOf(it) !in PROTOCOLS }.ifEmpty { configs }

    /**
     * The panel's blocked_servers as they apply to ordinary rows and automatic choice. A whitelist
     * row and whitelist mode dial the relay / CDN host, never the node's own address, so there
     * the whitelist-eligible servers are not demoted by an address block.
     */
    fun addressBlocked(servers: List<ColituServer>, blocked: Set<String>, whitelistMode: Boolean): Set<String> {
        if (blocked.isEmpty() || !whitelistMode) return blocked
        val exempt = servers.filter { eligible(it) }.map { it.id }.toSet()
        return blocked - exempt
    }

    /**
     * Never hidden by the local reachability check (pings to the node's own address): the picked
     * whitelist row's server and, in whitelist mode, every whitelist server; they are reached
     * through the relay / CDN host, not their own address.
     */
    fun keepVisible(servers: List<ColituServer>, rowNodeId: String?, whitelistMode: Boolean): Set<String> =
        buildSet {
            rowNodeId?.let(::add)
            if (whitelistMode) servers.filter { eligible(it) }.mapTo(this) { it.id }
        }
}
