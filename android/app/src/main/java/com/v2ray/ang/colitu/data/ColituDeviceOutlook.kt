package com.v2ray.ang.colitu.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.time.Duration
import java.time.Instant

/**
 * What the panel says about the current plan's end and the device limit
 * after it (devicelimit.Outlook): when the entitlement ends, what follows
 * ("free" / "colitu-free" for the free plan) and that plan's device limit,
 * plus how many devices the account has. Every number shown comes from here,
 * none is assumed by the app.
 */
data class ColituDeviceOutlook(
    val endsAt: Instant?,
    val nextPlan: String?,
    val nextDeviceLimit: Int?,
    val registeredDevices: Int?,
    /** Monthly traffic of the next plan in bytes, when the panel sends it. */
    val nextTrafficBytes: Long?,
) {
    val nextIsFree: Boolean get() = nextPlan.equals("free", true) || nextPlan.equals("colitu-free", true)

    /** More devices than the next plan allows: the others will be paused. */
    val willPauseDevices: Boolean
        get() = nextDeviceLimit != null && registeredDevices != null && registeredDevices > nextDeviceLimit

    /** The trial-end banner applies: a trial moving to the free plan within [window]. */
    fun trialEndingSoon(status: String, now: Instant, window: Duration = Duration.ofDays(3)): Boolean {
        val ends = endsAt ?: return false
        if (!status.equals("trialing", true) && !status.equals("trial_active", true)) return false
        if (!nextIsFree || nextDeviceLimit == null) return false
        return ends.isAfter(now) && !ends.isAfter(now.plus(window))
    }

    companion object {
        /**
         * Reads the outlook from the bootstrap or entitlement answer. The
         * fields may sit at the top level, in "entitlement" or in a
         * "device_outlook"/"devices" object; the first place that has
         * ends_at or next_plan wins.
         */
        fun fromJson(root: JsonObject?): ColituDeviceOutlook? {
            root ?: return null
            // Contract: bootstrap "entitlement" and GET /me/entitlement carry
            // ends_at, next_plan, next_device_limit and devices{active,
            // suspended,registered,limit}.
            val candidates = listOfNotNull(
                root.obj("entitlement"),
                root.obj("device_outlook"),
                root,
            )
            val source = candidates.firstOrNull { it.has("next_plan") || it.has("ends_at") || it.has("next_device_limit") } ?: return null
            val ends = source.str("ends_at")?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val traffic = source.long("next_traffic_limit_bytes")
                ?: source.obj("next_traffic")?.long("limit_bytes")
                ?: source.long("next_monthly_traffic_bytes")
            val registered = source.obj("devices")?.int("registered")
                ?: source.int("registered_devices")
                ?: source.int("device_count")
                ?: (source.int("active_devices")?.let { it + (source.int("suspended_devices") ?: 0) })
            return ColituDeviceOutlook(ends, source.str("next_plan"), source.int("next_device_limit"), registered, traffic)
        }
    }
}

/**
 * 403 DEVICE_OVER_LIMIT: this device is paused because the plan allows fewer
 * devices. [activeDevices] are the ones that may connect.
 */
data class ColituDevicePause(val deviceLimit: Int?, val activeDevices: List<ActiveDevice>) {
    data class ActiveDevice(val id: String, val name: String, val lastSeenAt: Instant?)

    companion object {
        const val CODE = "DEVICE_OVER_LIMIT"

        fun fromJson(body: JsonObject?): ColituDevicePause {
            val devices = body?.get("active_devices")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
                ?.map { d ->
                    ActiveDevice(
                        id = d.str("id").orEmpty(),
                        name = d.str("name")?.takeIf { it.isNotBlank() } ?: "—",
                        lastSeenAt = d.str("last_seen_at")?.let { runCatching { Instant.parse(it) }.getOrNull() },
                    )
                }.orEmpty()
            return ColituDevicePause(body?.int("device_limit"), devices)
        }
    }
}

private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

private fun JsonObject.long(key: String): Long? =
    get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }

private fun JsonObject.int(key: String): Int? =
    get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
