package com.v2ray.ang.colitu.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import com.google.gson.JsonParser
import com.tencent.mmkv.MMKV
import com.v2ray.ang.colitu.api.ColituApiClient
import com.v2ray.ang.colitu.api.ColituSecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ColituGecko: Colitu's own protocol, a peer of VLESS/Trojan/Hysteria2, same
 * design as the Windows app. `gecko-client` (Colitu's private colitu-gecko
 * repository, built by scripts/build-gecko.sh) opens a SOCKS5 front (TCP
 * CONNECT and UDP ASSOCIATE, per-run account) on loopback and carries what it
 * gets over its own connections to Colitu's Gecko entries; the panel's anchors
 * pick the exit. Listed in the protocol picker only when the panel offers it
 * (`GET /gecko/config`).
 *
 * - Gecko-only profile ([ColituGeckoRules.profileTemplate], made the running
 *   config by [ColituGeckoRules.geckoOnly]): every proxied TCP and UDP flow
 *   and the core's own DNS → `colitu-gecko` SOCKS outbound → gecko-client →
 *   the internet. No server outbound. Russian destinations (the anchors
 *   refuse them) and private ranges go direct.
 * - gecko-client is `libgeckoclient.so` in the APK's native library directory
 *   (an executable). It runs with the app's UID, which is outside the VPN in
 *   every mode (CoreVpnService / ColituSplitTunnel exclude the app itself),
 *   so its sockets reach the network directly, like Xray's own.
 * - Secrets: the panel's configuration (passed on unchanged) and the SOCKS
 *   account go to its standard input only; never to its command line, a log
 *   line or a plain file (the configuration is stored sealed, [ColituGeckoStore]).
 * - Failure: gecko-client missing, exiting, or without a non-bootstrap
 *   session within [ColituGeckoRules.READY_DEADLINE_MS]: nothing is switched
 *   silently. The core keeps the Gecko-only config (nothing leaks around the
 *   tunnel), the failure is stored ([ColituGeckoStore.markFailed], the
 *   cool-down) and the connect flow moves on (Adaptive Connect) or shows the
 *   connect error (a manual pick).
 */
enum class ColituGeckoOffer {
    /** Not asked yet, or the request failed. */
    Unknown,
    Offered,
    /** 404: Gecko is not offered to this account (the protocol is not listed). */
    NotOffered,
    /** 503, or an answer that is not a usable configuration: offered, not available now. */
    Unavailable,
}

/**
 * A configuration as the panel sent it: the exact response bytes and the
 * `X-Gecko-Config-Signature` header that covers them. A secret: memory and
 * the sealed store only, never logged.
 */
class ColituGeckoSignedConfig(val body: ByteArray, val signature: String) {
    override fun toString() = "signed gecko config (redacted)"
}

/** What the app kept of earlier runs and gives gecko-client back at its start (F-14, F-15). */
data class ColituGeckoFloor(val minIssued: Long = 0, val skinFloor: Map<String, Long> = emptyMap())

/** `GET /gecko/config`. [config] is the panel's answer as it came: a secret, never logged. [reason] says why an answer was not usable. */
class ColituGeckoConfigResult(val offer: ColituGeckoOffer, val config: ColituGeckoSignedConfig?, val reason: String? = null) {
    override fun toString() = "gecko $offer config ${if (config == null) "none" else "redacted"}"

    companion object {
        val NOT_OFFERED = ColituGeckoConfigResult(ColituGeckoOffer.NotOffered, null)
        val UNAVAILABLE = ColituGeckoConfigResult(ColituGeckoOffer.Unavailable, null)
        val UNKNOWN = ColituGeckoConfigResult(ColituGeckoOffer.Unknown, null)

        /**
         * An HTTP answer: 200 with a usable configuration and its signature
         * header is offered, 404 not offered, 503 or a 200 that is not usable
         * (no signature included) unavailable, anything else (no network,
         * 401, other errors) unknown.
         */
        fun fromHttp(code: Int, body: String?, signature: String? = null): ColituGeckoConfigResult = when (code) {
            200 -> ColituGeckoRules.sign(body?.toByteArray(Charsets.UTF_8), signature)?.let { ColituGeckoConfigResult(ColituGeckoOffer.Offered, it) }
                ?: ColituGeckoConfigResult(ColituGeckoOffer.Unavailable, null, ColituGeckoRules.unusableReason(body, signature))
            404 -> NOT_OFFERED
            503 -> UNAVAILABLE
            else -> UNKNOWN
        }

        /** The same for an [ColituApiClient.ApiResult]: the exact body bytes and the header, never the parsed object. */
        fun fromApi(result: ColituApiClient.ApiResult<JsonObject>?): ColituGeckoConfigResult = when (result) {
            is ColituApiClient.ApiResult.Success -> fromHttp(200, result.rawBody, result.signature)
            is ColituApiClient.ApiResult.Error -> fromHttp(result.code, null)
            null -> UNKNOWN
        }
    }
}

/**
 * A parsed gecko-client event; only fields that cannot carry a secret. [entry]
 * names a server of the fleet: it goes to the panel's report endpoint and
 * never into a log line ([describe] leaves it out).
 */
