package com.v2ray.ang.colitu.data

import com.google.gson.JsonObject

data class ClientBootstrapPolicy(
    val denial: String?,
    val recommendedVersion: String?,
    val entitlementStatus: String,
) {
    companion object {
        fun fromJson(root: JsonObject): ClientBootstrapPolicy {
            val policy = requireNotNull(root.getAsJsonObject("app_policy"))
            val entitlement = requireNotNull(root.getAsJsonObject("entitlement"))
            val maintenance = root.getAsJsonObject("maintenance")
            val updateRequired = requireNotNull(policy.get("update_required")).asBoolean
            val status = requireNotNull(entitlement.get("status")).asString
            val denial = when {
                updateRequired -> "APP_UPDATE_REQUIRED"
                maintenance?.get("active")?.asBoolean == true -> "MAINTENANCE_ACTIVE"
                status !in setOf("active", "trialing") -> "ENTITLEMENT_INACTIVE"
                else -> null
            }
            val recommended = if (policy.get("update_recommended")?.asBoolean == true)
                policy.get("recommended_version")?.asString else null
            return ClientBootstrapPolicy(denial, recommended, status)
        }
    }
}
