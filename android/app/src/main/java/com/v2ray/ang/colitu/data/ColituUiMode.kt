package com.v2ray.ang.colitu.data

import com.tencent.mmkv.MMKV

/**
 * Simple mode / Advanced mode. Simple shows the connect button, the location,
 * the plan and the account; Advanced adds split tunneling, privacy mode, ad
 * blocking, the warm spare, multihop and exit rotation. Hiding is not
 * disabling: what was set keeps working, except that a multihop route is not
 * a connect target in Simple mode (the automatic choice is) and the warm
 * spare is always on there.
 */
object ColituUiMode {
    private const val KEY_ADVANCED = "ui_advanced_mode"

    /**
     * Keys only an earlier version writes once someone signed in (device
     * capabilities, the server list's pings, the synced or connected node).
     */
    private val priorUseKeys = listOf("device_caps_version", "server_pings", "synced_server", "connected_server", "auto_selection")

    // Same store as ColituController; the VPN service reads it in its own process.
    private val store by lazy { MMKV.mmkvWithID("COLITU_SETTINGS", MMKV.MULTI_PROCESS_MODE) }

    /**
     * The stored mode; on the first run of this version it is decided once
     * (see [initialAdvanced]) and stored. Called at app start, before a
     * sign-in can make a new install look like an old one.
     */
    fun resolve(signedIn: Boolean): Boolean = runCatching {
        if (store.containsKey(KEY_ADVANCED)) return@runCatching store.decodeBool(KEY_ADVANCED, true)
        val advanced = initialAdvanced(stored = null, signedIn = signedIn, priorUse = priorUseKeys.any { store.containsKey(it) })
        store.encode(KEY_ADVANCED, advanced)
        advanced
    }.getOrDefault(true)

    var advanced: Boolean
        get() = runCatching { store.decodeBool(KEY_ADVANCED, true) }.getOrDefault(true)
        set(value) {
            store.encode(KEY_ADVANCED, value)
        }

    /**
     * New install (nothing stored, never signed in): Simple. Someone updating
     * keeps what they saw so far: Advanced.
     */
    fun initialAdvanced(stored: Boolean?, signedIn: Boolean, priorUse: Boolean): Boolean =
        stored ?: (signedIn || priorUse)

    /**
     * Whether the next connect uses the automatic choice. Simple mode has no
     * multihop: a selected route falls back to automatic (the selection is
     * kept for Advanced mode). Android has no manual transport choice; the
     * transport is always automatic.
     */
    fun connectsAutomatically(advanced: Boolean, autoSelection: Boolean, selectedIsMultihop: Boolean): Boolean =
        autoSelection || (!advanced && selectedIsMultihop)

    /** The warm spare runs whenever Simple mode is on, else as the setting says. */
    fun warmSpareActive(advanced: Boolean, setting: Boolean): Boolean = setting || !advanced
}