data class ColituGeckoEvent(
    val kind: String,
    val anchor: Long? = null,
    val bootstrap: Boolean? = null,
    val reason: String? = null,
    val skin: String? = null,
    val rev: String? = null,
    val cls: String? = null,
    val bytes: Long? = null,
    val bucket: String? = null,
    val entry: String? = null,
    // grip_end only: exact figures, kept in memory; ColituGeckoReports cuts them into slice labels.
    val lifeMs: Long? = null,
    val received: Long? = null,
    val rttMs: Long? = null,
    val goodput: Long? = null,
    val sent: Long? = null,
    // config / skin_revs only (F-14, F-15).
    val issued: Long? = null,
    val revs: Map<String, Long>? = null,
) {
    fun describe(): String = when (kind) {
        "session" -> "session (anchor ${anchor ?: "?"}, bootstrap ${bootstrap ?: "?"})"
        "need_config" -> "needs a new configuration ($reason)"
        "grip" -> "grip (skin ${skin ?: "?"}, rev ${rev ?: "?"}, class ${cls ?: "?"}, bucket ${bucket ?: "?"}, ${bytes ?: "?"} bytes)"
        // Nothing raw is described: only the class.
        "grip_end" -> "grip end (class ${cls ?: "?"})"
        "config" -> "configuration accepted (issued ${issued ?: "?"})"
        "config_refused" -> "configuration refused (${reason ?: "?"}); the last valid one stays"
        "skin_revs" -> "skin revisions (${revs?.size ?: 0} skins)"
        else -> kind
    }

    override fun toString() = describe()
}

/** gecko-client's loopback SOCKS port and per-run account. */
data class ColituGeckoLaunch(val socks: ColituCslAccount)

/** Gecko for one core start: the core config with Gecko, the launch, and the panel's configuration (a secret). */
class ColituGeckoPlan(val coreConfig: String, val launch: ColituGeckoLaunch, val config: ColituGeckoSignedConfig, val floor: ColituGeckoFloor = ColituGeckoFloor()) {
    override fun toString() = "gecko plan ${launch.socks} (configuration redacted)"
}

object ColituGeckoRules {
    const val OUTBOUND_TAG = "colitu-gecko"
    const val FILE_NAME = "libgeckoclient.so"

    /** The protocol's id: the picker's value, the transport of a Gecko connection, the AC journal's step name. */
    const val PROTOCOL = "gecko"

    /** The template's placeholder SOCKS port (discard): marks a Gecko-only profile; the VPN process puts gecko-client's in. */
    const val PLACEHOLDER_PORT = 9

    /** Rule tag of the private-ranges direct rule [geckoOnly] adds when no rule sends them direct. */
    const val PRIVATE_RULE_TAG = "colitu-gecko-private"

    /** How long gecko-client gets for its SOCKS port and a non-bootstrap session. */
    const val READY_DEADLINE_MS = 15_000L

    /** After a failed Gecko attempt Adaptive Connect skips Gecko this long; a manual pick ignores it. */
    const val COOL_DOWN_MS = 10 * 60_000L

    /** A connect waits at most this long for the panel's configuration when none is cached. */
    const val CONFIG_WAIT_MS = 6_000L

    /** `need_config` fetches a new configuration from the panel at most this often. */
    const val CONFIG_REFRESH_MS = 30_000L

    /**
     * Skin keys pinned in the app (id → 32-byte Ed25519 public key, hex). Id 2
     * is the production skin key: descriptors of real entries are signed with
     * it. The lab key (id 1) signs only the canary's and the tests'
     * descriptors and is deliberately not pinned, so the app never takes a
     * lab descriptor. Public keys; the same as the Windows app.
     */
    val SKIN_KEYS: Map<Int, String> = sortedMapOf(
        2 to "05c3ca41534e35dfe1f1ec807733a1d5b77d60ac24e272e29fcc1a0fdb5eb2fc",
    )

    /**
     * The configuration key pinned in the app (F-14): id 1, Ed25519 public
     * key. The panel signs `GET /gecko/config` with it and gecko-client
     * refuses anything else. Separate from the skin key; the same as the
     * Windows app.
     */
    val CONFIG_KEYS: Map<Int, String> = sortedMapOf(
        1 to "e0a6c63f2313311887b9e7e7f670390af3a16e8a9249b7d97b6ad32426615faa",
    )

    /** Public resolvers after the phone network's own, for gecko-client's lookups (COLITU_DNS). */
    val FALLBACK_DNS = listOf("1.1.1.1:53", "8.8.8.8:53")

    private val HEX_KEY = Regex("^[0-9a-f]{64}$")
    private val SAFE_TOKEN = Regex("^[A-Za-z0-9._-]{1,32}$")
    private val NEED_CONFIG_REASONS = setOf("half_life", "bootstrap", "expired")
    private const val EVENT_MARKER = "gecko-client: event "

    /** Outbound protocols a Gecko-only config keeps; every other outbound (a server's) is dropped. */
    private val localOutbounds = setOf("freedom", "blackhole", "dns", "loopback")

    /**
     * Why a Gecko-only core start cannot run; null when it can. Checked in
     * this order, so the reason names the first thing missing.
     */
    fun offReason(offer: ColituGeckoOffer, binaryPresent: Boolean): String? = when {
        offer == ColituGeckoOffer.NotOffered -> "not offered to this account"
        offer != ColituGeckoOffer.Offered -> "Gecko settings unavailable"
        !binaryPresent -> "gecko-client missing"
        else -> null
    }

    /**
     * Why the Gecko step of a connect is skipped; null when Gecko is tried.
     * [manual]: the user picked ColituGecko; it is tried whatever the
     * cool-down says (bug of 11-20: the cool-down silently skipped it while
     * the user kept trying) and on every connect. Automatic: once per
     * connect, not while cooling down after a failure.
     */
    fun stepSkip(offer: ColituGeckoOffer, hasConfig: Boolean, coolingDown: Boolean, manual: Boolean, triedThisConnect: Boolean): String? = when {
        offer == ColituGeckoOffer.NotOffered || offer == ColituGeckoOffer.Unknown -> "not offered"
        offer == ColituGeckoOffer.Unavailable -> "unavailable"
        !hasConfig -> "no configuration"
        manual -> null
        triedThisConnect -> "already tried"
        coolingDown -> "cooling down"
        else -> null
    }

    /** The steps of Adaptive Connect, in order: every normal server and transport, Gecko-only, the whitelist rows, Russia. */
    const val STEP_NORMAL = "normal"
    const val STEP_GECKO = PROTOCOL
    const val STEP_WHITELIST = "whitelist"
    const val STEP_RU = "ru"

    /** The first step of an automatic connect: in whitelist mode Gecko still goes before the whitelist rows. */
    fun firstStep(whitelistMode: Boolean, geckoUsable: Boolean): String = when {
        !whitelistMode -> STEP_NORMAL
        geckoUsable -> STEP_GECKO
        else -> STEP_WHITELIST
    }

    /** The step after [current] failed; null after the last. Gecko is left out when not usable ([stepSkip]). */
    /**
     * Whether Gecko takes the connect: picked by hand ([preferred] = [PROTOCOL]) and no Whitelist
     * row selected. A Whitelist row connects through the whitelist exit whatever protocol was
     * picked; the pick stays and applies again once a normal server is selected.
     */
    fun pickApplies(preferred: String, whitelistRowSelected: Boolean): Boolean =
        preferred == PROTOCOL && !whitelistRowSelected

    /** The automatic Gecko step is never part of a Whitelist row connect (owner: the row goes straight to the whitelist). */
    fun autoStepAllowed(whitelistRowSelected: Boolean): Boolean = !whitelistRowSelected

    fun nextStep(current: String, geckoUsable: Boolean): String? = when (current) {
        STEP_NORMAL -> if (geckoUsable) STEP_GECKO else STEP_WHITELIST
        STEP_GECKO -> STEP_WHITELIST
        STEP_WHITELIST -> STEP_RU
        else -> null
    }

    /** A cool-down from a failure at [failedAt] (0: none) is still running at [now]. */
    fun coolingDown(failedAt: Long, now: Long): Boolean = failedAt > 0 && now - failedAt in 0 until COOL_DOWN_MS

    /**
     * The state of a starting gecko-client: still waiting (null), on (empty
     * string) or off with a reason. Exiting wins over everything; on needs both
     * the SOCKS line and a non-bootstrap session.
     */
    fun verdict(listening: Boolean, session: Boolean, exited: Boolean, exitCode: Int?, elapsedMs: Long, deadlineMs: Long = READY_DEADLINE_MS): String? {
        val code = exitCode?.toString() ?: "?"
        return when {
            exited -> if (listening && session) "gecko-client exited (code $code)" else "gecko-client exited before its session came up (code $code)"
            listening && session -> ""
            elapsedMs >= deadlineMs -> if (listening) "no session within ${deadlineMs / 1000} s" else "no SOCKS port within ${deadlineMs / 1000} s"
            else -> null
        }
    }

    /** gecko-client logs this once its SOCKS port is open. */
    fun isReadyLine(line: String?): Boolean = line?.contains("gecko-client: SOCKS5 on ") == true

