package com.v2ray.ang.colitu.util

import android.content.Context
import androidx.annotation.StringRes
import com.v2ray.ang.R

/**
 * Semantic error codes produced by the network/repository layer.
 * Kept as a separate enum so the mapping logic is testable without an Android Context.
 */
enum class ColituErrorCode {
    SERVER_UNAVAILABLE,
    TIMEOUT,
    NETWORK_ERROR,
    AUTH_EXPIRED,
    PREMIUM_REQUIRED,
    NO_SERVER_SELECTED,
    DAILY_LIMIT_REACHED,
    CONFIG_NOT_READY,
    UPDATE_REQUIRED,
    ACCESS_DENIED,
    MAINTENANCE,
    GENERIC
}

object ColituErrorMapper {

    /**
     * Maps a raw error-code string (from ApiResult.Error or Result.failure message)
     * to a semantic [ColituErrorCode]. Pure Kotlin — no Android dependency, fully testable.
     *
     * Matching is case-insensitive and trims whitespace.
     */
    fun toErrorCode(raw: String): ColituErrorCode = when (raw.lowercase().trim()) {
        "app_update_required" -> ColituErrorCode.UPDATE_REQUIRED
        "entitlement_inactive", "entitlement_expired", "quota_exceeded" -> ColituErrorCode.ACCESS_DENIED
        "maintenance_active" -> ColituErrorCode.MAINTENANCE
        "server_unavailable", "service_unavailable", "502", "503" ->
            ColituErrorCode.SERVER_UNAVAILABLE
        "timeout" ->
            ColituErrorCode.TIMEOUT
        "network_error", "io_error" ->
            ColituErrorCode.NETWORK_ERROR
        "auth_expired", "unauthorized", "401" ->
            ColituErrorCode.AUTH_EXPIRED
        "premium_required", "server_not_allowed" ->
            ColituErrorCode.PREMIUM_REQUIRED
        "no_server_selected" ->
            ColituErrorCode.NO_SERVER_SELECTED
        "free_daily_limit_reached" ->
            ColituErrorCode.DAILY_LIMIT_REACHED
        "config_not_ready", "server_not_found", "config_unavailable" ->
            ColituErrorCode.CONFIG_NOT_READY
        "login_failed", "invalid_email", "invalid_password", "user_exists", "device_already_used", "device_limit_reached" ->
            ColituErrorCode.GENERIC
        else ->
            ColituErrorCode.GENERIC
    }

    @StringRes
    private fun ColituErrorCode.toStringRes(): Int = when (this) {
        ColituErrorCode.UPDATE_REQUIRED -> R.string.colitu_policy_update_required
        ColituErrorCode.ACCESS_DENIED -> R.string.colitu_policy_access_denied
        ColituErrorCode.MAINTENANCE -> R.string.colitu_policy_maintenance
        ColituErrorCode.SERVER_UNAVAILABLE  -> R.string.colitu_error_server_unavailable
        ColituErrorCode.TIMEOUT             -> R.string.colitu_error_timeout
        ColituErrorCode.NETWORK_ERROR       -> R.string.colitu_error_network
        ColituErrorCode.AUTH_EXPIRED        -> R.string.colitu_error_auth_expired
        ColituErrorCode.PREMIUM_REQUIRED    -> R.string.colitu_error_premium_required
        ColituErrorCode.NO_SERVER_SELECTED  -> R.string.colitu_error_no_server
        ColituErrorCode.DAILY_LIMIT_REACHED -> R.string.colitu_error_daily_limit
        ColituErrorCode.CONFIG_NOT_READY    -> R.string.colitu_error_config_not_ready
        ColituErrorCode.GENERIC             -> R.string.colitu_error_unknown_network
    }

    /** Preferred overload — resolves a localised string via [context]. */
    fun toUserMessage(context: Context, errorCode: String): String =
        context.getString(toErrorCode(errorCode).toStringRes())

    /**
     * Context-free overload for use where no [Context] is available (e.g. guard checks
     * before a coroutine launch, or unit tests).
     * Returns English fallback strings; prefer [toUserMessage(Context, String)] when possible.
     */
    fun toUserMessage(errorCode: String): String = when (toErrorCode(errorCode)) {
        ColituErrorCode.UPDATE_REQUIRED -> "Update Colitu before connecting."
        ColituErrorCode.ACCESS_DENIED -> "Your account cannot connect. Check your subscription and usage."
        ColituErrorCode.MAINTENANCE -> "Service maintenance is active. Please try again later."
        ColituErrorCode.SERVER_UNAVAILABLE  -> "Server is temporarily unavailable. Please try again later."
        ColituErrorCode.TIMEOUT             -> "Connection timed out. Please check your internet and try again."
        ColituErrorCode.AUTH_EXPIRED        -> "Your session expired. Please sign in again."
        ColituErrorCode.PREMIUM_REQUIRED    -> "Premium plan required for this location."
        ColituErrorCode.NO_SERVER_SELECTED  -> "Please choose a location first."
        ColituErrorCode.DAILY_LIMIT_REACHED -> "Your traffic quota has been exhausted. Upgrade or wait for the next billing period."
        ColituErrorCode.CONFIG_NOT_READY    -> "Connection setup failed. Please try again."
        ColituErrorCode.NETWORK_ERROR,
        ColituErrorCode.GENERIC             -> "Something went wrong with the network. Please try again."
    }
}
