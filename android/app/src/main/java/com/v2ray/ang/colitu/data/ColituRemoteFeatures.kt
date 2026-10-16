package com.v2ray.ang.colitu.data

import com.google.gson.JsonObject
import com.tencent.mmkv.MMKV

/**
 * Rollout switches from the panel (`features` of GET /servers). The last value
 * is stored and kept while the panel cannot be reached, so the recovery set
 * still works when the API is blocked. Absent from an older panel: nothing
 * changes. Cleared on sign-out ([KEY_AC3] is in the sign-out key list).
 */
object ColituRemoteFeatures {
    const val STORE_ID = "COLITU_SERVERS"
    const val KEY_AC3 = "feature_adaptive_connect_3"

    @Volatile private var ac3Cache: Boolean? = null

    /** Adaptive Connect 3.0 is on for this device; false when never told (or without MMKV, e.g. JVM tests). */
    val adaptiveConnect3: Boolean
        get() = ac3Cache ?: runCatching { store().decodeBool(KEY_AC3, false) }.getOrDefault(false).also { ac3Cache = it }

    /** The `features` object of a /servers answer: the switches it carries, null when absent. */
    fun parse(json: JsonObject?): Map<String, Boolean>? {
        if (json == null) return null
        return json.entrySet()
            .filter { (_, value) -> value.isJsonPrimitive && value.asJsonPrimitive.isBoolean }
            .associate { (key, value) -> key to value.asBoolean }
    }

    /** Stores what the panel said; keys it did not send keep their value. */
    fun update(features: Map<String, Boolean>?) {
        val ac3 = features?.get("adaptive_connect_3") ?: return
        runCatching { store().encode(KEY_AC3, ac3) }
        ac3Cache = ac3
    }

    fun clear() {
        runCatching { store().removeValueForKey(KEY_AC3) }
        ac3Cache = false
    }

    private fun store(): MMKV = MMKV.mmkvWithID(STORE_ID, MMKV.MULTI_PROCESS_MODE)
}