    /**
     * A `gecko-client: event {json}` line; null for any other line, malformed
     * JSON and unknown events. Only the fields the app needs are kept, and
     * only in a shape that cannot carry secrets.
     */
    fun parseEvent(line: String?): ColituGeckoEvent? {
        val at = line?.indexOf(EVENT_MARKER) ?: -1
        if (at < 0) return null
        val body = runCatching { JsonParser.parseString(line!!.substring(at + EVENT_MARKER.length)) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return when (val kind = str(body.get("event"))) {
            "session" -> ColituGeckoEvent(kind, anchor = long(body.get("anchor")), bootstrap = bool(body.get("bootstrap")))
            "need_config" -> ColituGeckoEvent(kind, reason = str(body.get("reason"))?.takeIf { it in NEED_CONFIG_REASONS } ?: "other")
            "grip" -> ColituGeckoEvent(
                kind,
                skin = token(body.get("skin")), rev = token(body.get("rev")), cls = token(body.get("class")),
                bytes = long(body.get("bytes")), bucket = token(body.get("bucket")), entry = token(body.get("entry")),
            )
            "grip_end" -> ColituGeckoEvent(
                kind,
                skin = token(body.get("skin")), rev = token(body.get("rev")), cls = token(body.get("class")), entry = token(body.get("entry")),
                lifeMs = long(body.get("life_ms")), received = long(body.get("received")), sent = long(body.get("sent")),
                rttMs = long(body.get("rtt_ms")), goodput = long(body.get("goodput")),
            )
            "config" -> ColituGeckoEvent(kind, issued = long(body.get("issued")))
            "config_refused" -> ColituGeckoEvent(kind, reason = str(body.get("reason"))?.takeIf { it == "signature" || it == "old" } ?: "other")
            "skin_revs" -> ColituGeckoEvent(kind, revs = revs(body.get("revs")))
            "lost" -> ColituGeckoEvent(kind)
            else -> null
        }
    }

    /** The panel's configuration is a JSON object with a non-empty `anchors` array; nothing else is checked. */
    fun isUsableConfig(json: String?): Boolean = parseConfig(json) != null

    /**
     * The header value as the panel sends it: hex of one key-id byte and a
     * 64-byte signature. Only the shape is checked here; whether it verifies
     * is gecko-client's job (it holds the pinned key).
     */
    fun isSignatureShape(signature: String?): Boolean = signature != null && SIGNATURE_SHAPE.matches(signature)

    private val SIGNATURE_SHAPE = Regex("^[0-9a-fA-F]{130}$")

    /**
     * A signed configuration from the exact response bytes and the signature
     * header; null when the header is missing or malformed or the body is not
     * a usable configuration. The body is never parsed and written again: the
     * signature covers these bytes.
     */
    fun sign(body: ByteArray?, signature: String?): ColituGeckoSignedConfig? {
        val sig = signature?.trim()
        if (body == null || body.isEmpty() || !isSignatureShape(sig)) return null
        if (!isUsableConfig(String(body, Charsets.UTF_8))) return null
        return ColituGeckoSignedConfig(body, sig!!.lowercase())
    }

    /** Why a 200 answer cannot start Gecko (no secret in it). */
    fun unusableReason(body: String?, signature: String?): String = when {
        signature.isNullOrBlank() -> "signature header missing"
        !isSignatureShape(signature.trim()) -> "signature header malformed"
        else -> "configuration not usable"
    }

    /** The stored floor with new values folded in: each number only ever goes up. */
    fun mergeFloor(stored: ColituGeckoFloor, issued: Long?, revs: Map<String, Long>?): ColituGeckoFloor {
        val map = stored.skinFloor.toMutableMap()
        revs?.forEach { (skin, rev) -> if (rev >= 0 && rev > (map[skin] ?: -1L)) map[skin] = rev }
        return ColituGeckoFloor(maxOf(stored.minIssued, issued ?: 0L), map)
    }

    /** The `revs` object of a skin_revs event: skin id (a safe token) to a non-negative revision. */
    private fun revs(e: JsonElement?): Map<String, Long>? {
        if (e == null || !e.isJsonObject) return null
        return e.asJsonObject.entrySet().mapNotNull { (skin, value) ->
            val rev = long(value)
            if (SAFE_TOKEN.matches(skin) && rev != null && rev >= 0) skin to rev else null
        }.toMap()
    }

    private fun parseConfig(json: String?): JsonObject? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
                ?.takeIf { root -> root.get("anchors")?.takeIf { it.isJsonArray }?.asJsonArray?.size()?.let { it > 0 } == true }
        }.getOrNull()
    }

    /**
     * Android's trust stores, newest first. gecko-client is a linux Go binary:
     * without SSL_CERT_DIR it finds no root at all on Android (checked on a
     * phone 2026-11-21: 0 roots, "certificate signed by unknown authority" on
     * every WebPKI-verified entry); with these it reads the system CAs.
     */
    val CERT_DIRS = listOf("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts")

    /** SSL_CERT_DIR for gecko-client: the trust stores that exist, colon separated; null when none does. */
    fun certDir(exists: (String) -> Boolean = { File(it).isDirectory }): String? =
        CERT_DIRS.filter(exists).joinToString(":").ifEmpty { null }

    /** The command line; secrets never appear on it ([secrets] goes to standard input). The config and skin keys are public. */
    fun arguments(launch: ColituGeckoLaunch): List<String> = buildList {
        add("-socks"); add("127.0.0.1:${launch.socks.port}"); add("-secrets-stdin")
        CONFIG_KEYS.forEach { (id, key) ->
            if (id > 0 && HEX_KEY.matches(key)) {
                add("-config-key"); add("$id=$key")
            }
        }
        SKIN_KEYS.forEach { (id, key) ->
            if (id > 0 && HEX_KEY.matches(key)) {
                add("-skin-key"); add("$id=$key")
            }
        }
    }

    /**
     * The first line on gecko-client's standard input: the per-run SOCKS
     * account, the panel's configuration as the exact response bytes
     * (`config_b64`) with its signature (`config_sig`), and what the app kept
     * from earlier runs: the highest accepted `issued` (`min_issued`) and the
     * highest skin revisions (`skin_floor`), both left out when there are none.
     * One line.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun secrets(launch: ColituGeckoLaunch, config: ColituGeckoSignedConfig, floor: ColituGeckoFloor = ColituGeckoFloor()): String = JsonObject().apply {
        addProperty("socks_user", launch.socks.user)
        addProperty("socks_password", launch.socks.password)
        addProperty("config_b64", Base64.encode(config.body))
        addProperty("config_sig", config.signature)
        if (floor.minIssued > 0) addProperty("min_issued", floor.minIssued)
        if (floor.skinFloor.isNotEmpty()) {
            add("skin_floor", JsonObject().also { map -> floor.skinFloor.toSortedMap().forEach { (skin, rev) -> map.addProperty(skin, rev) } })
        }
    }.toString() + "\n"

    /** A later standard-input line: a new configuration replaces the running one (if it verifies). */
    @OptIn(ExperimentalEncodingApi::class)
    fun configLine(config: ColituGeckoSignedConfig): String = JsonObject().apply {
        addProperty("config_b64", Base64.encode(config.body))
        addProperty("config_sig", config.signature)
    }.toString() + "\n"

    /**
     * The Gecko-only profile's raw config, before the app's layers (Russian
     * direct, split tunnel, ad blocking, DNS through the tunnel, the check
     * inbound) go on like on any panel profile: the same skeleton as
     * [XrayMobileAdapter]'s, with `proxy` a SOCKS outbound to loopback on
     * [PLACEHOLDER_PORT]. No server: the panel's anchors choose the exit. The
     * VPN process turns it into the running config ([geckoOnly]).
     */
    fun profileTemplate(): String = JsonObject().apply {
        add("log", JsonObject().apply {
            addProperty("loglevel", "warning")
            addProperty("access", "none")
            addProperty("dnsLog", false)
        })
        add("inbounds", JsonArray())
        add("outbounds", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("tag", ColituWarmSpare.PRIMARY_TAG)
                addProperty("protocol", "socks")
                add("settings", JsonObject().apply {
                    add("servers", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("address", "127.0.0.1")
                            addProperty("port", PLACEHOLDER_PORT)
                        })
                    })
                })
            })
            add(JsonObject().apply {
                addProperty("tag", "direct")
                addProperty("protocol", "freedom")
            })
        })
        // No dns_policy: ColituDnsRoute gives it DoH resolvers by address, asked through the tunnel (TCP over Gecko).
        add("dns", JsonObject())
        add("routing", JsonObject())
        add("stats", JsonObject())
        add("policy", JsonObject().apply {
            add("system", JsonObject().apply {
                addProperty("statsOutboundUplink", true)
                addProperty("statsOutboundDownlink", true)
            })
        })
    }.toString()

    /** [config] was made from [profileTemplate]: its `proxy` outbound is the loopback placeholder. */
    fun isGeckoProfile(config: String?): Boolean = runCatching {
        val root = JsonParser.parseString(config ?: return false).asJsonObject
        val proxy = root.getAsJsonArray("outbounds")?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            ?.firstOrNull { tagOf(it) == ColituWarmSpare.PRIMARY_TAG } ?: return false
        val server = proxy.getAsJsonObject("settings")?.getAsJsonArray("servers")?.firstOrNull()?.asJsonObject ?: return false
        str(proxy.get("protocol")) == "socks" && str(server.get("address")) == "127.0.0.1" && long(server.get("port")) == PLACEHOLDER_PORT.toLong()
    }.getOrDefault(false)

    /**
     * The running config of a Gecko-only start: [config] (from
     * [profileTemplate], with the app's layers on) with
     * - the `colitu-gecko` SOCKS outbound to gecko-client ([socks]) first, so
     *   whatever no rule matches goes to Gecko too, TCP and UDP;
     * - every outbound that is not local (direct, block, dns) dropped, and
     *   every rule that sent to one of them, or to a balancer, sent to Gecko
     *   (the user's traffic, the check inbound, the core's own DNS);
     * - balancers and observatories removed (nothing left to balance);
     * - Russian direct rules and a private-ranges direct rule ahead of the
     *   first open rule to Gecko (one without an inbound tag), added when
     *   missing; rules with an inbound tag (the check, the DNS) stay in front.
     * Null when [config] is not a Gecko-only profile.
     */
    fun geckoOnly(config: String, socks: ColituCslAccount): String? = runCatching {
        if (!isGeckoProfile(config)) return null
        val root = JsonParser.parseString(config).asJsonObject
        val old = root.getAsJsonArray("outbounds").mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
        val kept = old.filter { str(it.get("protocol")) in localOutbounds }
        val dropped = old.filterNot { it in kept }.mapNotNull(::tagOf).toSet()
        root.add("outbounds", JsonArray().apply {
            add(socksOutbound(socks))
            kept.forEach(::add)
        })
        val routing = root.getAsJsonObject("routing") ?: JsonObject().also { root.add("routing", it) }
        routing.remove("balancers")
        root.remove("observatory")
        root.remove("burstObservatory")
        val rules = routing.getAsJsonArray("rules")?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }.orEmpty().toMutableList()
        rules.forEach { rule ->
            val target = str(rule.get("outboundTag"))
            if (rule.has("balancerTag") || target == null || target in dropped) {
                rule.remove("balancerTag")
                rule.addProperty("outboundTag", OUTBOUND_TAG)
            }
        }
        fun opensToGecko(rule: JsonObject) = str(rule.get("outboundTag")) == OUTBOUND_TAG && strings(rule.get("inboundTag")).isEmpty()
        val missing = buildList {
            if (rules.none(::sendsPrivateDirect)) add(directRule("ip", listOf("geoip:private"), PRIVATE_RULE_TAG))
            if (rules.none { str(it.get("ruleTag")) == ColituRuBypass.RULE_TAG }) {
                add(directRule("domain", ColituRuBypass.DIRECT_DOMAINS, ColituRuBypass.RULE_TAG))
                add(directRule("ip", listOf("geoip:ru"), ColituRuBypass.RULE_TAG))
            }
        }
        if (missing.isNotEmpty()) {
            val firstRu = rules.indexOfFirst { str(it.get("ruleTag")) == ColituRuBypass.RULE_TAG }
            val firstOpen = rules.indexOfFirst(::opensToGecko)
            val at = listOf(firstRu, firstOpen).filter { it >= 0 }.minOrNull() ?: rules.size
            rules.addAll(at, missing)
        }
        routing.add("rules", JsonArray().apply { rules.forEach(::add) })
        root.toString()
    }.getOrNull()

    private fun socksOutbound(socks: ColituCslAccount) = JsonObject().apply {
        addProperty("tag", OUTBOUND_TAG)
        addProperty("protocol", "socks")
        add("settings", JsonObject().apply {
            add("servers", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("address", "127.0.0.1")
                    addProperty("port", socks.port)
                    add("users", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("user", socks.user)
                            addProperty("pass", socks.password)
                        })
                    })
                })
            })
        })
    }

    private fun sendsPrivateDirect(rule: JsonObject): Boolean =
        str(rule.get("outboundTag")) == "direct" && strings(rule.get("ip")).any { it.equals("geoip:private", ignoreCase = true) }

    private fun directRule(field: String, values: List<String>, ruleTag: String) = JsonObject().apply {
        addProperty("type", "field")
        add(field, JsonArray().apply { values.forEach(::add) })
        addProperty("outboundTag", "direct")
        addProperty("ruleTag", ruleTag)
    }

    /**
     * COLITU_DNS for gecko-client: the network's resolvers as `host:port`
     * (scoped IPv6 addresses left out: Go would need the zone), then
     * [FALLBACK_DNS], without repeats.
     */
    fun dnsList(network: List<InetAddress>): String {
        val servers = network.mapNotNull { address ->
            when {
                address is Inet4Address -> "${address.hostAddress}:53"
                address is Inet6Address && !address.isLinkLocalAddress && address.scopeId == 0 ->
                    "[${address.hostAddress?.substringBefore('%')}]:53"
                else -> null
            }
        }
        return (servers + FALLBACK_DNS).distinct().joinToString(",")
    }

    private fun tagOf(o: JsonObject): String? = str(o.get("tag"))

    private fun str(e: JsonElement?): String? = e?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun long(e: JsonElement?): Long? =
        e?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toLongOrNull()

    private fun bool(e: JsonElement?): Boolean? = e?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    /** A short scalar as text when it cannot hold more than a label; otherwise null. */
    private fun token(e: JsonElement?): String? {
        val p = e?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        val text = when {
            p.isString -> p.asString
            p.isNumber -> p.asString
            else -> return null
        }
        return text.takeIf { SAFE_TOKEN.matches(it) }
    }

    private fun strings(e: JsonElement?): List<String> = when {
        e == null -> emptyList()
        e.isJsonArray -> e.asJsonArray.mapNotNull { str(it) }
        else -> listOfNotNull(str(e))
    }

    /**
     * The offer to remember after an answer: an unknown answer (no network,
     * 401) keeps what the last real one said.
     */
    fun nextOffer(previous: ColituGeckoOffer, result: ColituGeckoConfigResult): ColituGeckoOffer =
        if (result.offer == ColituGeckoOffer.Unknown) previous else result.offer

    /** The picker lists ColituGecko when the panel offered it at the last real answer (404 and never asked: hidden). */
    fun listed(offer: ColituGeckoOffer): Boolean = offer == ColituGeckoOffer.Offered || offer == ColituGeckoOffer.Unavailable

    /** Listed and pickable: offered with a usable configuration. A 503 lists it disabled, with a short reason. */
    fun pickable(offer: ColituGeckoOffer): Boolean = offer == ColituGeckoOffer.Offered
}

/**
 * Where the app keeps Gecko's state for the VPN process (it runs in its own
 * process and also starts from the tile, the widget and Always-on): the
 * panel's last offer, the configuration sealed with the app's Keystore key,
 * whether Gecko carries the running connection and the last failure (the
 * cool-down). Multi-process stores.
 */
object ColituGeckoStore {
    /** The alpha's Advanced-mode switch and route kind: no longer read, removed once ([forgetAlpha]). */
    private const val KEY_ALPHA_ENABLED = "gecko_alpha"
    private const val KEY_ALPHA_MULTIHOP = "multihop"
    private const val KEY_OFFER = "gecko_offer"
    private const val KEY_CONFIG = "config"
    private const val KEY_MIN_ISSUED = "min_issued"
    private const val KEY_SKIN_FLOOR = "skin_floor"
    private const val KEY_FETCHED_AT = "fetched_at"
    private const val KEY_ACTIVE = "active"
    private const val KEY_FAILED_AT = "failed_at"
    private const val KEY_FAILURE = "failure"
    private const val SEAL_CONTEXT = "colitu-gecko-v1"
    private val settings by lazy { MMKV.mmkvWithID("COLITU_SETTINGS", MMKV.MULTI_PROCESS_MODE) }
    private val store by lazy { MMKV.mmkvWithID("COLITU_GECKO", MMKV.MULTI_PROCESS_MODE) }

