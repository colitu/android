package com.v2ray.ang.colitu.data

import android.content.Context
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tencent.mmkv.MMKV
import com.v2ray.ang.colitu.api.ColituSecureStore
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * CSL alpha (Colitu Session Layer, github.com/colitu/csl): open TCP
 * connections survive a carrier change. Tester accounts only, on nodes the
 * panel enabled it for (the `/config` envelope then carries `csl`), opt-in in
 * Advanced mode. Same design as the Windows app:
 *
 * - The core keeps working as usual; every rule that sends TCP into the
 *   tunnel gets a copy in front that sends it to the local csl-client
 *   (`colitu-csl` SOCKS outbound, per-run account).
 * - csl-client carries those streams to the node's csl-server through one or
 *   two carrier inbounds of the core (`colitu-csl-primary` to `proxy`, and
 *   `colitu-csl-spare` to the warm spare when it runs on the same node).
 * - csl-client is `libcslclient.so` in the APK's native library directory
 *   (an executable). The node credential and the SOCKS account reach it on
 *   standard input only, never a file or the command line.
 * - Fallback: csl-client missing, exiting, or without a carrier within
 *   [CARRIER_DEADLINE_MS]: the core runs again without CSL ([ColituCslRuntime]).
 */
data class ColituCslConfig(val version: Int, val port: Int, val credential: String) {
    /** Version 1, a port, and a credential of 64 lowercase hex digits. */
    val usable: Boolean get() = version == 1 && port in 1..65535 && CREDENTIAL.matches(credential)

    override fun toString() = "csl v$version port $port credential redacted"

    fun toJson(): String = JsonObject().apply {
        addProperty("version", version)
        addProperty("port", port)
        addProperty("credential", credential)
    }.toString()

    companion object {
        private val CREDENTIAL = Regex("^[0-9a-f]{64}$")

        /** The envelope's `csl` object, or null when absent or not usable. */
        fun fromJson(json: JsonElement?): ColituCslConfig? = runCatching {
            val o = json?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            ColituCslConfig(
                o.get("version")?.asInt ?: 0,
                o.get("port")?.asInt ?: 0,
                o.get("credential")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
            ).takeIf { it.usable }
        }.getOrNull()
    }
}

/** A loopback SOCKS port and its per-run account. */
data class ColituCslAccount(val port: Int, val user: String, val password: String) {
    override fun toString() = "127.0.0.1:$port (account redacted)"
}

data class ColituCslCarrier(val label: String, val inbound: ColituCslAccount, val family: String)

/** csl-client's SOCKS account, the node's CSL port and the carriers. */
data class ColituCslLaunch(val socks: ColituCslAccount, val serverPort: Int, val carriers: List<ColituCslCarrier>)

object ColituCslRules {
    const val OUTBOUND_TAG = "colitu-csl"
    const val PRIMARY_INBOUND = "colitu-csl-primary"
    const val SPARE_INBOUND = "colitu-csl-spare"
    const val PRIMARY_LABEL = "primary"
    const val SPARE_LABEL = "spare"

    /** How long csl-client gets for its first carrier. */
    const val CARRIER_DEADLINE_MS = 10_000L

    /** Inbounds whose traffic is the app's own (checks, carriers): never sent into CSL. */
    private val internalInbounds = setOf(XrayMobileAdapter.VERIFY_TAG, XrayMobileAdapter.VERIFY_SPARE_TAG, PRIMARY_INBOUND, SPARE_INBOUND)

    /** csl-client logs this once its session has a carrier and its SOCKS port is open. */
    fun isReadyLine(line: String): Boolean = line.contains("csl-client: SOCKS5 on")

    /** Hysteria travels over UDP; everything else over TCP (a hint for csl-client's lanes). */
    fun familyOf(outboundProtocol: String?): String = if (outboundProtocol?.startsWith("hysteria") == true) "udp" else "tcp"

