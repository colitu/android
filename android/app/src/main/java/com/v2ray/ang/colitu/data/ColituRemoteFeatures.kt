package com.v2ray.ang.colitu.data

import com.google.gson.JsonObject
import com.tencent.mmkv.MMKV

/**
 * Rollout switches from the panel (`features` of GET /servers). The last value
 * is stored and kept while the panel cannot be reached, so the recovery set
 * still works when the API is blocked. Absent from an older panel: nothing
 * changes. Cleared on sign-out.
 */
object ColituRemoteFeatures {
    const val STORE_ID = "COLITU_SERVERS"
    const val KEY_AC3 = "feature_adaptive_connect_3"
    const val KEY_AC4 = "feature_ac_engine_4"
    const val KEY_TLS_EVENTS = "feature_tls_probe_events"

    @Volatile private var ac3Cache: Boolean? = null
    @Volatile private var ac4Cache: Boolean? = null
    @Volatile private var tlsEventsCache: Boolean? = null

    /** Adaptive Connect 3.0 is on for this device; false when never told (or without MMKV, e.g. JVM tests). */
    val adaptiveConnect3: Boolean
        get() = ac3Cache ?: runCatching { store().decodeBool(KEY_AC3, false) }.getOrDefault(false).also { ac3Cache = it }

    /** Adaptive Connect 4 (decision model, whitelist mode) is on for this device; false when never told. */
    val acEngine4: Boolean
        get() = ac4Cache ?: runCatching { store().decodeBool(KEY_AC4, false) }.getOrDefault(false).also { ac4Cache = it }

    /**
     * The panel accepts TLS probe events (`probe`/`port`/`measure` fields) in ac-events; an older panel rejects the
     * whole report on unknown fields. False when never told or absent from the last list.
     */
    val tlsProbeEvents: Boolean
        get() = tlsEventsCache ?: runCatching { store().decodeBool(KEY_TLS_EVENTS, false) }.getOrDefault(false).also { tlsEventsCache = it }

    /** The `features` object of a /servers answer: the switches it carries, null when absent. */
    fun parse(json: JsonObject?): Map<String, Boolean>? {
        if (json == null) return null
        return json.entrySet()
            .filter { (_, value) -> value.isJsonPrimitive && value.asJsonPrimitive.isBoolean }
            .associate { (key, value) -> key to value.asBoolean }
    }

    /** Stores what the panel said; keys it did not send keep their value. */
    fun update(features: Map<String, Boolean>?) {
        // Absence means false (a list without the flag comes from a panel that would reject the fields).
        val tls = features?.get("tls_probe_events") == true
        runCatching { store().encode(KEY_TLS_EVENTS, tls) }
        tlsEventsCache = tls
        features?.get("adaptive_connect_3")?.let { ac3 ->
            runCatching { store().encode(KEY_AC3, ac3) }
            ac3Cache = ac3
        }
        features?.get("ac_engine_4")?.let { ac4 ->
            runCatching { store().encode(KEY_AC4, ac4) }
            ac4Cache = ac4
        }
    }

    fun clear() {
        runCatching { store().removeValueForKey(KEY_AC3) }
        runCatching { store().removeValueForKey(KEY_AC4) }
        runCatching { store().removeValueForKey(KEY_TLS_EVENTS) }
        tlsEventsCache = false
        ac3Cache = false
        ac4Cache = false
    }

    private fun store(): MMKV = MMKV.mmkvWithID(STORE_ID, MMKV.MULTI_PROCESS_MODE)
}
