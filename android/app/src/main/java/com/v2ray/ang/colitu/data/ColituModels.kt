package com.v2ray.ang.colitu.data

import com.google.gson.JsonObject

data class ColituUser(
    val id: String = "",
    val name: String = "",
    val email: String = "",
    val phone: String? = null,
    val plan: String = "inactive",
    val entitlementStatus: String = "inactive",
    val deviceLimit: Int = 1,
    /** Entitlement end (ISO-8601) from /me/entitlement or the bootstrap. */
    val expiresAt: String? = null,
    val trafficUsedBytes: Long = 0,
    /** Null when the plan has no traffic quota. */
    val trafficLimitBytes: Long? = null,
) {
    val hasActiveEntitlement: Boolean
        get() = entitlementStatus.equals("active", true) || entitlementStatus.equals("trialing", true)
}

data class ColituServer(
    val id: String,
    val displayName: String,
    val countryCode: String?,
    val city: String?,
    val isRecommended: Boolean,
    val isAvailable: Boolean,
    val load: String? = null,
    val protocols: List<String> = emptyList(),
    val region: String? = null,
    /** Third-party services the node opens (checked by the panel from the node's IP). */
    val services: List<String> = emptyList(),
    /** Use-case categories from the panel: streaming, gaming, privacy, speed, torrent, ai. */
    val categories: List<String> = emptyList(),
    /** Where the app measures this location's ping (TCP connect time). */
    val latencyHost: String? = null,
    val latencyPort: Int? = null,
) {
    val flagEmoji: String get() = countryCodeToFlag(countryCode)
    /** "AI" means the ones users ask for most: Gemini and ChatGPT both open. */
    val opensAi: Boolean get() = services.containsAll(REQUIRED_AI_SERVICES)
    val opensStreaming: Boolean get() = services.any { it in STREAMING_SERVICES }
    /** The node runs one of Colitu's ad-blocking DNS servers (matched by its probe host). */
    val hostsAdBlockDns: Boolean get() = latencyHost?.lowercase() in ColituAdBlock.dnsHosts

    companion object {
        fun fromJson(json: JsonObject): ColituServer? {
            return try {
                // The panel only lists healthy nodes, but city, region and
                // load may be empty for a freshly added one (the live Estonia
                // node has no region). Only the id and a usable transport are
                // required, like on iOS.
                val id = json.getString("id") ?: return null
                val country = json.getString("country")
                val displayName = json.getString("name") ?: country ?: return null
                val status = json.getString("status")
                if (status != null && !status.equals("online", true)) return null
                val load = json.getString("load")?.lowercase()?.takeIf { it in setOf("low", "medium", "high") }
                val offered = json.getAsJsonArray("protocols")
                    ?.mapNotNull { value -> value.takeIf { it.isJsonPrimitive }?.asString }
                    .orEmpty()
                val protocols = offered.filter { it in XrayMobileAdapter.supportedProtocols }
                if (offered.isNotEmpty() && protocols.isEmpty()) return null

                ColituServer(
                    id = id,
                    displayName = displayName,
                    countryCode = country?.uppercase()?.takeIf { it.length == 2 },
                    city = json.getString("city"),
                    isRecommended = false,
                    isAvailable = true,
                    load = load,
                    protocols = protocols,
                    region = json.getString("region"),
                    services = json.getAsJsonArray("services")
                        ?.mapNotNull { value -> value.takeIf { it.isJsonPrimitive }?.asString }
                        .orEmpty(),
                    categories = json.getAsJsonArray("categories")
                        ?.mapNotNull { value -> value.takeIf { it.isJsonPrimitive }?.asString?.lowercase() }
                        ?.distinct()
                        .orEmpty(),
                    latencyHost = json.getString("latency_host"),
                    latencyPort = json.getString("latency_port")?.toIntOrNull(),
                )
            } catch (e: Exception) {
                null
            }
        }

        // Returns null for non-primitive JSON values (objects, arrays) — safe against crash.
        private fun JsonObject.getString(key: String): String? =
            if (has(key) && !get(key).isJsonNull && get(key).isJsonPrimitive)
                get(key).asString.takeIf { it.isNotBlank() }
            else null

        val REQUIRED_AI_SERVICES = listOf("gemini", "chatgpt")
        val STREAMING_SERVICES = listOf("netflix", "youtube_premium")

        /** Display names, in the order they are shown on a server row. */
        val SERVICE_NAMES = linkedMapOf(
            "chatgpt" to "ChatGPT",
            "gemini" to "Gemini",
            "claude" to "Claude",
            "netflix" to "Netflix",
            "youtube_premium" to "YouTube Premium",
        )

        fun countryCodeToFlag(code: String?): String {
            if (code == null || code.length != 2) return "🌐"
            return code.uppercase().map { it.code - 0x41 + 0x1F1E6 }
                .joinToString("") { String(Character.toChars(it)) }
        }
    }
}