    /**
     * Adds CSL to a core config: the result and the launch for csl-client, or
     * null when the config does not have the expected shape (it then runs
     * without CSL). [account] makes the loopback accounts (tests pass fixed ones).
     */
    fun apply(config: String, serverPort: Int, account: () -> ColituCslAccount): Pair<String, ColituCslLaunch>? = runCatching {
        val root = JsonParser.parseString(config).asJsonObject
        val outbounds = root.getAsJsonArray("outbounds") ?: return null
        val byTag = outbounds.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }.associateBy { tagOf(it) }
        val primary = byTag[ColituWarmSpare.PRIMARY_TAG] ?: return null
        if (byTag.containsKey(OUTBOUND_TAG)) return null
        // The node's csl-server is behind the primary; the spare only helps when it runs on the same node.
        val spare = byTag[ColituWarmSpare.SPARE_TAG]?.takeIf { hostOf(it) != null && hostOf(it) == hostOf(primary) }
        val socks = account()
        val carriers = mutableListOf(ColituCslCarrier(PRIMARY_LABEL, account(), familyOf(primary.str("protocol"))))
        if (spare != null) carriers += ColituCslCarrier(SPARE_LABEL, account(), familyOf(spare.str("protocol")))

        outbounds.add(JsonObject().apply {
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
        })
        val routing = root.getAsJsonObject("routing") ?: JsonObject().also { root.add("routing", it) }
        val rules = routing.getAsJsonArray("rules") ?: JsonArray().also { routing.add("rules", it) }
        val dnsTag = root.getAsJsonObject("dns")?.str("tag")
        // Every rule that sends TCP into the tunnel gets a TCP copy to csl-client in front of it.
        val copies = mutableListOf<Pair<Int, JsonObject>>()
        rules.forEachIndexed { index, element ->
            val rule = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEachIndexed
            val toTunnel = rule.str("outboundTag") == ColituWarmSpare.PRIMARY_TAG || rule.str("balancerTag") == ColituWarmSpare.BALANCER_TAG
            val inbounds = strings(rule.get("inboundTag"))
            if (!toTunnel || inbounds.any { it in internalInbounds || it == dnsTag } || !carriesTcp(rule.str("network"))) return@forEachIndexed
            val copy = rule.deepCopy()
            copy.remove("balancerTag")
            copy.remove("ruleTag")
            copy.addProperty("outboundTag", OUTBOUND_TAG)
            copy.addProperty("network", "tcp")
            copies += index to copy
        }
        copies.asReversed().forEach { (index, copy) -> insert(rules, index, copy) }
        // Xray sends traffic no rule matched to the first outbound.
        if (outbounds.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.let { tagOf(it) } == ColituWarmSpare.PRIMARY_TAG) {
            rules.add(JsonObject().apply {
                addProperty("type", "field")
                addProperty("network", "tcp")
                addProperty("outboundTag", OUTBOUND_TAG)
            })
        }
        if (spare != null) addCarrierInbound(root, rules, carriers[1].inbound, SPARE_INBOUND, ColituWarmSpare.SPARE_TAG)
        addCarrierInbound(root, rules, carriers[0].inbound, PRIMARY_INBOUND, ColituWarmSpare.PRIMARY_TAG)
        root.toString() to ColituCslLaunch(socks, serverPort, carriers)
    }.getOrNull()

    /** `socks5://user:pass@127.0.0.1:port/127.0.0.1:serverPort?family=…#label` (colitu-csl carrier syntax). */
    fun carrierSpec(carrier: ColituCslCarrier, serverPort: Int): String =
        "socks5://${enc(carrier.inbound.user)}:${enc(carrier.inbound.password)}@127.0.0.1:${carrier.inbound.port}" +
            "/127.0.0.1:$serverPort?family=${carrier.family}#${carrier.label}"

    /** The command line; secrets never appear on it ([secrets] goes to standard input). */
    fun arguments(launch: ColituCslLaunch): List<String> = buildList {
        add("-socks"); add("127.0.0.1:${launch.socks.port}"); add("-secrets-stdin")
        launch.carriers.forEach { add("-carrier"); add(carrierSpec(it, launch.serverPort)) }
        add("-v")
    }

    /** The one JSON line csl-client reads from standard input. */
    fun secrets(launch: ColituCslLaunch, credential: String): String = JsonObject().apply {
        addProperty("credential", credential)
        addProperty("socks_user", launch.socks.user)
        addProperty("socks_password", launch.socks.password)
    }.toString() + "\n"

    /** A free loopback port and a random account. */
    fun newAccount(): ColituCslAccount {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        return ColituCslAccount(port, hex(8), hex(16))
    }

    private fun addCarrierInbound(root: JsonObject, rules: JsonArray, account: ColituCslAccount, tag: String, outbound: String) {
        val inbounds = root.getAsJsonArray("inbounds") ?: JsonArray().also { root.add("inbounds", it) }
        inbounds.add(JsonObject().apply {
            addProperty("tag", tag)
            addProperty("listen", "127.0.0.1")
            addProperty("port", account.port)
            addProperty("protocol", "socks")
            add("settings", JsonObject().apply {
                addProperty("auth", "password")
                add("accounts", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("user", account.user)
                        addProperty("pass", account.password)
                    })
                })
                addProperty("udp", false)
            })
        })
        insert(rules, 0, JsonObject().apply {
            addProperty("type", "field")
            add("inboundTag", JsonArray().apply { add(tag) })
            addProperty("outboundTag", outbound)
        })
    }

    private fun insert(array: JsonArray, index: Int, value: JsonElement) {
        val tail = (index until array.size()).map { array[it] }
        while (array.size() > index) array.remove(array.size() - 1)
        array.add(value)
        tail.forEach { array.add(it) }
    }

    private fun carriesTcp(network: String?): Boolean =
        network == null || network.split(',').map { it.trim() }.any { it.equals("tcp", ignoreCase = true) }

    private fun hostOf(outbound: JsonObject): String? {
        val settings = outbound.getAsJsonObject("settings") ?: return null
        return settings.str("address")
            ?: settings.getAsJsonArray("vnext")?.firstOrNull()?.asJsonObject?.str("address")
            ?: settings.getAsJsonArray("servers")?.firstOrNull()?.asJsonObject?.str("address")
    }

    private fun tagOf(o: JsonObject): String? = o.str("tag")

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun strings(e: JsonElement?): List<String> = when {
        e == null -> emptyList()
        e.isJsonArray -> e.asJsonArray.mapNotNull { it.takeIf { x -> x.isJsonPrimitive }?.asString }
        e.isJsonPrimitive -> listOf(e.asString)
        else -> emptyList()
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private val random = SecureRandom()

    private fun hex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }
}

