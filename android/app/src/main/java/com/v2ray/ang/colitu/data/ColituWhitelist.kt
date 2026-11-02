package com.v2ray.ang.colitu.data

import android.net.Network
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Adaptive Connect 4 whitelist mode (mobile operators that only let listed
 * addresses through). With the VPN off and after two failed connects in a
 * row, one check of at most [CHECK_BUDGET_MS] connects in parallel by TCP to
 * a foreign set (3 neutral foreign hosts + 2 Colitu nodes) and the domestic
 * set of the network's country (`whitelist_probe` of GET /servers):
 * foreign ≤ 1 OK and domestic ≥ 2 OK → whitelist mode. In the mode the
 * automatic choice takes only `whitelist_ok` servers (bridges first,
 * [ColituLm]); it is checked again every [RECHECK_MS] and left once the
 * foreign set works again. Same rule as the panel's `acmodel.WhitelistMode`.
 */
object ColituWhitelist {
    const val FOREIGN_MAX_OK = 1
    const val DOMESTIC_MIN_OK = 2
    const val FAILURES_TO_CHECK = 2
    const val CHECK_BUDGET_MS = 4_000L
    private const val CONNECT_TIMEOUT_MS = 3_500
    const val RECHECK_MS = 5 * 60_000L
    const val PORT = 443

    /** Neutral foreign hosts, the same on every platform (Windows, Linux, iOS, extension). */
    val FOREIGN_HOSTS = listOf("www.google.com", "www.wikipedia.org", "www.cloudflare.com")

    /** Used until the panel sent `whitelist_probe` (its default). */
    val DEFAULT_PROBE = mapOf("RU" to listOf("ya.ru", "vk.com", "gosuslugi.ru", "mail.ru"))

    data class Check(val foreignOk: Int, val foreignTotal: Int, val domesticOk: Int, val domesticTotal: Int, val ms: Long) {
        val whitelist: Boolean get() = mode(foreignOk, domesticOk)
        /** The foreign set works again: whitelist mode ends. */
        val foreignWorks: Boolean get() = foreignOk > FOREIGN_MAX_OK
        /** `on:f1/5:d3/4` (log, journal). */
        val flag: String get() = "${if (whitelist) "on" else "off"}:f$foreignOk/$foreignTotal:d$domesticOk/$domesticTotal"
    }

    fun mode(foreignOk: Int, domesticOk: Int): Boolean = foreignOk <= FOREIGN_MAX_OK && domesticOk >= DOMESTIC_MIN_OK

    /** A check is due: the VPN is off and the last [FAILURES_TO_CHECK] connects failed. */
    fun checkDue(vpnOff: Boolean, failuresInRow: Int): Boolean = vpnOff && failuresInRow >= FAILURES_TO_CHECK

    /** What the mode is after [check]: entered by the rule, left only when the foreign set works. */
    fun next(current: Boolean, check: Check): Boolean = if (current) !check.foreignWorks else check.whitelist

    // ── Kept across app restarts (MMKV) ──────────────────────────────────────

    /** A found mode is trusted after an app restart for this long; an older one is checked again. */
    const val RESTORE_MAX_AGE_MS = 5 * 60_000L

    /** The mode ([on]) as of the last check at [checkedAt] (wall clock, ms) on [network] (the network key). */
    data class Saved(val on: Boolean, val checkedAt: Long, val network: String)

    enum class Restore {
        /** Nothing to bring back (no state, or the mode was off). */
        None,
        /** Same network, checked at most [RESTORE_MAX_AGE_MS] ago: the mode is on again right away. */
        On,
        /** The mode was on, but on another network or too long ago: one check decides. */
        Recheck,
    }

    /** What app start does with [saved] on [network] at [now]. */
    fun restore(saved: Saved?, network: String, now: Long): Restore {
        if (saved == null || !saved.on) return Restore.None
        val age = now - saved.checkedAt
        return if (saved.network == network && age in 0..RESTORE_MAX_AGE_MS) Restore.On else Restore.Recheck
    }

    fun savedToJson(saved: Saved): String = JsonObject().apply {
        addProperty("on", saved.on)
        addProperty("checked_at", saved.checkedAt)
        addProperty("network", saved.network)
    }.toString()

    fun savedFromJson(raw: String?): Saved? = runCatching {
        val json = JsonParser.parseString(raw ?: return null).asJsonObject
        val network = json.get("network")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } ?: return null
        Saved(json.get("on")?.asBoolean ?: return null, json.get("checked_at")?.asLong ?: return null, network)
    }.getOrNull()

    /** Domestic hosts for [country] from [probe] (the panel's list, else [DEFAULT_PROBE]); empty when none. */
    fun domesticHosts(probe: Map<String, List<String>>?, country: String?): List<String> {
        val cc = country?.trim()?.uppercase()?.takeIf { it.length == 2 } ?: return emptyList()
        return (probe ?: DEFAULT_PROBE)[cc].orEmpty().take(MAX_HOSTS)
    }

    /**
     * Two Colitu nodes for the foreign set: not in Russia, with a probe
     * address, fastest known ping first (unknown after).
     */
    fun colituTargets(servers: List<ColituServer>, pingMs: (ColituServer) -> Int?): List<Pair<String, Int>> =
        servers.asSequence()
            .filter { !it.isMultihop && !it.countryCode.equals("RU", ignoreCase = true) }
            .filter { !it.latencyHost.isNullOrBlank() && ColituLatency.probeAllowed(it.latencyHost!!, it.latencyPort ?: PORT) }
            .sortedBy { pingMs(it) ?: Int.MAX_VALUE }
            .take(2)
            .map { it.latencyHost!! to (it.latencyPort ?: PORT) }
            .toList()

    /** `whitelist_probe` of GET /servers: country -> hosts; null when absent. */
    fun parseProbe(json: JsonObject?): Map<String, List<String>>? {
        json ?: return null
        return json.entrySet().mapNotNull { (country, hosts) ->
            val cc = country.trim().uppercase().takeIf { it.length == 2 } ?: return@mapNotNull null
            val list = hosts.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { h -> h.takeIf { it.isJsonPrimitive }?.asString?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } }
                ?.take(MAX_HOSTS).orEmpty()
            if (list.isEmpty()) null else cc to list
        }.toMap()
    }

    fun probeToJson(probe: Map<String, List<String>>): String = JsonObject().apply {
        probe.forEach { (cc, hosts) -> add(cc, JsonArray().apply { hosts.forEach(::add) }) }
    }.toString()

    fun probeFromJson(raw: String?): Map<String, List<String>>? =
        runCatching { raw?.takeIf { it.isNotBlank() }?.let { parseProbe(JsonParser.parseString(it).asJsonObject) } }.getOrNull()

    private const val MAX_HOSTS = 8

    /**
     * One check: every host at the same time, at most [CHECK_BUDGET_MS]. On
     * [network] when given (the VPN owner binds to the physical network).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun check(colituNodes: List<Pair<String, Int>>, domestic: List<String>, network: Network? = null): Check = coroutineScope {
        val started = System.currentTimeMillis()
        val foreign = FOREIGN_HOSTS.map { it to PORT } + colituNodes
        val domesticTargets = domestic.map { it to PORT }
        val jobs = (foreign + domesticTargets).map { (host, port) -> async(Dispatchers.IO) { reachable(host, port, network) } }
        val results = withTimeoutOrNull(CHECK_BUDGET_MS) { jobs.awaitAll() }
            ?: jobs.map { job -> if (job.isCompleted && !job.isCancelled) job.getCompleted() else false }.also { jobs.forEach { it.cancel() } }
        val foreignOk = results.take(foreign.size).count { it }
        val domesticOk = results.drop(foreign.size).count { it }
        Check(foreignOk, foreign.size, domesticOk, domesticTargets.size, System.currentTimeMillis() - started)
    }

    private suspend fun reachable(host: String, port: Int, network: Network?): Boolean = withContext(Dispatchers.IO) {
        if (!ColituLatency.probeAllowed(host, port)) return@withContext false
        runCatching {
            val resolved = network?.getByName(host) ?: InetAddress.getByName(host)
            if (ColituAddressRanges.isNonPublic(resolved)) return@runCatching false
            (network?.socketFactory?.createSocket() ?: Socket()).use { socket ->
                socket.connect(InetSocketAddress(resolved, port), CONNECT_TIMEOUT_MS)
            }
            true
        }.getOrDefault(false)
    }
}

/**
 * Servers located in Russia (owner, 2026-11-02), independent of the
 * Adaptive Connect 4 switch. Listed only when the app language is Russian
 * or the network's country (`client_country` of the last /servers with the
 * VPN off) is RU. A plain Russian server is never picked automatically
 * ([ColituServerRanking.autoExcluded], [ColituLm]); a bridge (a multihop
 * route entering in one country and leaving in another) may be.
 */
object ColituRussianServers {
    const val RU = "RU"

    /**
     * Entry and exit country (upper case, empty when unknown), as on Windows: a multihop route
     * its two ends; a bridge node ("Finlandiya (Köprü)", country not RU) enters in Russia and
     * leaves in its own country; anything else its own country twice.
     */
    fun countries(server: ColituServer): Pair<String, String> {
        val own = server.countryCode?.trim()?.uppercase().orEmpty()
        server.route?.let { route ->
            val exit = route.exit.country?.trim()?.uppercase().orEmpty()
            return route.entry.country?.trim()?.uppercase().orEmpty() to exit.ifEmpty { own }
        }
        if (own.isNotEmpty() && own != RU && com.v2ray.ang.colitu.l10n.ColituServerNames.isBridgeName(server.displayName)) return RU to own
        return own to own
    }

    /** Enters in one country and leaves in another: a multihop route across borders or a bridge node. */
    fun isBridge(server: ColituServer): Boolean {
        val (entry, exit) = countries(server)
        return entry.isNotEmpty() && exit.isNotEmpty() && entry != exit
    }

    fun visible(server: ColituServer, language: String, networkCountry: String?): Boolean =
        !server.countryCode.equals(RU, ignoreCase = true) || language.equals("ru", ignoreCase = true) ||
            networkCountry?.trim().equals(RU, ignoreCase = true)

    /** Ids of [servers] the list leaves out, except [keep] (connected, connecting, picked by hand). */
    fun hiddenIds(servers: List<ColituServer>, language: String, networkCountry: String?, keep: Set<String>): Set<String> =
        servers.filter { it.id !in keep && !visible(it, language, networkCountry) }.mapTo(mutableSetOf()) { it.id }

    /** The automatic choice may use it: anything but a plain Russian server (bridges may; a bridge node is not RU). */
    fun autoAllowed(server: ColituServer): Boolean = isBridge(server) || !server.countryCode.equals(RU, ignoreCase = true)
}
