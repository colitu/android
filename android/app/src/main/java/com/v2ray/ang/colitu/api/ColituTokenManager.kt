package com.v2ray.ang.colitu.api

object ColituTokenManager {
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_ENTITLEMENT_STATUS = "entitlement_status"
    private const val KEY_PLAN = "entitlement_plan"
    private const val KEY_DEPRECATED_ENTITLEMENT_FLAG = "is_pro"
    private const val KEY_DEVICE_KEY = "device_key"
    private const val KEY_DEVICE_ID = "backend_device_id"
    private const val KEY_PENDING_EMAIL = "pending_verification_email"

    /** COLITU_SETTINGS keys that belong to the signed-in account. */
    private val ACCOUNT_SETTINGS = setOf(
        "synced_server", "stalled_transport", "last_transport", "connected_server", "auto_connect", "auto_selection",
    )

    fun getAccessToken(): String? = ColituSecureStore.get(KEY_ACCESS_TOKEN)
    fun getRefreshToken(): String? = ColituSecureStore.get(KEY_REFRESH_TOKEN)
    fun getUserId(): String? = ColituSecureStore.get(KEY_USER_ID)
    fun getUserEmail(): String? = ColituSecureStore.get(KEY_USER_EMAIL)
    fun getUserName(): String? = ColituSecureStore.get(KEY_USER_NAME)
    fun getEntitlementStatus(): String =
        ColituSecureStore.get(KEY_ENTITLEMENT_STATUS) ?: "inactive"
    fun getPlan(): String = ColituSecureStore.get(KEY_PLAN) ?: "inactive"
    fun isLoggedIn(): Boolean = !getAccessToken().isNullOrBlank()

    fun saveTokens(accessToken: String, refreshToken: String?) {
        ColituSecureStore.put(KEY_ACCESS_TOKEN, accessToken)
        if (!refreshToken.isNullOrBlank()) {
            ColituSecureStore.put(KEY_REFRESH_TOKEN, refreshToken)
        }
    }

    /** Bumped by [clear]; a token refresh that started before a sign-out must not save its tokens. */
    @Volatile private var generation = 0L
    fun sessionGeneration(): Long = generation

    /** Saves refreshed tokens unless the session was cleared since [startedAt] was read. */
    @Synchronized
    fun saveRefreshedTokens(startedAt: Long, accessToken: String, refreshToken: String?): Boolean {
        if (startedAt != generation) return false
        saveTokens(accessToken, refreshToken)
        return true
    }

    fun saveUserInfo(id: String, email: String, name: String, plan: String, entitlementStatus: String) {
        ColituSecureStore.put(KEY_USER_ID, id)
        ColituSecureStore.put(KEY_USER_EMAIL, email)
        ColituSecureStore.put(KEY_USER_NAME, name)
        ColituSecureStore.put(KEY_PLAN, plan)
        ColituSecureStore.put(KEY_ENTITLEMENT_STATUS, entitlementStatus)
        ColituSecureStore.remove(KEY_DEPRECATED_ENTITLEMENT_FLAG)
    }

    fun saveEntitlementStatus(status: String) {
        ColituSecureStore.put(KEY_ENTITLEMENT_STATUS, status)
        ColituSecureStore.remove(KEY_DEPRECATED_ENTITLEMENT_FLAG)
    }

    fun getDeviceKey(): String {
        ColituSecureStore.get(KEY_DEVICE_KEY)?.takeIf { it.isNotBlank() }?.let { return it }
        return java.util.UUID.randomUUID().toString().also { ColituSecureStore.put(KEY_DEVICE_KEY, it) }
    }
    fun getDeviceId(): String? = ColituSecureStore.get(KEY_DEVICE_ID)
    fun saveDeviceId(deviceId: String) = ColituSecureStore.put(KEY_DEVICE_ID, deviceId)

    /** Set while the signed-in account still has to confirm this e-mail address. */
    fun getPendingVerificationEmail(): String? = ColituSecureStore.get(KEY_PENDING_EMAIL)?.takeIf { it.isNotBlank() }
    fun setPendingVerificationEmail(email: String?) {
        if (email.isNullOrBlank()) ColituSecureStore.remove(KEY_PENDING_EMAIL) else ColituSecureStore.put(KEY_PENDING_EMAIL, email)
    }

    /**
     * Ends the session on this device: stops the tunnel, forgets the tokens,
     * the Colitu profile and everything that belonged to this account, so
     * the next account starts clean (no synced server, remembered transports
     * or auto-connect of the previous one, no quick start from the tile).
     */
    @Synchronized
    fun clear() {
        generation++
        com.v2ray.ang.core.CoreServiceManager.stopVService(com.v2ray.ang.AngApplication.application)
        com.v2ray.ang.colitu.app.ColituQuickStart.revoke()
        val settings = com.tencent.mmkv.MMKV.mmkvWithID("COLITU_SETTINGS", com.tencent.mmkv.MMKV.MULTI_PROCESS_MODE)
        settings.allKeys()?.filter { key -> ACCOUNT_SETTINGS.any { key == it || key.startsWith("good_transport_") } }
            ?.let { settings.removeValuesForKeys(it.toTypedArray()) }
        com.v2ray.ang.handler.MmkvManager.decodeSubscriptions()
            .filter { it.subscription.remarks == "Colitu" }
            .forEach { com.v2ray.ang.handler.MmkvManager.removeSubscription(it.guid) }
        com.tencent.mmkv.MMKV.mmkvWithID("COLITU_SERVERS", com.tencent.mmkv.MMKV.MULTI_PROCESS_MODE)
            .removeValuesForKeys(arrayOf("config_etag", "selected_server_id", "auto_connect_server_id", "recovery_last_attempt"))
        ColituSecureStore.remove(
            KEY_ACCESS_TOKEN,
            KEY_REFRESH_TOKEN,
            KEY_USER_ID,
            KEY_USER_EMAIL,
            KEY_USER_NAME,
            KEY_ENTITLEMENT_STATUS,
            KEY_PLAN,
            KEY_DEPRECATED_ENTITLEMENT_FLAG,
            KEY_DEVICE_ID,
            KEY_PENDING_EMAIL,
            "vpn_lkg_envelope",
            "vpn_lkg_route",
            "vpn_recovery_set",
        )
        // device_key identifies the installation and intentionally survives logout.
    }
}
