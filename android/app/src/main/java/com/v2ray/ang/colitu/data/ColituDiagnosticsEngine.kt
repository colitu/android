package com.v2ray.ang.colitu.data

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * The `engine` section of the silent support report (Adaptive Connect 4,
 * phase 4; schema `DiagnosticsEngine`): the engine journal's decisions (each
 * with the outcomes that followed it), ping round summaries, whitelist checks,
 * the servers hidden now, the model version and the feature flags. Bounded
 * like the panel keeps it ([MAX_DECISIONS] ... [MAX_TOTAL_BYTES]; an item over
 * [MAX_ITEM_BYTES] loses candidates, else it is left out; over the total the
 * oldest decisions go first). Every string value passes through [mask] (the
 * report's own masking), keys and numbers stay. Pure, so it is unit tested.
 */
object ColituDiagnosticsEngine {
    const val MAX_DECISIONS = 300
    const val MAX_PING_ROUNDS = 60
    const val MAX_HIDDEN = 200
    const val MAX_WHITELIST = 100
    const val MAX_FLAGS = 64
    const val MAX_ITEM_BYTES = 4 * 1024
    const val MAX_TOTAL_BYTES = 192 * 1024
    const val MAX_MODEL_VERSION = 64

    /**
     * [records]: the engine journal, oldest first. [hidden]: the servers hidden now
     * (`node_id`, `reason`, ...). Decisions go newest first, the other lists oldest first.
     */
    fun build(
        records: List<JsonObject>,
        hidden: List<JsonObject>,
        modelVersion: String?,
        flags: Map<String, Any?>,
        mask: (String) -> String,
    ): JsonObject {
        // Each decision carries the outcome records that followed it (until the next decision).
        val decisions = mutableListOf<JsonObject>()
        val pings = mutableListOf<JsonObject>()
        val whitelist = mutableListOf<JsonObject>()
        for (record in records) {
            when (record.get("kind")?.takeIf { it.isJsonPrimitive }?.asString) {
                "decision" -> decisions += record.deepCopy()
                "outcome" -> decisions.lastOrNull()?.let { decision ->
                    val outcomes = decision.getAsJsonArray("outcomes") ?: JsonArray().also { decision.add("outcomes", it) }
                    outcomes.add(record.deepCopy().apply { remove("kind") })
                }
                "ping" -> pings += record.deepCopy()
                "whitelist" -> whitelist += record.deepCopy()
            }
        }
        val decisionItems = decisions.takeLast(MAX_DECISIONS).mapNotNull { fit(masked(it, mask)) }.reversed().toMutableList()
        val pingItems = pings.takeLast(MAX_PING_ROUNDS).mapNotNull { fit(masked(it, mask)) }.toMutableList()
        val hiddenItems = hidden.take(MAX_HIDDEN).mapNotNull { fit(masked(it, mask)) }.toMutableList()
        val whitelistItems = whitelist.takeLast(MAX_WHITELIST).mapNotNull { fit(masked(it, mask)) }.toMutableList()

        val flagObject = JsonObject()
        flags.entries.take(MAX_FLAGS).forEach { (key, value) ->
            when (value) {
                null -> Unit
                is Boolean -> flagObject.addProperty(key, value)
                is Number -> flagObject.addProperty(key, value)
                else -> flagObject.addProperty(key, mask(value.toString()))
            }
        }

        fun assemble() = JsonObject().apply {
            modelVersion?.takeIf { it.isNotBlank() }?.let { addProperty("model_version", mask(it).take(MAX_MODEL_VERSION)) }
            add("flags", flagObject)
            add("decisions", JsonArray().apply { decisionItems.forEach(::add) })
            add("ping_rounds", JsonArray().apply { pingItems.forEach(::add) })
            add("hidden_servers", JsonArray().apply { hiddenItems.forEach(::add) })
            add("whitelist_checks", JsonArray().apply { whitelistItems.forEach(::add) })
        }

        // Over the total: the oldest decisions first (decisions are newest first), then the
        // oldest ping rounds, hidden servers and whitelist checks.
        var total = bytes(assemble())
        while (total > MAX_TOTAL_BYTES) {
            val list = listOf(decisionItems, pingItems, hiddenItems, whitelistItems).firstOrNull { it.isNotEmpty() } ?: break
            val dropped = if (list === decisionItems) list.removeAt(list.lastIndex) else list.removeAt(0)
            total -= bytes(dropped) + 1
        }
        // The estimate above ignores separators: confirm on the real text.
        while (bytes(assemble()) > MAX_TOTAL_BYTES) {
            val list = listOf(decisionItems, pingItems, hiddenItems, whitelistItems).firstOrNull { it.isNotEmpty() } ?: break
            if (list === decisionItems) list.removeAt(list.lastIndex) else list.removeAt(0)
        }
        return assemble()
    }

    fun bytes(element: JsonElement): Int = element.toString().toByteArray(Charsets.UTF_8).size

    /** [item] within [MAX_ITEM_BYTES]: its candidates trimmed from the end as needed; null when even that is too big. */
    fun fit(item: JsonObject): JsonObject? {
        if (bytes(item) <= MAX_ITEM_BYTES) return item
        val candidates = item.getAsJsonArray("candidates")
        if (candidates != null) {
            val trimmed = item.deepCopy()
            trimmed.addProperty("candidates_trimmed", true)
            val list = trimmed.getAsJsonArray("candidates")
            while (list.size() > 0 && bytes(trimmed) > MAX_ITEM_BYTES) list.remove(list.size() - 1)
            if (bytes(trimmed) <= MAX_ITEM_BYTES) return trimmed
        }
        return null
    }

    /** A copy of [element] with every string value passed through [mask]. */
    fun masked(element: JsonObject, mask: (String) -> String): JsonObject = maskElement(element, mask).asJsonObject

    private fun maskElement(element: JsonElement, mask: (String) -> String): JsonElement = when {
        element.isJsonObject -> JsonObject().also { out -> element.asJsonObject.entrySet().forEach { (k, v) -> out.add(k, maskElement(v, mask)) } }
        element.isJsonArray -> JsonArray().also { out -> element.asJsonArray.forEach { out.add(maskElement(it, mask)) } }
        element.isJsonPrimitive && element.asJsonPrimitive.isString -> JsonPrimitive(mask(element.asString))
        else -> element.deepCopy()
    }
}
