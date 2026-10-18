package com.v2ray.ang.colitu.data

import android.content.Context
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Adaptive Connect decision engine (Go, colitu-csl repo engine/) as a
 * sidecar: `libcolituengine.so` in the APK's native library directory (an
 * executable, not a library: an app may only run files from there), one
 * JSON request per line on its stdin, one answer per line on its stdout. It
 * needs no network and keeps no state. It decides the automatic server order
 * ([decideOrder]); the app's own code (same rules, shared test vectors) is the
 * fallback whenever the engine is missing, slow, failing or answers with other
 * servers than the app's candidates. A missing, slow or crashed engine is left
 * alone for [RETRY_AFTER_MS].
 */
object ColituEngine {
    private const val TAG = "ColituEngine"
    private const val FILE_NAME = "libcolituengine.so"
    private const val ANSWER_TIMEOUT_MS = 2_000L
    private const val RETRY_AFTER_MS = 10 * 60_000L
    private const val MAX_LOGGED_MISMATCHES = 20
    /** How long a connect waits for the engine's order before it uses the app's own. */
    private const val DECISION_BUDGET_MS = 800L

    /** Calls a connect stops waiting for still finish here, so the line protocol stays in step. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lock = Mutex()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var nextId = 0L
    @Volatile private var disabledUntil = 0L
    private val mismatches = AtomicInteger()

    /** The engine's `out` for [op], or null when it is not available, failed or answered with an error. */
    suspend fun call(context: Context, op: String, input: JsonElement?): JsonElement? = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() < disabledUntil) return@withContext null
        lock.withLock {
            val running = ensureStarted(context) ?: return@withLock null
            val id = ++nextId
            val request = JsonObject().apply {
                addProperty("id", id)
                addProperty("op", op)
                add("in", input ?: JsonNull.INSTANCE)
            }
            runCatching {
                writer!!.apply { write(request.toString()); newLine(); flush() }
                // A blocked read only ends when the process goes: on a timeout it is destroyed.
                val line = coroutineScope {
                    val read = async(Dispatchers.IO) { reader!!.readLine() }
                    withTimeoutOrNull(ANSWER_TIMEOUT_MS) { read.await() }.also { if (it == null) running.destroy() }
                } ?: return@withLock fail("no answer to $op within ${ANSWER_TIMEOUT_MS / 1000} s")
                val answer = JsonParser.parseString(line).asJsonObject
                if (answer.get("id")?.asLong != id) return@withLock fail("answer out of step for $op")
                answer.get("error")?.takeIf { it.isJsonPrimitive }?.asString?.let {
                    Log.w(TAG, "$op: $it")
                    return@withLock null
                }
                answer.get("out")?.takeIf { !it.isJsonNull }
            }.getOrElse { fail("$op: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * Shadow comparison: logs (a few times per run at most) when the engine's
     * answer to [op] differs from [appDecision]. Never throws.
     */
    suspend fun compare(context: Context, op: String, input: JsonElement, appDecision: JsonElement?) {
        runCatching {
            val engine = call(context, op, input)
            if (engine == null && System.currentTimeMillis() < disabledUntil) return
            if ((engine ?: JsonNull.INSTANCE) == (appDecision ?: JsonNull.INSTANCE)) return
            if (mismatches.incrementAndGet() <= MAX_LOGGED_MISMATCHES) {
                Log.w(TAG, "Colitu: engine $op differs: app $appDecision, engine $engine")
            }
        }
    }

    /**
     * The engine's order of [app] for [op] (an array of ids), used only when it
     * holds exactly the app's candidates (same ids, none added, none dropped,
     * no repeats): the engine may reorder but never bring back a server the app
     * left out (Russia, penalised, hidden). Otherwise, or without an answer
     * within [DECISION_BUDGET_MS], the app's own order. The second value says
     * which one was used.
     */
    suspend fun <T> decideOrder(context: Context, op: String, input: JsonElement, app: List<T>, id: (T) -> String): Pair<List<T>, String> {
        if (app.size < 2) return app to "app"
        val pending = background.async { call(context.applicationContext, op, input) }
        val answer = withTimeoutOrNull(DECISION_BUDGET_MS) { pending.await() } ?: return app to "app (engine unavailable or slow)"
        if (!answer.isJsonArray) return app to "app (engine unavailable)"
        val byId = LinkedHashMap<String, T>()
        for (item in app) {
            if (byId.put(id(item), item) != null) return app to "app"
        }
        val order = ArrayList<T>(app.size)
        for (element in answer.asJsonArray) {
            val key = element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            val item = key?.let { byId.remove(it) } ?: return app to setDiffers(op, app.map(id), answer)
            order.add(item)
        }
        if (byId.isNotEmpty()) return app to setDiffers(op, app.map(id), answer)
        return order to "engine"
    }

    private fun setDiffers(op: String, app: List<String>, engine: JsonElement): String {
        if (mismatches.incrementAndGet() <= MAX_LOGGED_MISMATCHES) {
            Log.w(TAG, "Colitu: engine $op answered other servers than the app's candidates (app $app, engine $engine); app order used")
        }
        return "app (engine set differs)"
    }

    /** The `rank` request for the automatic order (engine.RankInput on the Go side). */
    fun rankRequest(
        servers: List<ColituServer>,
        pings: Map<String, ColituPing>,
        network: String,
        lastGood: String?,
        penaltyAt: Map<String, Long>,
        clientCountry: String?,
        now: Long,
    ): JsonObject {
        val list = JsonArray()
        val pingObject = JsonObject()
        val penalized = JsonArray()
        // Alpha test nodes never reach the engine unless the tester's test switch lets automatic mode use them.
        for (server in servers.filterNot { it.alpha && !ColituCslStore.autoAlpha }) {
            list.add(JsonObject().apply {
                addProperty("id", server.id)
                addProperty("country", server.countryCode)
                addProperty("available", server.isAvailable)
                addProperty("multihop", server.isMultihop)
            })
            pings[server.id]?.let { p ->
                pingObject.add(server.id, JsonObject().apply {
                    if (p.ms == null) add("ms", JsonNull.INSTANCE) else addProperty("ms", p.ms)
                    addProperty("at_ms", p.measuredAt)
                    addProperty("network", p.network)
                })
            }
            penaltyAt[server.id]?.let { at ->
                penalized.add(JsonObject().apply { addProperty("id", server.id); addProperty("at_ms", at) })
            }
        }
        return JsonObject().apply {
            addProperty("now_ms", now)
            addProperty("network", network)
            addProperty("client_country", clientCountry.orEmpty())
            addProperty("last_good", lastGood.orEmpty())
            add("servers", list)
            add("pings", pingObject)
            add("penalized", penalized)
        }
    }

    private fun ensureStarted(context: Context): Process? {
        process?.takeIf { it.isRunning() }?.let { return it }
        stop()
        val file = File(context.applicationInfo.nativeLibraryDir, FILE_NAME)
        if (!file.exists()) return null
        return runCatching {
            ProcessBuilder(file.absolutePath).redirectErrorStream(false).start().also {
                process = it
                writer = it.outputStream.bufferedWriter(Charsets.UTF_8)
                reader = it.inputStream.bufferedReader(Charsets.UTF_8)
                Log.w(TAG, "Colitu: engine started")
            }
        }.getOrElse {
            fail("start: ${it.javaClass.simpleName}")
            null
        }
    }

    private fun fail(reason: String): JsonElement? {
        Log.w(TAG, "Colitu: engine $reason; off for ${RETRY_AFTER_MS / 60_000} minutes")
        disabledUntil = System.currentTimeMillis() + RETRY_AFTER_MS
        stop()
        return null
    }

    private fun stop() {
        runCatching { process?.destroy() }
        process = null
        writer = null
        reader = null
    }
}

/**
 * Whether the process has not exited yet. Process.isAlive needs API 26 and the
 * app runs on 24 (Android 7.0); exitValue() throws while the process runs.
 */
internal fun Process.isRunning(): Boolean =
    try {
        exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }
