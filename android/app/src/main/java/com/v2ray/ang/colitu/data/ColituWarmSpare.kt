package com.v2ray.ang.colitu.data

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tencent.mmkv.MMKV

/**
 * Warm spare: a second, already configured path inside the running core.
 * The primary outbound `proxy` sits in the balancer [BALANCER_TAG]; when the
 * burst observatory sees it dead, Xray sends new connections to the spare
 * ([SPARE_TAG], the balancer's fallback) and back once the primary answers
 * again. The VPN stays up and no app code has to run (works in the
 * background). The app's stall watch and Adaptive Connect still act when
 * both paths are dead.
 *
 * The spare's tag does not start with "proxy": balancer and observatory
 * selectors match outbound tags by prefix, so `proxy-spare` would have joined
 * the primary in the balancer and carried a share of the traffic all the time.
 */
object ColituWarmSpare {
    const val PRIMARY_TAG = "proxy"
    const val SPARE_TAG = "warm-spare"
    const val BALANCER_TAG = "proxy-auto"
    /** The catch-all rule that sends what no other rule matched to the balancer. */
    const val RULE_TAG = "colitu-spare"

    /**
     * Probe budget (mobile). Xray's burst observatory probes each subject
     * `sampling` times at random moments within every `interval × sampling`
     * window and keeps the last `sampling` results; the outbound counts as
     * dead when all of them failed. One probe of the primary every 10 s on
     * average: a dead primary is noticed with the next probe, typically
     * within about 13 s. A single lost probe moves only new connections to
     * the spare until the next probe answers; open ones stay where they are.
     */
    const val PROBE_INTERVAL = "10s"
    const val PROBE_SAMPLING = 1
    const val PROBE_TIMEOUT = "3s"
    /** Plain HTTP: the probe leaves at the node, so it needs no TLS of its own (a third of the bytes). */
    const val PROBE_URL = "http://www.gstatic.com/generate_204"
    // No `connectivity` URL: when that direct check fails (no network, or a
    // resolver the VPN process cannot use) Xray records nothing, and the
    // primary then took twice as long to be marked dead in a local test
    // (18 s instead of 9-14 s). Offline, both paths are dead anyway.

    /** Rule tag of the DNS module's own queries (DoH of ad blocking, the panel's DNS servers) to the balancer. */
    const val DNS_RULE_TAG = "colitu-spare-dns"
    /** Tag given to a `dns` block that has none, so its queries can be routed. */
    const val DNS_TAG = "colitu-dns"

    /**
     * TCP_USER_TIMEOUT of the TCP outbounds: a connection whose data is not
     * acknowledged for this long is closed. A silently dropped path then
     * ends its long-lived connections (the DNS module's DoH keeps one) and
     * they reconnect through the balancer, i.e. the spare, instead of
     * waiting on a dead socket for minutes.
     */
    const val TCP_USER_TIMEOUT_MS = 10_000

    private const val KEY_ENABLED = "warm_spare"
    // Same store as ColituController; the VPN service reads it in its own process.
    private val store by lazy { MMKV.mmkvWithID("COLITU_SETTINGS", MMKV.MULTI_PROCESS_MODE) }

    /** "Warm spare" setting, on by default. */
    var enabled: Boolean
        get() = runCatching { store.decodeBool(KEY_ENABLED, true) }.getOrDefault(true)
        set(value) {
            store.encode(KEY_ENABLED, value)
        }

    // ── Which spare ────────────────────────────────────────────────────────

    /** TCP transports in the order they are preferred as a spare for a UDP primary. */
    private val tcpOrder = listOf("vless-reality", "vless-xhttp", "trojan", "shadowsocks")
    private const val UDP = "hysteria2"

    /**
     * The spare transport for [primary]: the other family when possible
     * (Hysteria2 -> Reality, XHTTP, Trojan, SS2022; TCP -> Hysteria2), else
     * another TCP transport. [offered] are the spare server's transports,
     * [stalled] what stalled there on this network (never a spare). On
     * another server ([sameServer] false) the primary's own transport is the
     * last resort; on the same server it never is. A transport the panel
     * hints as blocked on this network ([hinted]) is only picked when nothing
     * else qualifies.
     */
    fun spareTransport(
        primary: String?,
        offered: Collection<String>,
        stalled: Set<String>,
        sameServer: Boolean,
        hinted: Set<String> = emptySet(),
        worked: Set<String> = emptySet(),
    ): String? = spareChoice(primary, offered, stalled, sameServer, hinted, worked)?.first

