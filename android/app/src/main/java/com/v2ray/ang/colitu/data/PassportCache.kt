package com.v2ray.ang.colitu.data

/** The few storage calls the passport cache needs (MMKV in the app, a map in tests). */
interface PassportKv {
    fun string(key: String): String?
    fun putString(key: String, value: String?)
    fun bool(key: String): Boolean
    fun putBool(key: String, value: Boolean)
    fun long(key: String): Long
    fun putLong(key: String, value: Long)
}

/**
 * Last server state of the passport, for offline display, plus the
 * per-device bookkeeping of the sync (v1 import done, last notified event).
 * Cleared on sign-out except the import mark: a device imports its v1
 * passport once, whoever signs in later.
 */
class PassportCache(private val kv: PassportKv) {

    fun save(raw: String) = kv.putString(KEY_STATE, raw)

    fun raw(): String? = kv.string(KEY_STATE)

    fun load(): PassportState? = PassportJson.parseState(raw())

    /** This device's v1 passport was imported (or the server refused it for good). */
    var imported: Boolean
        get() = kv.bool(KEY_IMPORTED)
        set(value) = kv.putBool(KEY_IMPORTED, value)

    /** Highest event id the VPN process already posted a notification for. */
    var notifiedThrough: Long
        get() = kv.long(KEY_NOTIFIED)
        set(value) = kv.putLong(KEY_NOTIFIED, value)

    /** The time zone offset last sent with PUT /profile (minutes; Int.MIN_VALUE when never). */
    var tzSent: Int
        get() = kv.string(KEY_TZ)?.toIntOrNull() ?: Int.MIN_VALUE
        set(value) = kv.putString(KEY_TZ, value.toString())

    /** Sign-out: nothing of the account stays on the device. */
    fun clear() {
        kv.putString(KEY_STATE, null)
        kv.putString(KEY_TZ, null)
        kv.putLong(KEY_NOTIFIED, 0L)
    }

    companion object {
        const val KEY_STATE = "srv_state"
        const val KEY_IMPORTED = "v1_imported"
        const val KEY_NOTIFIED = "srv_notified"
        const val KEY_TZ = "srv_tz_sent"
    }
}
