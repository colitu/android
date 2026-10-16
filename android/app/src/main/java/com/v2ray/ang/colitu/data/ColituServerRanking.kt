package com.v2ray.ang.colitu.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * One ping of a location: [ms] null means the probe timed out or the node was
 * unreachable. [measuredAt] is wall-clock ms, [network] the network key it was
 * measured on (see [ColituServerRanking.networkKey]).
 */
data class ColituPing(val ms: Int?, val measuredAt: Long, val network: String) {
    fun fresh(network: String, now: Long): Boolean =
        this.network == network && now - measuredAt in 0 until ColituServerRanking.PING_FRESH_MS

    /** A successful ping still orders the servers up to [ColituServerRanking.PING_USABLE_MS] on its network. */
    fun usable(network: String, now: Long): Boolean =
        ms != null && this.network == network && now - measuredAt in 0 until ColituServerRanking.PING_USABLE_MS
}

/**
 * Order of the locations for automatic mode (Adaptive Connect v2), without
 * Android state so it can be unit tested. The "Recommended" entry and the
 * automatic connect target are both the first of [rank], so the screen never
 * disagrees with what connect does. A ping only hints; real traffic decides
 * through the per-network [ColituConnectMemory].
 */
object ColituServerRanking {
    const val PING_FRESH_MS = 10 * 60_000L

    /**
     * A successful ping older than [PING_FRESH_MS] (same network) still orders the servers for
     * this long, after a network change or a wake-up, instead of dropping back to the panel's
     * order, in which a far server can come first. A failed ping is only believed while fresh.
     */
    const val PING_USABLE_MS = 6 * 60 * 60_000L

    /** "<link>|<client_network>", link = wifi / cellular / ethernet / other / none. */
    fun networkKey(link: String, clientNetwork: String?): String = "$link|${clientNetwork.orEmpty()}"

    /** Every underlying network is new (none of [previous] is still up): another access network. */
    fun networkReplaced(previous: Set<String>?, current: Set<String>): Boolean =
        previous != null && previous.isNotEmpty() && current.isNotEmpty() && previous.none { it in current }

    /**
     * Server countries automatic mode never picks. A Russian exit carries
     * the same blocks the user wants to get away from, wherever the user is.
     * A manual choice still connects there, and a multihop route that only
     * enters in Russia is not affected (routes are never picked anyway).
     */
    val AUTO_EXCLUDED_COUNTRIES = setOf("RU")

    fun autoExcluded(server: ColituServer): Boolean =
        server.countryCode?.trim()?.uppercase() in AUTO_EXCLUDED_COUNTRIES || (server.alpha && !ColituCslStore.autoAlpha)

    /**
     * Client countries where a server in the client's own country cannot open
     * the sites blocked there, so automatic mode puts such servers after the
     * foreign ones. Everywhere else a home-country server competes on latency
     * like any other (a fast local node is the better choice, not the worse).
     */
    val HOME_PUSHBACK_COUNTRIES = setOf(
        "RU", "BY", "KZ", "UZ", "TM", "TJ", "KG", "AZ", "IR", "CN", "TR", "VN", "PK", "EG", "SA", "AE", "ET", "MM",
    )

    /** The remembered server yields to a fresh candidate at least this much faster (fraction of its latency)... */
    const val MEMORY_BEATEN_FRACTION = 0.4

    /** ...and at least this many ms faster. */
    const val MEMORY_BEATEN_MS = 60

    /**
     * Available nodes (no multihop routes, none in [AUTO_EXCLUDED_COUNTRIES]) best first; the same
     * rules on every platform (shared test vectors, colitu-csl engine/testdata/ac-vectors):
     * 1. penalized on this network last, the oldest penalty ([penaltyAt]) first so retries rotate;
     * 2. a fresh failed ping after all others except the penalized;
     * 3. the user's own country after the foreign ones, only when the client is in one of
     *    [HOME_PUSHBACK_COUNTRIES] (censoring countries);
     * 4. the last good server on this network first (remembered from automatic connections
     *    only), unless its ping is known and a candidate of the same standing is at least
     *    [MEMORY_BEATEN_FRACTION] and [MEMORY_BEATEN_MS] ms faster;
     * 5. successful pings (fresh, or up to [PING_USABLE_MS] old on this network) ascending;
     * 6. without a usable ping in panel order, after the pinged ones.
     */
    fun rank(
        servers: List<ColituServer>,
        pings: Map<String, ColituPing>,
        network: String,
        lastGood: String?,
        penalized: Set<String>,
        clientCountry: String?,
        now: Long,
        penaltyAt: Map<String, Long> = emptyMap(),
    ): List<ColituServer> {
        val country = clientCountry?.trim()?.uppercase()?.takeIf { it.length == 2 }
        val pushHome = country != null && country in HOME_PUSHBACK_COUNTRIES
        val candidates = servers.withIndex().filter { (_, server) -> server.isAvailable && !server.isMultihop && !autoExcluded(server) }
        fun isHome(server: ColituServer) = pushHome && server.countryCode?.trim()?.uppercase() == country
        fun failedPing(id: String) = pings[id]?.let { ping -> ping.ms == null && ping.fresh(network, now) } == true
        // Servers of the same standing (penalty, failed ping, home pushback) compete with the remembered one.
        fun standing(server: ColituServer) = Triple(server.id in penalized, failedPing(server.id), isHome(server))
        val rememberedMs = lastGood?.let { usableMs(pings[it], network, now) }
        val remembered = candidates.firstOrNull { it.value.id == lastGood }?.value
        val memoryBeaten = remembered != null && rememberedMs != null && candidates.any { (_, other) ->
            val ms = usableMs(pings[other.id], network, now)
            other.id != remembered.id && ms != null && standing(other) == standing(remembered) &&
                rememberedMs - ms >= MEMORY_BEATEN_MS && ms <= rememberedMs * (1 - MEMORY_BEATEN_FRACTION)
        }
        val ordered = candidates.sortedWith(
            compareBy<IndexedValue<ColituServer>> { it.value.id in penalized }
                .thenBy { if (it.value.id in penalized) penaltyAt[it.value.id] ?: 0L else 0L }
                .thenBy { failedPing(it.value.id) }
                .thenBy { isHome(it.value) }
                .thenBy { memoryBeaten || it.value.id != lastGood }
                .thenBy { usableMs(pings[it.value.id], network, now) ?: Int.MAX_VALUE }
                .thenBy { it.index },
        )
        return ordered.map { it.value }
    }

    /**
     * After every transport of a server failed: the next ranked server not
     * tried yet, only in automatic mode, below [maxServers] and while the
     * connect's budget lasts. A manual choice is never switched (null: the
     * error offers the fastest server instead).
     */
    fun fallbackServer(automatic: Boolean, ranked: List<ColituServer>, failed: List<String>, maxServers: Int, budgetLeft: Boolean): ColituServer? =
        if (!automatic || failed.size >= maxServers || !budgetLeft) null else ranked.firstOrNull { it.id !in failed }

    /** A successful ping that may order the servers (fresh, or up to [PING_USABLE_MS] old on this network). */
    private fun usableMs(ping: ColituPing?, network: String, now: Long): Int? =
        ping?.takeIf { it.usable(network, now) }?.ms

    // ── Ping storage ───────────────────────────────────────────────────────

    fun pingsToJson(pings: Map<String, ColituPing>): String = JsonObject().apply {
        pings.forEach { (id, ping) ->
            add(id, JsonObject().apply {
                ping.ms?.let { addProperty("ms", it) }
                addProperty("at", ping.measuredAt)
                addProperty("net", ping.network)
            })
        }
    }.toString()

    /** Also reads the old format (id -> ms), as pings that are never fresh. */
    fun pingsFromJson(raw: String?): Map<String, ColituPing> = runCatching {
        if (raw.isNullOrBlank()) return emptyMap()
        JsonParser.parseString(raw).asJsonObject.entrySet().mapNotNull { (id, value) ->
            when {
                value.isJsonPrimitive -> id to ColituPing(value.asInt, 0L, "")
                value.isJsonObject -> {
                    val o = value.asJsonObject
                    val ms = o.get("ms")?.takeIf { it.isJsonPrimitive }?.asInt
                    val at = o.get("at")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
                    val net = o.get("net")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    id to ColituPing(ms, at, net)
                }
                else -> null
            }
        }.toMap()
    }.getOrDefault(emptyMap())
}

/**
 * What worked and what failed, per network key, each with its own expiry:
 * last good server (24 h), last good transport per server (24 h), stalled
 * transport per server and per network (6 h), penalized server (30 min).
 * Memory of another network is never applied; it stays stored under its own
 * key until it expires. Pure, so the expiry is unit tested.
 */
class ColituConnectMemory private constructor(private val entries: MutableMap<String, Entry>) {
    data class Entry(val value: String, val at: Long)

    fun lastGoodServer(network: String, now: Long): String? = valueOf(goodServerKey(network), now)

    fun lastGoodTransport(network: String, serverId: String, now: Long): String? = valueOf(goodTransportKey(network, serverId), now)

    /** Transports that stalled on [serverId] or on any server of this network (confirmed or tentative). */
    fun stalled(network: String, serverId: String, now: Long): Set<String> {
        val prefixes = listOf(
            stalledPrefix(network, serverId), stalledPrefix(network, ANY),
            tentativePrefix(network, serverId), tentativePrefix(network, ANY),
        )
        return entries.keys
            .filter { key -> prefixes.any { key.startsWith(it) } && alive(key, now) }
            .map { it.substringAfterLast('|') }
            .toSet()
    }

    /**
     * [transport] carried real traffic on this network (any server) in the
     * last 24 h: its own record, or a server's last good transport here
     * (written before the network-wide record existed, and kept per server).
     */
    fun workedOnNetwork(network: String, transport: String, now: Long): Boolean {
        if (valueOf(workedKey(network, transport), now) != null) return true
        val prefix = "$GOOD_TRANSPORT|$network|"
        return entries.any { (key, entry) -> key.startsWith(prefix) && entry.value == transport && alive(key, now) }
    }

    fun penalized(network: String, now: Long): Set<String> = penaltyTimes(network, now).keys

    /** When each penalized server of this network was penalized (the ranking rotates them, oldest first). */
    fun penaltyTimes(network: String, now: Long): Map<String, Long> {
        val prefix = "$PENALTY|$network|"
        return entries.filter { (key, _) -> key.startsWith(prefix) && alive(key, now) }
            .entries.associate { (key, entry) -> key.removePrefix(prefix) to entry.at }
    }

    /**
     * Real traffic flowed: remember the transport (and, unless [goodServer]
     * is false for a multihop route, the server) and forget their failures.
     */
    fun recordSuccess(network: String, serverId: String, transport: String?, now: Long, goodServer: Boolean = true) {
        if (goodServer) put(goodServerKey(network), serverId, now)
        entries.remove("$PENALTY|$network|$serverId")
        if (transport.isNullOrBlank()) return
        put(goodTransportKey(network, serverId), transport, now)
        put(workedKey(network, transport), "1", now)
        entries.remove(stalledPrefix(network, serverId) + transport)
        entries.remove(stalledPrefix(network, ANY) + transport)
        entries.remove(tentativePrefix(network, serverId) + transport)
        entries.remove(tentativePrefix(network, ANY) + transport)
    }

    /**
     * [transport] came up on [serverId] but carried nothing (the device itself
     * was online). Not [confirmed], the mark lasts [TENTATIVE_STALL_TTL_MS]:
     * a round in which everything fails says more about the network than
     * about the transports. [confirm] makes it a 6 h one once another
     * transport carried traffic on this network.
     */
    fun markStalled(network: String, serverId: String, transport: String, now: Long, confirmed: Boolean = true) {
        val prefix = if (confirmed) ::stalledPrefix else ::tentativePrefix
        put(prefix(network, serverId) + transport, "1", now)
        put(prefix(network, ANY) + transport, "1", now)
        if (valueOf(goodTransportKey(network, serverId), now) == transport) entries.remove(goodTransportKey(network, serverId))
    }

    /** Another transport worked on this network: the tentative stalls of [transports] on [serverId] become 6 h ones. */
    fun confirm(network: String, serverId: String, transports: Collection<String>, now: Long) {
        transports.forEach { transport ->
            if (alive(tentativePrefix(network, serverId) + transport, now) || alive(tentativePrefix(network, ANY) + transport, now)) {
                entries.remove(tentativePrefix(network, serverId) + transport)
                entries.remove(tentativePrefix(network, ANY) + transport)
                markStalled(network, serverId, transport, now, confirmed = true)
            }
        }
    }