    /** [spareTransport] with why it was chosen (for the log): proven, other-family, same-transport or hinted-fallback. */
    fun spareChoice(
        primary: String?,
        offered: Collection<String>,
        stalled: Set<String>,
        sameServer: Boolean,
        hinted: Set<String> = emptySet(),
        worked: Set<String> = emptySet(),
    ): Pair<String, String>? {
        if (hinted.isNotEmpty()) {
            spareChoice(primary, offered, stalled + hinted, sameServer, worked = worked)?.let { return it }
            return spareChoice(primary, offered, stalled, sameServer, worked = worked)?.let { it.first to "hinted-fallback" }
        }
        val usable = offered.filter { it in XrayMobileAdapter.supportedProtocols && it !in stalled }
        // On another server, what carried traffic on this network lately wins
        // over the family rule, the primary's own transport first: an
        // unproven family (often frozen by the same DPI) is no spare at all.
        if (!sameServer) {
            val proven = usable.filter { it in worked }
            if (proven.isNotEmpty()) {
                val pick = primary?.takeIf { it in proven }
                    ?: proven.sortedWith(compareBy<String> { it == "shadowsocks" }.thenBy { XrayMobileAdapter.transportRank[it] ?: 9 }).first()
                return pick to "proven"
            }
        }
        val byFamily = if (primary == UDP) {
            tcpOrder
        } else {
            listOf(UDP) + tcpOrder.filter { it != primary }
        }
        // A transport that carried traffic on this network lately ([worked])
        // beats an untested one; Shadowsocks stays last either way.
        val candidates = byFamily.sortedWith(compareBy<String> { it == "shadowsocks" }.thenBy { it !in worked })
        candidates.firstOrNull { it in usable }?.let { return it to if (it in worked) "proven" else "other-family" }
        return if (!sameServer && primary != null && primary in usable) primary to "same-transport" else null
    }

    /**
     * Automatic mode: the next server of the Adaptive Connect ranking that is
     * not the primary and not penalized on this network. [ranked] already
     * leaves out unavailable nodes and multihop routes.
     */
    fun spareServer(ranked: List<ColituServer>, primaryId: String, penalized: Set<String>): ColituServer? =
        ranked.firstOrNull { it.id != primaryId && it.id !in penalized && !it.isMultihop && it.isAvailable }

    // ── Spare health and replacement ───────────────────────────────────────

    /**
     * The spare is checked alone (its own check inbound, one small HTTP
     * request) this often while the primary is healthy: Hysteria2 reuses its
     * QUIC connection (well under 1 KB a check, ~0.03 MB/h at 60 s); a TCP
     * spare opens a new tunnel connection each time (~5 KB), so every 180 s
     * keeps it near 0.1 MB/h.
     */
    const val SPARE_PROBE_UDP_MS = 60_000L
    const val SPARE_PROBE_TCP_MS = 180_000L
    /** Consecutive misses (phone online, primary healthy) that make the spare dead. */
    const val SPARE_PROBE_MISSES = 2

    fun spareProbeIntervalMs(spareProtocol: String?): Long = if (spareProtocol == UDP) SPARE_PROBE_UDP_MS else SPARE_PROBE_TCP_MS

    /** Where a replacement spare may come from: one server, its transports and what stalled there. */
    data class SpareOption(val serverId: String, val sameServer: Boolean, val offered: List<String>, val stalled: Set<String>)

    /**
     * A new spare for [primary] after the attached one died: the first of
     * [options] (in order of preference) that still has a transport by the
     * spare rules ([spareChoice]: proven first, then the other family),
     * never one of [dead] (server id to transport). Null: keep the old spare.
     */
    fun replacement(
        primary: String?,
        options: List<SpareOption>,
        dead: Set<Pair<String, String>>,
        worked: Set<String> = emptySet(),
        hinted: Set<String> = emptySet(),
    ): Triple<String, String, String>? = options.firstNotNullOfOrNull { option ->
        val deadHere = dead.filter { it.first == option.serverId }.mapTo(mutableSetOf()) { it.second }
        spareChoice(primary, option.offered, option.stalled + deadHere, option.sameServer, hinted, worked)
            ?.let { (protocol, reason) -> Triple(option.serverId, protocol, reason) }
    }

    /** Traffic in the last 10 s below this: no call, no download, the core may reload for a new spare. */
    const val SWAP_IDLE_BYTES = 10 * 1024L

    /**
     * After waiting this long for an idle moment, light traffic no longer
     * defers the swap: background sync and our own probes keep many phones
     * above [SWAP_IDLE_BYTES] all the time (measured 15–30 KB per 10 s), and a
     * dead spare protects nothing.
     */
    const val SWAP_MAX_DEFER_MS = 2 * 60_000L

