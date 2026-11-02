package com.v2ray.ang.colitu.data

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tencent.mmkv.MMKV
import com.v2ray.ang.colitu.api.ColituApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * This device's connection outcomes per (network, server, protocol) for
 * [ColituLm]: the Beta posterior's evidence and the "own" feature. The
 * network is a hash of the app's network key (link type + ISP key), so the
 * store names no network; it never leaves the device. Outcomes older than
 * [MAX_AGE_MS] (≈ 1/1000 weight at a 72 h half-life) are dropped, each key
 * keeps its last [MAX_PER_KEY], the whole store at most [MAX_KEYS] keys
 * (least recently used go first). Pure, so it is unit tested.
 */
class ColituLmHistory private constructor(private val entries: MutableMap<String, MutableList<LongArray>>) {
    /** Records one outcome at [now] (wall-clock ms). */
    fun record(network: String, serverId: String, protocol: String, ok: Boolean, now: Long) {
        if (serverId.isBlank()) return
        val list = entries.getOrPut(key(network, serverId, protocol)) { mutableListOf() }
        list += longArrayOf(now, if (ok) 1L else 0L)
        while (list.size > MAX_PER_KEY) list.removeAt(0)
        prune(now)
    }

    /** The outcomes of [serverId] + [protocol] on [network], with their age in hours at [now]. */
    fun outcomes(network: String, serverId: String, protocol: String, now: Long): List<ColituLm.Outcome> =
        entries[key(network, serverId, protocol)].orEmpty()
            .filter { now - it[0] in 0 until MAX_AGE_MS }
            .map { ColituLm.Outcome((now - it[0]) / 3_600_000.0, it[1] == 1L) }

    fun prune(now: Long) {
        entries.values.forEach { list -> list.removeAll { now - it[0] !in 0 until MAX_AGE_MS } }
        entries.entries.removeAll { it.value.isEmpty() }
        if (entries.size > MAX_KEYS) {
            entries.entries.sortedBy { e -> e.value.maxOf { it[0] } }.take(entries.size - MAX_KEYS).map { it.key }.forEach { entries.remove(it) }
        }
    }

    val size: Int get() = entries.size

    fun toJson(): String = JsonObject().apply {
        entries.forEach { (key, list) ->
            add(key, JsonArray().apply { list.forEach { add(JsonArray().apply { add(it[0]); add(it[1]) }) } })
        }
    }.toString()

    companion object {
        const val MAX_AGE_MS = 30L * 24 * 3_600_000
        const val MAX_PER_KEY = 16
        const val MAX_KEYS = 400

        /** First 16 hex digits of SHA-256 of the network key: stable per network, names nothing. */
        fun networkHash(network: String): String =
            MessageDigest.getInstance("SHA-256").digest(network.toByteArray(Charsets.UTF_8))
                .take(8).joinToString("") { "%02x".format(it) }

        private fun key(network: String, serverId: String, protocol: String) =
            "${networkHash(network)}|${serverId.lowercase()}|$protocol"

        fun empty() = ColituLmHistory(mutableMapOf())

        fun fromJson(raw: String?, now: Long): ColituLmHistory {
            val map = mutableMapOf<String, MutableList<LongArray>>()
            runCatching {
                if (!raw.isNullOrBlank()) {
                    JsonParser.parseString(raw).asJsonObject.entrySet().forEach { (key, value) ->
                        val list = value.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e ->
                            val pair = e.takeIf { it.isJsonArray }?.asJsonArray?.takeIf { it.size() == 2 } ?: return@mapNotNull null
                            longArrayOf(pair[0].asLong, pair[1].asLong)
                        }?.toMutableList() ?: return@forEach
                        map[key] = list
                    }
                }
            }
            return ColituLmHistory(map).also { it.prune(now) }
        }
    }
}

/**
 * GET /client/ac-model: fetched while `features.ac_engine_4` is on and
 * refetched after [REFRESH_AFTER_MS] (at most every [RETRY_MS]); offline the
 * last model stays in use (the decision calls it stale after
 * [ColituLm.STALE_AFTER_H]). No model: the Adaptive Connect 3 rules. With the
 * VPN on the request carries the `network_token` of the last /servers with
 * the VPN off, so the panel knows the network's country and city. Also keeps
 * the device history ([ColituLmHistory]).
 */