    /** Drops what the alpha stored (its switch and route kind): ColituGecko is a protocol, the panel's offer decides. */
    fun forgetAlpha() {
        runCatching {
            if (settings.containsKey(KEY_ALPHA_ENABLED)) settings.removeValueForKey(KEY_ALPHA_ENABLED)
            if (store.containsKey(KEY_ALPHA_MULTIHOP)) store.removeValueForKey(KEY_ALPHA_MULTIHOP)
        }
    }

    var offer: ColituGeckoOffer
        get() = runCatching { ColituGeckoOffer.valueOf(settings.decodeString(KEY_OFFER, null) ?: "Unknown") }.getOrDefault(ColituGeckoOffer.Unknown)
        private set(value) {
            settings.encode(KEY_OFFER, value.name)
        }

    /** When the last Gecko attempt failed (0: none since the last success); the cool-down runs from it. */
    val failedAt: Long get() = runCatching { store.decodeLong(KEY_FAILED_AT, 0L) }.getOrDefault(0L)

    /** Why the last Gecko attempt failed (a short reason without secrets); null when none. */
    val failure: String? get() = runCatching { store.decodeString(KEY_FAILURE, null) }.getOrNull()

    fun markFailed(reason: String, at: Long = System.currentTimeMillis()) {
        runCatching {
            store.encode(KEY_FAILED_AT, at)
            store.encode(KEY_FAILURE, reason.take(120))
        }
    }

    /** A Gecko connection carried traffic: the cool-down ends. */
    fun clearFailure() {
        runCatching {
            store.removeValueForKey(KEY_FAILED_AT)
            store.removeValueForKey(KEY_FAILURE)
        }
    }

    /** Gecko carries the running connection (set by the VPN process; the home screen's protocol chip reads it). */
    var active: Boolean
        get() = runCatching { store.decodeBool(KEY_ACTIVE, false) }.getOrDefault(false)
        set(value) {
            store.encode(KEY_ACTIVE, value)
        }

    val fetchedAt: Long get() = runCatching { store.decodeLong(KEY_FETCHED_AT, 0L) }.getOrDefault(0L)

    /**
     * Takes the panel's answer: the offer (an unknown answer keeps the last
     * one), and the configuration when it is offered; a not-offered or
     * unavailable answer drops a stored one.
     */
    fun remember(result: ColituGeckoConfigResult) {
        offer = ColituGeckoRules.nextOffer(offer, result)
        runCatching {
            when (result.offer) {
                ColituGeckoOffer.Offered -> {
                    store.encode(KEY_FETCHED_AT, System.currentTimeMillis())
                    if (result.config != null) store.encode(KEY_CONFIG, ColituSecureStore.sealConfig(pack(result.config), SEAL_CONTEXT))
                }
                ColituGeckoOffer.NotOffered, ColituGeckoOffer.Unavailable -> store.removeValueForKey(KEY_CONFIG)
                ColituGeckoOffer.Unknown -> Unit
            }
        }
    }

    /** Signature, a line break, the body in base64: sealed as one string. */
    @OptIn(ExperimentalEncodingApi::class)
    internal fun pack(config: ColituGeckoSignedConfig): String = config.signature + "\n" + Base64.encode(config.body)

    @OptIn(ExperimentalEncodingApi::class)
    internal fun unpack(packed: String): ColituGeckoSignedConfig? {
        val parts = packed.split("\n", limit = 2)
        if (parts.size != 2) return null
        val body = runCatching { Base64.decode(parts[1]) }.getOrNull()
        return ColituGeckoRules.sign(body, parts[0])
    }

    fun config(): ColituGeckoSignedConfig? = runCatching {
        val sealed = store.decodeString(KEY_CONFIG) ?: return null
        unpack(ColituSecureStore.openConfig(sealed, SEAL_CONTEXT))
    }.getOrNull()

    /** The highest accepted `issued` and the highest skin revisions kept across runs; both only ever go up. */
    fun floor(): ColituGeckoFloor = runCatching {
        val revs = runCatching {
            JsonParser.parseString(store.decodeString(KEY_SKIN_FLOOR, "") ?: "").takeIf { it.isJsonObject }?.asJsonObject
                ?.entrySet()?.mapNotNull { (skin, value) -> runCatching { skin to value.asLong }.getOrNull() }?.toMap()
        }.getOrNull().orEmpty()
        ColituGeckoFloor(store.decodeLong(KEY_MIN_ISSUED, 0L), revs)
    }.getOrDefault(ColituGeckoFloor())

    /** Folds an accepted `issued` or the held skin revisions into the stored floor (monotonic). */
    @Synchronized
    fun remember(issued: Long?, revs: Map<String, Long>?) {
        val before = floor()
        val after = ColituGeckoRules.mergeFloor(before, issued, revs)
        if (after == before) return
        runCatching {
            store.encode(KEY_MIN_ISSUED, after.minIssued)
            store.encode(KEY_SKIN_FLOOR, JsonObject().also { map -> after.skinFloor.toSortedMap().forEach { (skin, rev) -> map.addProperty(skin, rev) } }.toString())
        }
    }
}

/** The gecko-client sidecar in the VPN process; one at a time. */
object ColituGeckoRuntime {
    private const val TAG = "ColituGecko"
    private const val REPORTS_PATH = "/gecko/reports"
    private const val CONFIG_PATH = "/gecko/config"
    private const val MAX_LOGGED_LINES = 40

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile private var process: Process? = null
    @Volatile private var stdin: BufferedWriter? = null
    private val generation = AtomicInteger()
    private val refreshedAt = AtomicLong()
    private val refreshing = AtomicBoolean()

    fun binary(context: Context): File = File(context.applicationInfo.nativeLibraryDir, ColituGeckoRules.FILE_NAME)

    val running: Boolean get() = process?.isRunning() == true

    /** Error of a Gecko-only start that cannot run (the connect flow shows it or moves on). */
    const val UNAVAILABLE = "GECKO_UNAVAILABLE"

    /**
     * The Gecko-only start for [content], or null when [content] is not a
     * Gecko-only profile (every other protocol: no Gecko at all). Throws
     * [UNAVAILABLE] when it is one but cannot run (not offered, no
     * configuration, gecko-client missing): the start fails, nothing else is
     * started in its place. Wins over CSL alpha.
     */
    fun plan(context: Context, content: String): ColituGeckoPlan? {
        if (!ColituGeckoRules.isGeckoProfile(content)) return null
        val config = ColituGeckoStore.config()
        val stored = ColituGeckoStore.offer
        val offer = if (config != null && stored == ColituGeckoOffer.Offered) ColituGeckoOffer.Offered
        else if (stored == ColituGeckoOffer.NotOffered) ColituGeckoOffer.NotOffered else ColituGeckoOffer.Unavailable
        val socks = ColituCslRules.newAccount()
        val off = ColituGeckoRules.offReason(offer, binary(context).exists())
        if (off == null) {
            val core = ColituGeckoRules.geckoOnly(content, socks)
            if (core != null) return ColituGeckoPlan(core, ColituGeckoLaunch(socks), config!!, ColituGeckoStore.floor())
        }
        val reason = off ?: "the core config could not take Gecko"
        Log.w(TAG, "Colitu: Gecko: cannot start ($reason)")
        ColituGeckoStore.markFailed(reason)
        error(UNAVAILABLE)
    }