    /** Light traffic: under this in the last 10 s (about 25 kbit/s) is no call (~40 KB) or video. */
    const val SWAP_LIGHT_BYTES = 32 * 1024L

    /**
     * Whether a new spare may be swapped in now (core reload, about a second
     * without traffic): null = now, else why it waits. Any traffic in the
     * last 10 s (a call's UDP, a page, a download) defers it, light traffic
     * only for [SWAP_MAX_DEFER_MS] ([deferredForMs] = how long it has waited);
     * the screen being off is no reason on its own, a call runs with the screen off.
     */
    fun swapDeferral(bytesLast10s: Long?, deferredForMs: Long = 0L): String? = when {
        bytesLast10s == null -> "traffic unknown"
        bytesLast10s < SWAP_IDLE_BYTES -> null
        deferredForMs >= SWAP_MAX_DEFER_MS && bytesLast10s < SWAP_LIGHT_BYTES -> null
        else -> "busy (${bytesLast10s / 1024} KB in the last 10 s)"
    }

    // ── Parallel connect ───────────────────────────────────────────────────

    enum class ParallelWinner { Primary, Spare, None }

    /**
     * One parallel round: the primary passing wins (normal); only the spare
     * passing makes it the primary (one quick core reload at connect time);
     * neither: the next pair. -1 or null = failed or no spare.
     */
    fun parallelWinner(primaryMs: Long, spareMs: Long?): ParallelWinner = when {
        primaryMs >= 0 -> ParallelWinner.Primary
        spareMs != null && spareMs >= 0 -> ParallelWinner.Spare
        else -> ParallelWinner.None
    }

    // ── Core config ────────────────────────────────────────────────────────

    /**
     * [raw] (a full runtime config, after split tunneling, privacy rules and
     * ad blocking) with the outbound `proxy` of [spareRaw] added as
     * [SPARE_TAG], the balancer, the burst observatory and every routing rule
     * that pointed at `proxy` moved to the balancer. Without [spareRaw] (or
     * when it has no usable outbound) the config is the single-outbound one.
     */
    fun attach(raw: String, spareRaw: String?): String {
        val spare = spareRaw?.let { outboundOf(it, PRIMARY_TAG) } ?: return detach(raw)
        val json = JsonParser.parseString(detach(raw)).asJsonObject
        val outbounds = json.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray ?: return raw
        val primaryIndex = outbounds.indexOfFirst { it.tagOf() == PRIMARY_TAG }
        if (primaryIndex < 0) return raw
        spare.addProperty("tag", SPARE_TAG)
        // The primary stays the first outbound (Xray's default); the spare follows it.
        val rebuilt = JsonArray()
        outbounds.forEachIndexed { i, outbound ->
            if (i == primaryIndex) {
                rebuilt.add(withDeadPathTimeout(outbound.asJsonObject))
                rebuilt.add(withDeadPathTimeout(spare))
            } else {
                rebuilt.add(outbound)
            }
        }
        json.add("outbounds", rebuilt)
        val routing = json.get("routing")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        routing.add("balancers", JsonArray().apply {
            routing.get("balancers")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.filterNot { it.isJsonObject && it.asJsonObject.get("tag")?.asString == BALANCER_TAG }
                ?.forEach(::add)
            add(JsonObject().apply {
                addProperty("tag", BALANCER_TAG)
                add("selector", JsonArray().apply { add(PRIMARY_TAG) })
                addProperty("fallbackTag", SPARE_TAG)
                // Random over the single selected outbound; with a fallbackTag it
                // follows the observatory and picks the fallback when nothing is alive.
                add("strategy", JsonObject().apply { addProperty("type", "random") })
            })
        })
        json.add("routing", routing)
        // Only the primary is probed: the fallback is used without a check
        // of its own, so probing it would only double the probe traffic.
        json.add("burstObservatory", JsonObject().apply {
            add("subjectSelector", JsonArray().apply { add(PRIMARY_TAG) })
            add("pingConfig", JsonObject().apply {
                addProperty("destination", PROBE_URL)
                addProperty("interval", PROBE_INTERVAL)
                addProperty("sampling", PROBE_SAMPLING)
                addProperty("timeout", PROBE_TIMEOUT)
            })
        })
        return reroute(json.toString())
    }

    /** True when [raw] carries a spare (then [reroute] keeps its rules on the balancer). */
    fun hasSpare(raw: String): Boolean = runCatching {
        JsonParser.parseString(raw).asJsonObject.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.any { it.tagOf() == SPARE_TAG } == true
    }.getOrDefault(false)

    /**
     * Every rule that names `proxy` as outbound now names the balancer, and
     * the catch-all goes (back) to the very end, behind rules other layers
     * added since (split tunneling's "only these" direct rule must stay in
     * front of it). No-op without a spare. Idempotent.
     */
    fun reroute(raw: String): String {
        if (!hasSpare(raw)) return raw
        val json = JsonParser.parseString(raw).asJsonObject
        val routing = json.get("routing")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val existing = routing.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
        val rules = JsonArray()
        // The traffic check tests the primary alone (XrayMobileAdapter.withVerifyInbound): its rule stays first.
        val verify = existing.filter { it.isJsonObject && it.asJsonObject.ruleTagOf() in checkRuleTags }
        verify.forEach(rules::add)
        // The DNS module's own queries (DoH of ad blocking, the panel's DNS
        // servers) follow the balancer too, ahead of every other rule (a
        // port-53 rule to `dns-out` would otherwise catch plain DNS servers).
        dnsTagOf(json)?.let { tag ->
            rules.add(JsonObject().apply {
                addProperty("type", "field")
                add("inboundTag", JsonArray().apply { add(tag) })
                addProperty("balancerTag", BALANCER_TAG)
                addProperty("ruleTag", DNS_RULE_TAG)
            })
        }
        existing.forEach { element ->
            val rule = element.takeIf { it.isJsonObject }?.asJsonObject
            when {
                rule == null -> rules.add(element)
                rule.ruleTagOf() == RULE_TAG || rule.ruleTagOf() == DNS_RULE_TAG -> Unit
                rule.ruleTagOf() in checkRuleTags -> Unit
                rule.get("outboundTag")?.takeIf { it.isJsonPrimitive }?.asString == PRIMARY_TAG -> {
                    rule.remove("outboundTag")
                    rule.addProperty("balancerTag", BALANCER_TAG)
                    rules.add(rule)
                }
                else -> rules.add(rule)
            }
        }
        // Xray sends what no rule matched to the first outbound, past the balancer.
        rules.add(JsonObject().apply {
            addProperty("type", "field")
            addProperty("network", "tcp,udp")
            addProperty("balancerTag", BALANCER_TAG)
            addProperty("ruleTag", RULE_TAG)
        })
        routing.add("rules", rules)
        json.add("routing", routing)
        return json.toString()
    }

    private val checkRuleTags = setOf(XrayMobileAdapter.VERIFY_TAG, XrayMobileAdapter.VERIFY_SPARE_TAG)

    /** [raw] as today's single-outbound config: spare, its check inbound, balancer, observatory and catch-all removed. */
    fun detach(raw: String): String {
        val json = JsonParser.parseString(raw).asJsonObject
        var changed = false
        json.get("inbounds")?.takeIf { it.isJsonArray }?.asJsonArray?.let { inbounds ->
            if (inbounds.any { it.tagOf() == XrayMobileAdapter.VERIFY_SPARE_TAG }) {
                json.add("inbounds", JsonArray().apply { inbounds.filterNot { it.tagOf() == XrayMobileAdapter.VERIFY_SPARE_TAG }.forEach(::add) })
                changed = true
            }
        }
        json.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray?.let { outbounds ->
            if (outbounds.any { it.tagOf() == SPARE_TAG }) {
                json.add("outbounds", JsonArray().apply { outbounds.filterNot { it.tagOf() == SPARE_TAG }.forEach(::add) })
                changed = true
            }
        }
        if (json.has("burstObservatory")) {
            json.remove("burstObservatory")
            changed = true
        }
        json.get("routing")?.takeIf { it.isJsonObject }?.asJsonObject?.let { routing ->
            routing.get("balancers")?.takeIf { it.isJsonArray }?.asJsonArray?.let { balancers ->
                val kept = balancers.filterNot { it.isJsonObject && it.asJsonObject.get("tag")?.asString == BALANCER_TAG }
                if (kept.size != balancers.size()) {
                    changed = true
                    if (kept.isEmpty()) routing.remove("balancers") else routing.add("balancers", JsonArray().apply { kept.forEach(::add) })
                }
            }
            routing.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray?.let { existing ->
                val rules = JsonArray()
                existing.forEach { element ->
                    val rule = element.takeIf { it.isJsonObject }?.asJsonObject
                    when {
                        rule == null -> rules.add(element)
                        rule.ruleTagOf() == RULE_TAG || rule.ruleTagOf() == DNS_RULE_TAG ||
                            rule.ruleTagOf() == XrayMobileAdapter.VERIFY_SPARE_TAG -> changed = true
                        rule.get("balancerTag")?.takeIf { it.isJsonPrimitive }?.asString == BALANCER_TAG -> {
                            rule.remove("balancerTag")
                            rule.addProperty("outboundTag", PRIMARY_TAG)
                            rules.add(rule)
                            changed = true
                        }
                        else -> rules.add(rule)
                    }
                }
                routing.add("rules", rules)
            }
        }
        return if (changed) json.toString() else raw
    }