data class ColituServerListResponse(
    val servers: List<ColituServer>,
) {
    companion object {
        /**
         * Parses the canonical /api/v1/servers response. The control plane has
         * already applied entitlement and node-health eligibility.
         */
        fun fromJson(json: JsonObject): ColituServerListResponse {
            val serversArray = if (json.has("servers") && json.get("servers").isJsonArray)
                json.getAsJsonArray("servers")
            else
                return ColituServerListResponse(emptyList())

            val servers = serversArray.mapNotNull { elem ->
                if (elem.isJsonObject) ColituServer.fromJson(elem.asJsonObject) else null
            }.mapIndexed { index, server -> server.copy(isRecommended = index == 0) }

            return ColituServerListResponse(servers)
        }
    }
}

data class ColituVpnConfig(
    val serverId: String,
    /** ISO country of the server ("RU"), when the panel sends it. */
    val serverCountry: String? = null,
    val configType: String,
    val protocolType: String?,
    val rawConfig: String?,
    val expiresAt: String?,
    val unlimited: Boolean,
    val revision: Long = 0,
    val offlineGraceUntil: String? = null
) {
    companion object {
        /** Accept only the versioned profile returned by the Colitu config API. */
        fun fromJson(json: JsonObject): ColituVpnConfig? =
            runCatching { XrayMobileAdapter.render(json) }.getOrNull()
    }
}
data class ColituAuthRequest(
    val email: String,
    val password: String
)

data class ColituAuthResponse(
    val accessToken: String?,
    val refreshToken: String?,
    val tokenType: String?,
    val expiresIn: Long?,
) {
    companion object {
        fun fromJson(json: JsonObject): ColituAuthResponse {
            val access = json.getString("access_token")
            val refresh = json.getString("refresh_token")
            val type = json.getString("token_type")
            val expires = if (json.has("expires_in") && json.get("expires_in").isJsonPrimitive) {
                runCatching { json.get("expires_in").asLong }.getOrNull()
            } else null
            return if (access != null && refresh != null && type == "Bearer" && expires != null && expires > 0) {
                ColituAuthResponse(access, refresh, type, expires)
            } else {
                ColituAuthResponse(null, null, null, null)
            }
        }

        private fun JsonObject.getString(key: String): String? =
            if (has(key) && !get(key).isJsonNull && get(key).isJsonPrimitive)
                get(key).asString.takeIf { it.isNotBlank() }
            else null
    }
}

data class ColituDevice(
    val id: String,
    val name: String,
    val current: Boolean,
    val lastActiveAt: String?,
    val platform: String? = null,
) {
    companion object {
        fun fromJson(json: JsonObject, currentDeviceId: String? = null): ColituDevice? {
            val id = json.tryString("id") ?: return null
            val name = json.tryString("name") ?: return null
            return ColituDevice(
                id = id,
                name = name,
                current = id == currentDeviceId,
                lastActiveAt = json.tryString("last_seen_at"),
                platform = json.tryString("platform"),
            )
        }
    }
}

data class ColituSubscription(
    val active: Boolean,
    val planName: String,
    val productId: String?,
    val status: String?,
    val expiresAt: String?
) {
    companion object {
        fun fromJson(json: JsonObject): ColituSubscription {
            val root = json.unwrapObject("data") ?: json
            val plan = root.unwrapObject("plan")
                ?: throw IllegalArgumentException("BILLING_SUBSCRIPTION_INVALID")
            val planName = plan.tryString("name")
                ?: throw IllegalArgumentException("BILLING_SUBSCRIPTION_INVALID")
            val productId = plan.tryString("id")
                ?: throw IllegalArgumentException("BILLING_SUBSCRIPTION_INVALID")
            val status = root.tryString("status")
                ?: throw IllegalArgumentException("BILLING_SUBSCRIPTION_INVALID")
            val expiresAt = root.tryString("current_period_end")
                ?: throw IllegalArgumentException("BILLING_SUBSCRIPTION_INVALID")
            val active = status.equals("active", true) || status.equals("trialing", true)
            return ColituSubscription(
                active = active,
                planName = planName,
                productId = productId,
                status = status,
                expiresAt = expiresAt
            )
        }
    }
}

private fun JsonObject.unwrapObject(key: String): JsonObject? =
    if (has(key) && !get(key).isJsonNull && get(key).isJsonObject) getAsJsonObject(key) else null

private fun JsonObject.tryString(key: String): String? =
    if (has(key) && !get(key).isJsonNull && get(key).isJsonPrimitive)
        runCatching { get(key).asString.takeIf { it.isNotBlank() } }.getOrNull()
    else null

private fun JsonObject.tryBool(key: String): Boolean? =
    if (has(key) && !get(key).isJsonNull && get(key).isJsonPrimitive)
        runCatching { get(key).asBoolean }.getOrNull()
    else null

private fun JsonObject.tryInt(key: String): Int? =
    if (has(key) && !get(key).isJsonNull && get(key).isJsonPrimitive)
        runCatching { get(key).asInt }.getOrNull()
    else null

private fun JsonObject.tryDouble(key: String): Double? =
    if (has(key) && !get(key).isJsonNull && get(key).isJsonPrimitive)
        runCatching { get(key).asDouble }.getOrNull()
    else null

private fun JsonObject.tryLong(key: String): Long? =
    if (has(key) && !get(key).isJsonNull && get(key).isJsonPrimitive) runCatching { get(key).asLong }.getOrNull() else null