object ColituAcModel {
    private const val TAG = "ColituAcModel"
    private const val STORE_ID = "COLITU_LM"
    private const val KEY_MODEL = "model"
    private const val KEY_FETCHED_AT = "model_fetched_at"
    private const val KEY_ATTEMPT_AT = "model_attempt_at"
    private const val KEY_HISTORY = "history"
    const val FETCH_PATH = "/client/ac-model"
    const val RETRY_MS = 15 * 60_000L
    const val REFRESH_AFTER_MS = 60 * 60_000L
    /** Longest token sent (the panel accepts up to 256 characters). */
    const val MAX_TOKEN_LENGTH = 256

    private val store by lazy { MMKV.mmkvWithID(STORE_ID, MMKV.MULTI_PROCESS_MODE) }
    private val fetchLock = Mutex()

    @Volatile private var cached: Pair<ColituLm.Model, Long>? = null
    @Volatile private var historyCache: ColituLmHistory? = null

    /** The stored model and when it was fetched (wall-clock ms); null when none. */
    fun current(): Pair<ColituLm.Model, Long>? {
        cached?.let { return it }
        return runCatching {
            val raw = store.decodeString(KEY_MODEL) ?: return null
            val model = ColituLm.modelFromJson(JsonParser.parseString(raw).asJsonObject) ?: return null
            (model to store.decodeLong(KEY_FETCHED_AT, 0L)).also { cached = it }
        }.getOrNull()
    }

    /** A refetch is due: none stored or older than [REFRESH_AFTER_MS], and no attempt in the last [RETRY_MS]. */
    fun fetchDue(fetchedAt: Long?, attemptAt: Long, now: Long): Boolean {
        val fresh = fetchedAt != null && now - fetchedAt in 0 until REFRESH_AFTER_MS
        return !fresh && now - attemptAt !in 0 until RETRY_MS
    }

    fun path(networkToken: String?): String {
        val token = networkToken?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_TOKEN_LENGTH } ?: return FETCH_PATH
        return "$FETCH_PATH?network_token=" + URLEncoder.encode(token, "UTF-8")
    }

    /** Fetches the model when due; the caller never waits for it. True when a new one was stored. */
    suspend fun refreshIfDue(networkToken: String?): Boolean = withContext(Dispatchers.IO) {
        if (!fetchLock.tryLock()) return@withContext false
        try {
            val now = System.currentTimeMillis()
            if (!fetchDue(current()?.second, runCatching { store.decodeLong(KEY_ATTEMPT_AT, 0L) }.getOrDefault(0L), now)) return@withContext false
            store.encode(KEY_ATTEMPT_AT, now)
            when (val result = ColituApiClient.get(path(networkToken))) {
                is ColituApiClient.ApiResult.Success -> {
                    val model = ColituLm.modelFromJson(result.data)
                    if (model == null) {
                        Log.w(TAG, "ac-model refused (not a model)")
                        return@withContext false
                    }
                    store.encode(KEY_MODEL, result.data.toString())
                    store.encode(KEY_FETCHED_AT, now)
                    cached = model to now
                    Log.i(TAG, "Colitu: ac-model v${model.version} ${model.country} stored (${model.priors.values.sumOf { it.size }} nodes with priors)")
                    true
                }
                // Offline or refused: the stored (stale) model stays in use.
                is ColituApiClient.ApiResult.Error -> false
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "ac-model refresh failed: ${e.javaClass.simpleName}")
            false
        } finally {
            fetchLock.unlock()
        }
    }

    fun history(): ColituLmHistory =
        historyCache ?: ColituLmHistory.fromJson(runCatching { store.decodeString(KEY_HISTORY) }.getOrNull(), System.currentTimeMillis())
            .also { historyCache = it }

    @Synchronized
    fun recordOutcome(network: String, serverId: String, protocol: String, ok: Boolean) {
        val h = history()
        h.record(network, serverId, protocol, ok, System.currentTimeMillis())
        runCatching { store.encode(KEY_HISTORY, h.toJson()) }
    }
}
