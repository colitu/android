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

    /**
     * The installation's device key. It must not change while the app stays
     * installed: the panel sees a new key as a new device, and on a
     * one-device plan the phone's own record then fills the limit
     * (DEVICE_LIMIT_REACHED). A stored key the secure store cannot read for
     * a moment (a busy or reset Keystore, seen on Xiaomi) is retried, then
     * taken from an app-private copy; only a phone with neither gets a new key.
     */
    @Synchronized
    fun getDeviceKey(): String {
        readDeviceKey()?.let { return it }
        if (ColituSecureStore.has(KEY_DEVICE_KEY)) {
            repeat(2) {
                Thread.sleep(150)
                readDeviceKey()?.let { return it }
            }
        }
        deviceKeyCopy()?.let { copy ->
            ColituSecureStore.put(KEY_DEVICE_KEY, copy)
            return copy
        }
        return java.util.UUID.randomUUID().toString().also {
            ColituSecureStore.put(KEY_DEVICE_KEY, it)
            saveDeviceKeyCopy(it)
        }
    }

    private fun readDeviceKey(): String? =
        ColituSecureStore.get(KEY_DEVICE_KEY)?.takeIf { it.isNotBlank() }?.also(::saveDeviceKeyCopy)

    // An identifier, not a credential (it signs nothing and the panel also
    // needs the account's tokens), so a plain copy in no-backup app storage
    // is fine; it is never restored onto another phone.
    private val deviceKeyFile get() = java.io.File(com.v2ray.ang.AngApplication.application.noBackupFilesDir, "colitu_device_key")

    private fun deviceKeyCopy(): String? = runCatching {
        deviceKeyFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.length in 20..256 }
    }.getOrNull()

    private fun saveDeviceKeyCopy(key: String) {
        runCatching {
            if (deviceKeyCopy() == key) return
            val tmp = java.io.File(deviceKeyFile.parentFile, deviceKeyFile.name + ".tmp")
            tmp.writeText(key)
            if (!tmp.renameTo(deviceKeyFile)) { deviceKeyFile.delete(); tmp.renameTo(deviceKeyFile) }
        }
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
            // The warm spare's node credentials belong to this account too.
            "vpn_lkg_spare",
            "vpn_recovery_set",
        )
        // device_key identifies the installation and intentionally survives logout.
    }
}