    /** Every transport of [serverId] failed on this network. */
    fun penalize(network: String, serverId: String, now: Long) {
        put("$PENALTY|$network|$serverId", "1", now)
        if (valueOf(goodServerKey(network), now) == serverId) entries.remove(goodServerKey(network))
    }

    /** Drops expired entries, then the oldest ones beyond [MAX_ENTRIES]. */
    fun prune(now: Long) {
        entries.keys.filterNot { alive(it, now) }.forEach { entries.remove(it) }
        if (entries.size > MAX_ENTRIES) {
            entries.entries.sortedBy { it.value.at }.take(entries.size - MAX_ENTRIES).map { it.key }.forEach { entries.remove(it) }
        }
    }

    val size: Int get() = entries.size

    fun toJson(): String = JsonObject().apply {
        entries.forEach { (key, entry) ->
            add(key, JsonObject().apply {
                addProperty("v", entry.value)
                addProperty("t", entry.at)
            })
        }
    }.toString()

    private fun valueOf(key: String, now: Long): String? = entries[key]?.takeIf { alive(key, now) }?.value

    private fun put(key: String, value: String, now: Long) {
        entries[key] = Entry(value, now)
        if (entries.size > MAX_ENTRIES) prune(now)
    }

    private fun alive(key: String, now: Long): Boolean {
        val entry = entries[key] ?: return false
        val age = now - entry.at
        // A clock set back makes an entry "from the future": treat it as expired.
        return age in 0 until ttlOf(key)
    }

    companion object {
        const val GOOD_SERVER_TTL_MS = 24 * 3_600_000L
        const val GOOD_TRANSPORT_TTL_MS = 24 * 3_600_000L
        const val STALLED_TTL_MS = 6 * 3_600_000L
        const val PENALTY_TTL_MS = 30 * 60_000L
        const val TENTATIVE_STALL_TTL_MS = 10 * 60_000L
        const val MAX_ENTRIES = 200

        private const val GOOD_SERVER = "gs"
        private const val GOOD_TRANSPORT = "gt"
        private const val WORKED = "wk"
        private const val STALLED = "st"
        private const val TENTATIVE = "sq"
        private const val PENALTY = "pn"
        /** Server slot of a stall that counts for every server of the network. */
        private const val ANY = "*"

        private fun goodServerKey(network: String) = "$GOOD_SERVER|$network"
        private fun workedKey(network: String, transport: String) = "$WORKED|$network|$transport"
        private fun goodTransportKey(network: String, serverId: String) = "$GOOD_TRANSPORT|$network|$serverId"
        private fun stalledPrefix(network: String, serverId: String) = "$STALLED|$network|$serverId|"
        private fun tentativePrefix(network: String, serverId: String) = "$TENTATIVE|$network|$serverId|"

        private fun ttlOf(key: String): Long = when (key.substringBefore('|')) {
            GOOD_SERVER -> GOOD_SERVER_TTL_MS
            GOOD_TRANSPORT, WORKED -> GOOD_TRANSPORT_TTL_MS
            STALLED -> STALLED_TTL_MS
            TENTATIVE -> TENTATIVE_STALL_TTL_MS
            PENALTY -> PENALTY_TTL_MS
            else -> 0L
        }

        fun empty() = ColituConnectMemory(mutableMapOf())

        /** Reads the stored memory and prunes what expired. */
        fun fromJson(raw: String?, now: Long): ColituConnectMemory {
            val map = mutableMapOf<String, Entry>()
            runCatching {
                if (!raw.isNullOrBlank()) {
                    JsonParser.parseString(raw).asJsonObject.entrySet().forEach { (key, value) ->
                        val o = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                        val v = o.get("v")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
                        val t = o.get("t")?.takeIf { it.isJsonPrimitive }?.asLong ?: return@forEach
                        map[key] = Entry(v, t)
                    }
                }
            }
            return ColituConnectMemory(map).also { it.prune(now) }
        }
    }
}