/**
 * Where the app leaves the CSL block for the VPN process: per profile, sealed
 * with the app's Keystore key, in a multi-process store (the VPN service runs
 * in its own process and also starts from the tile, the widget and Always-on).
 */
object ColituCslStore {
    private const val KEY_ENABLED = "csl_alpha"
    private const val SEAL_CONTEXT = "colitu-csl-v1"
    private val settings by lazy { MMKV.mmkvWithID("COLITU_SETTINGS", MMKV.MULTI_PROCESS_MODE) }
    private val store by lazy { MMKV.mmkvWithID("COLITU_CSL", MMKV.MULTI_PROCESS_MODE) }

    private const val KEY_AUTO_ALPHA = "auto_alpha_test"

    /**
     * Tester-only test switch: automatic mode may also pick alpha test nodes
     * (used to exercise automatic recovery on a node without users).
     */
    var autoAlpha: Boolean
        get() = runCatching { settings.decodeBool(KEY_AUTO_ALPHA, false) }.getOrDefault(false)
        set(value) {
            settings.encode(KEY_AUTO_ALPHA, value)
        }

    /** "Seamless session (alpha)" in Advanced mode; off by default. */
    var enabled: Boolean
        get() = runCatching { settings.decodeBool(KEY_ENABLED, false) }.getOrDefault(false)
        set(value) {
            settings.encode(KEY_ENABLED, value)
        }

    fun put(guid: String, csl: ColituCslConfig?) {
        runCatching {
            if (csl == null || !csl.usable) store.removeValueForKey(guid)
            else store.encode(guid, ColituSecureStore.sealConfig(csl.toJson(), SEAL_CONTEXT))
        }
    }