    /**
     * The tag of the config's `dns` block, given one ([DNS_TAG]) when it has
     * none; null without DNS servers (nothing to route).
     */
    private fun dnsTagOf(json: JsonObject): String? {
        val dns = json.get("dns")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        if (dns.get("servers")?.takeIf { it.isJsonArray }?.asJsonArray?.size()?.let { it > 0 } != true) return null
        dns.get("tag")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }?.let { return it }
        dns.addProperty("tag", DNS_TAG)
        return DNS_TAG
    }

    /** [outbound] with [TCP_USER_TIMEOUT_MS] unless it runs over QUIC (Hysteria2) or already sets one. */
    private fun withDeadPathTimeout(outbound: JsonObject): JsonObject {
        if (outbound.get("protocol")?.takeIf { it.isJsonPrimitive }?.asString == "hysteria") return outbound
        val stream = outbound.get("streamSettings")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject().also { outbound.add("streamSettings", it) }
        val sockopt = stream.get("sockopt")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject().also { stream.add("sockopt", it) }
        if (!sockopt.has("tcpUserTimeout")) sockopt.addProperty("tcpUserTimeout", TCP_USER_TIMEOUT_MS)
        return outbound
    }

    /**
     * One line for the log at every core start: outbound tags (with
     * protocol), balancer, observatory and each rule as its tag, matchers and
     * target. No addresses, hosts or credentials.
     */
    fun describe(raw: String): String = runCatching {
        val json = JsonParser.parseString(raw).asJsonObject
        val outbounds = json.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray?.joinToString(",") {
            val o = it.asJsonObject
            "${o.get("tag")?.asString}:${o.get("protocol")?.asString}"
        }
        val inbounds = json.get("inbounds")?.takeIf { it.isJsonArray }?.asJsonArray?.joinToString(",") {
            val o = it.asJsonObject
            "${o.get("tag")?.asString}:${o.get("protocol")?.asString}"
        }
        val routing = json.get("routing")?.takeIf { it.isJsonObject }?.asJsonObject
        val balancers = routing?.get("balancers")?.takeIf { it.isJsonArray }?.asJsonArray?.joinToString(",") {
            val b = it.asJsonObject
            "${b.get("tag")?.asString}${b.get("selector")}->${b.get("fallbackTag")?.asString}"
        }.orEmpty()
        val rules = routing?.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray?.joinToString(" | ") { element ->
            val r = element.asJsonObject
            val matchers = listOf("inboundTag", "port", "network", "domain", "ip", "protocol").filter { r.has(it) }.joinToString("+") { key ->
                if (key == "inboundTag" || key == "port" || key == "network") "$key=${r.get(key)}" else "$key[${r.get(key).let { v -> if (v.isJsonArray) v.asJsonArray.size() else 1 }}]"
            }
            val target = r.get("outboundTag")?.asString ?: r.get("balancerTag")?.asString?.let { "balancer:$it" } ?: "?"
            "${r.get("ruleTag")?.asString ?: "-"}($matchers)->$target"
        }.orEmpty()
        val dns = json.get("dns")?.takeIf { it.isJsonObject }?.asJsonObject
        "in[$inbounds] out[$outbounds] balancers[$balancers] observatory=${json.has("burstObservatory")} " +
            "dns(tag=${dns?.get("tag")?.asString}, servers=${dns?.get("servers")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0}) rules[$rules]"
    }.getOrElse { "unreadable: ${it.javaClass.simpleName}" }

    private fun outboundOf(raw: String, tag: String): JsonObject? = runCatching {
        JsonParser.parseString(raw).asJsonObject.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.firstOrNull { it.tagOf() == tag }?.asJsonObject?.deepCopy()
    }.getOrNull()

    private fun JsonElement.tagOf(): String? =
        takeIf { it.isJsonObject }?.asJsonObject?.get("tag")?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.ruleTagOf(): String? = get("ruleTag")?.takeIf { it.isJsonPrimitive }?.asString
}
