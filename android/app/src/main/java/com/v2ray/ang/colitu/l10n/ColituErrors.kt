package com.v2ray.ang.colitu.l10n

/**
 * Turns an API / repository error code into the user-facing sentence, with
 * the same wording as the iOS app (colitu_errors.dart).
 */
fun colituErrorMessage(code: String?, signingIn: Boolean = false): String {
    val loc = ColituLoc
    val raw = code.orEmpty().trim()
    return when (raw.uppercase()) {
        "LOGIN_FAILED", "INVALID_CREDENTIALS", "AUTH_INVALID_CREDENTIALS" -> loc["err.credentials"]
        "AUTH_EXPIRED", "UNAUTHORIZED", "401" ->
            if (signingIn) loc["err.credentials"] else loc["auth.expired"]
        "USER_EXISTS", "EMAIL_TAKEN", "DEVICE_ALREADY_USED", "REGISTRATION_FAILED" -> loc["err.registration"]
        "RATE_LIMITED", "TOO_MANY_REQUESTS", "429" -> loc["err.rateLimited"]
        "DEVICE_LIMIT_REACHED" -> loc["err.deviceLimit"]
        "REGION_NOT_SUPPORTED" -> loc["err.region"]
        "TRIAL_ALREADY_USED" -> loc["err.trialUsed"]
        "EMAIL_NOT_VERIFIED" -> loc["err.notVerified"]
        "VERIFICATION_CODE_INVALID" -> loc["verify.err.invalid"]
        "VERIFICATION_CODE_EXPIRED" -> loc["verify.err.expired"]
        "VERIFICATION_RATE_LIMITED" -> loc["verify.err.wait"]
        "EMAIL_DELIVERY_UNAVAILABLE" -> loc["verify.err.mail"]
        "SUPPORT_FILE_TOO_LARGE", "SUPPORT_FILE_TYPE" -> loc["support.err.file"]
        "SUPPORT_UNAVAILABLE" -> loc["support.err.unavailable"]
        "SUPPORT_CONVERSATION_CLOSED" -> loc["support.closed"]
        "SUPPORT_INVALID_INPUT" -> loc["support.err.subject"]
        "ENTITLEMENT_INACTIVE", "ENTITLEMENT_EXPIRED", "PREMIUM_REQUIRED", "SERVER_NOT_ALLOWED" -> loc["err.noPlan"]
        "QUOTA_EXCEEDED", "FREE_DAILY_LIMIT_REACHED" -> loc["err.quota"]
        "APP_UPDATE_REQUIRED" -> loc["err.updateRequired"]
        "MAINTENANCE_ACTIVE" -> loc["err.maintenance"]
        "NO_SERVERS", "NO_SERVER_SELECTED", "SERVER_NOT_FOUND" -> loc["err.noServers"]
        "NETWORK_ERROR", "TIMEOUT", "IO_ERROR", "SERVER_UNAVAILABLE", "SERVICE_UNAVAILABLE", "502", "503" -> loc["err.network"]
        "UNREACHABLE", "TUNNEL_TIMEOUT" -> loc["err.unreachable"]
        "VERIFY_FAILED" -> loc["err.verify"]
        "TUN_FAILED" -> loc["err.tun"]
        "VPN_PERMISSION_DENIED" -> loc["err.permission"]
        "CONFIG_NOT_READY", "CONFIG_CACHE_MISS", "PARSE_ERROR", "CONFIG_EXPIRED", "ENGINE_FAILED" -> loc["err.engine"]
        "INVALID_EMAIL" -> loc["auth.err.email"]
        "INVALID_PASSWORD", "PASSWORD_TOO_SHORT", "AUTH_INVALID_PASSWORD" -> loc["auth.err.password"]
        "LINK_NOT_FOUND", "LINK_EXPIRED", "LINK_DENIED" -> loc["link.invalid"]
        "BILLING_CHECKOUT_UNAVAILABLE", "BILLING_PAYMENT_METHOD_UNAVAILABLE" -> loc["pricing.unavailable"]
        else -> if (raw.startsWith("BILLING_")) loc["err.payment"] else loc["err.generic"]
    }
}