    fun get(guid: String): ColituCslConfig? = runCatching {
        val sealed = store.decodeString(guid) ?: return null
        ColituCslConfig.fromJson(JsonParser.parseString(ColituSecureStore.openConfig(sealed, SEAL_CONTEXT)))
    }.getOrNull()
}

/** The csl-client sidecar in the VPN process; one at a time. */
object ColituCslRuntime {
    private const val TAG = "ColituCsl"
    private const val FILE_NAME = "libcslclient.so"
    /** After a failure CSL stays off for this long (the core runs without it). */
    private const val RETRY_AFTER_MS = 10 * 60_000L

    @Volatile private var process: Process? = null
    private val generation = AtomicInteger()
    @Volatile private var offUntil = 0L

    fun binary(context: Context): File = File(context.applicationInfo.nativeLibraryDir, FILE_NAME)

    /** Why CSL is off for the next start, or null when it can run. */
    fun offReason(context: Context, csl: ColituCslConfig?): String? = when {
        !ColituCslStore.enabled -> "setting off"
        csl == null -> "this server does not offer it"
        !binary(context).exists() -> "csl-client missing"
        System.currentTimeMillis() < offUntil -> "failed a moment ago"
        else -> null
    }

    /**
     * Starts csl-client for [launch]. [onFailure] runs (once, on a background
     * thread) when it exits, or when it has no carrier within the deadline; it
     * is then stopped and CSL stays off for a while.
     */
    fun start(context: Context, launch: ColituCslLaunch, credential: String, onFailure: (String) -> Unit) {
        stop()
        val gen = generation.incrementAndGet()
        val started = System.currentTimeMillis()
        val proc = runCatching {
            ProcessBuilder(listOf(binary(context).absolutePath) + ColituCslRules.arguments(launch)).start()
        }.getOrElse {
            fail(gen, "csl-client could not start: ${it.javaClass.simpleName}", onFailure)
            return
        }
        process = proc
        runCatching {
            proc.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(ColituCslRules.secrets(launch, credential)) }
        }.onFailure {
            fail(gen, "csl-client did not take its secrets", onFailure)
            return
        }
        Log.w(TAG, "Colitu: CSL alpha: csl-client started (carriers ${launch.carriers.joinToString { "${it.label}/${it.family}" }})")
        var ready = false
        Thread({
            var lines = 0
            runCatching {
                proc.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    if (ColituCslRules.isReadyLine(line) && !ready) {
                        ready = true
                        Log.w(TAG, "Colitu: CSL alpha: on after ${System.currentTimeMillis() - started} ms")
                    }
                    // Carrier events name labels and loopback ports only; a few per run are enough.
                    if (++lines <= 40) Log.w(TAG, "Colitu: CSL alpha | ${line.substringAfter(' ').substringAfter(' ')}")
                }
            }
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            fail(gen, if (ready) "csl-client exited (code $code)" else "csl-client exited before a carrier came up (code $code)", onFailure)
        }, "colitu-csl-stderr").apply { isDaemon = true }.start()
        Thread({
            Thread.sleep(ColituCslRules.CARRIER_DEADLINE_MS)
            if (!ready) fail(gen, "no carrier within ${ColituCslRules.CARRIER_DEADLINE_MS / 1000} s", onFailure)
        }, "colitu-csl-deadline").apply { isDaemon = true }.start()
    }

    /** Stops csl-client (if any). Not reported as a failure. */
    fun stop() {
        generation.incrementAndGet()
        val proc = process
        process = null
        runCatching { proc?.destroy() }
    }

    val running: Boolean get() = process?.isRunning() == true

    private fun fail(gen: Int, reason: String, onFailure: (String) -> Unit) {
        if (!generation.compareAndSet(gen, gen + 1)) return
        offUntil = System.currentTimeMillis() + RETRY_AFTER_MS
        Log.w(TAG, "Colitu: CSL alpha: off ($reason)")
        val proc = process
        process = null
        runCatching { proc?.destroy() }
        onFailure(reason)
    }
}