    /**
     * Starts gecko-client for [plan] (after the core started with
     * [ColituGeckoPlan.coreConfig]). [onFailure] runs (once, on a background
     * thread) when it exits, or misses its deadline; it is then stopped and
     * the failure stored (the cool-down). The core keeps its Gecko-only
     * config: nothing is switched silently.
     */
    fun start(context: Context, plan: ColituGeckoPlan, onFailure: (String) -> Unit) {
        stop()
        val gen = generation.incrementAndGet()
        val started = System.currentTimeMillis()
        val secrets = runCatching { ColituGeckoRules.secrets(plan.launch, plan.config, plan.floor) }.getOrElse {
            fail(gen, "unusable Gecko configuration", onFailure)
            return
        }
        val proc = runCatching {
            ProcessBuilder(listOf(binary(context).absolutePath) + ColituGeckoRules.arguments(plan.launch))
                .also { pb ->
                    pb.environment()["COLITU_DNS"] = dnsServers(context)
                    ColituGeckoRules.certDir()?.let { pb.environment()["SSL_CERT_DIR"] = it }
                }
                .start()
        }.getOrElse {
            fail(gen, "gecko-client could not start (${it.javaClass.simpleName})", onFailure)
            return
        }
        val writer = proc.outputStream.bufferedWriter(Charsets.UTF_8)
        synchronized(lock) {
            process = proc
            stdin = writer
        }
        runCatching {
            writer.write(secrets)
            writer.flush()
        }.onFailure {
            fail(gen, "gecko-client did not take its configuration", onFailure)
            return
        }
        Log.w(TAG, "Colitu: Gecko: gecko-client started (SOCKS 127.0.0.1:${plan.launch.socks.port})")
        // The panel offered Gecko (a plan needs that): reports a 404 had stopped may go again.
        ColituGeckoReports.resume()
        val listening = AtomicBoolean()
        val session = AtomicBoolean()
        fun checkReady() {
            if (ColituGeckoRules.verdict(listening.get(), session.get(), false, null, System.currentTimeMillis() - started) == "" &&
                generation.get() == gen && !ColituGeckoStore.active
            ) {
                ColituGeckoStore.active = true
                Log.w(TAG, "Colitu: Gecko: on after ${System.currentTimeMillis() - started} ms")
            }
        }
        Thread({
            var lines = 0
            runCatching {
                proc.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    if (line.isEmpty()) return@forEachLine
                    val entry: String
                    if (ColituGeckoRules.isReadyLine(line)) {
                        listening.set(true)
                        entry = "SOCKS port open"
                    } else {
                        val event = ColituGeckoRules.parseEvent(line)
                        entry = event?.describe() ?: "(other output, ${line.length} chars)" // may echo configuration: its length only
                        if (event != null) {
                            when (event.kind) {
                                "session" -> if (event.bootstrap == false) session.set(true)
                                "need_config" -> if (generation.get() == gen) refreshConfig(event.reason ?: "other")
                                "config", "skin_revs" -> if (generation.get() == gen) ColituGeckoStore.remember(event.issued, event.revs)
                                "grip" -> ColituGeckoReports.add(event, System.currentTimeMillis())
                                "grip_end" -> ColituGeckoReports.addEnd(event, System.currentTimeMillis())
                            }
                        }
                    }
                    checkReady()
                    if (++lines <= MAX_LOGGED_LINES) Log.w(TAG, "Colitu: Gecko | $entry")
                }
            }
            val code = runCatching { proc.waitFor() }.getOrNull()
            val reason = ColituGeckoRules.verdict(listening.get(), session.get(), true, code, System.currentTimeMillis() - started)!!
            fail(gen, reason, onFailure)
        }, "colitu-gecko-stderr").apply { isDaemon = true }.start()
        // Standard output carries nothing the app needs; drained so gecko-client never blocks on it.
        Thread({
            runCatching {
                val buffer = ByteArray(4096)
                proc.inputStream.use { input ->
                    do {
                        val read = input.read(buffer)
                    } while (read >= 0)
                }
            }
        }, "colitu-gecko-stdout")
            .apply { isDaemon = true }.start()
        Thread({
            Thread.sleep(ColituGeckoRules.READY_DEADLINE_MS)
            val verdict = ColituGeckoRules.verdict(listening.get(), session.get(), false, null, System.currentTimeMillis() - started)
            if (!verdict.isNullOrEmpty()) fail(gen, verdict, onFailure)
        }, "colitu-gecko-deadline").apply { isDaemon = true }.start()
        scope.launch {
            ColituGeckoReports.pump(
                running = { generation.get() == gen },
                post = { body -> ColituApiClient.postPassive(REPORTS_PATH, body) },
            )
        }
    }

    /** Hands the running gecko-client a new configuration; false when none runs. */
    fun sendConfig(config: ColituGeckoSignedConfig): Boolean {
        val line = ColituGeckoRules.configLine(config)
        synchronized(lock) {
            val writer = stdin ?: return false
            if (process?.isRunning() != true) return false
            return runCatching {
                writer.write(line)
                writer.flush()
            }.isSuccess
        }
    }

    /** Stops gecko-client (if any): its standard input is closed (it then exits), then it is ended. Not reported as a failure. */
    fun stop() {
        generation.incrementAndGet()
        val proc: Process?
        val writer: BufferedWriter?
        synchronized(lock) {
            proc = process
            writer = stdin
            process = null
            stdin = null
        }
        ColituGeckoStore.active = false
        runCatching { writer?.close() }
        runCatching { proc?.destroy() }
    }

    /**
     * gecko-client asked for a new configuration: fetched from the panel (a
     * passive call: the VPN process never refreshes the session) at most once
     * per [ColituGeckoRules.CONFIG_REFRESH_MS] and handed over.
     */
    private fun refreshConfig(reason: String) {
        val now = System.currentTimeMillis()
        val last = refreshedAt.get()
        if (last != 0L && now - last < ColituGeckoRules.CONFIG_REFRESH_MS) return
        if (!refreshing.compareAndSet(false, true)) return
        refreshedAt.set(now)
        scope.launch {
            try {
                Log.w(TAG, "Colitu: Gecko: new configuration requested ($reason)")
                val result = ColituGeckoConfigResult.fromApi(runCatching { ColituApiClient.getPassive(CONFIG_PATH, direct = true) }.getOrNull())
                ColituGeckoStore.remember(result)
                if (result.offer == ColituGeckoOffer.Offered) ColituGeckoReports.resume()
                val config = result.config
                Log.w(
                    TAG,
                    "Colitu: Gecko: panel answered ${result.offer}" + (result.reason?.let { " ($it); Gecko is not started" } ?: "") +
                        if (config == null) "" else if (sendConfig(config)) "; new configuration handed over" else "; gecko-client did not take it",
                )
            } finally {
                refreshing.set(false)
            }
        }
    }

    private fun fail(gen: Int, reason: String, onFailure: (String) -> Unit) {
        if (!generation.compareAndSet(gen, gen + 1)) return
        ColituGeckoStore.markFailed(reason)
        Log.w(TAG, "Colitu: Gecko: failed ($reason); the core keeps the Gecko-only config, the connect flow decides")
        val proc: Process?
        val writer: BufferedWriter?
        synchronized(lock) {
            proc = process
            writer = stdin
            process = null
            stdin = null
        }
        ColituGeckoStore.active = false
        runCatching { writer?.close() }
        runCatching { proc?.destroy() }
        onFailure(reason)
    }

    /** The resolvers of the phone's network (not the VPN), then the public fallbacks. */
    private fun dnsServers(context: Context): String = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val networks = cm.allNetworks.filter { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@filter false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        val active = cm.activeNetwork
        val ordered = networks.sortedBy { if (it == active) 0 else 1 }
        ColituGeckoRules.dnsList(ordered.flatMap { cm.getLinkProperties(it)?.dnsServers.orEmpty() })
    }.getOrElse { ColituGeckoRules.dnsList(emptyList()) }
}
