package com.v2ray.ang.colitu.api

import com.tencent.mmkv.MMKV

/** Keeps existing core storage keys/API, but encrypts each secret-bearing value. */
internal class CoreConfigStorage(private val bucket: String) {
    private val store = MMKV.mmkvWithID(bucket, MMKV.MULTI_PROCESS_MODE)
    private val prefix = "colitu-gcm-v1:"

    init {
        // Convert every pre-migration row before any core profile can be used.
        // Legacy raw rows may be JSON or share links, and both contain secrets.
        store.allKeys()?.forEach { id ->
            val value = store.decodeString(id) ?: return@forEach
            if (!value.startsWith(prefix) && !runCatching { encode(id, value) }.getOrDefault(false)) {
                store.remove(id)
            }
        }
    }

    fun encode(id: String, value: String): Boolean = store.encode(id,
        prefix + ColituSecureStore.sealConfig(value, "$bucket:$id"))

    fun decodeString(id: String): String? {
        val stored = store.decodeString(id) ?: return null
        if (stored.startsWith(prefix)) {
            return runCatching { ColituSecureStore.openConfig(stored.removePrefix(prefix), "$bucket:$id") }.getOrNull()
        }
        // A row created concurrently by an older process is migrated on read.
        // Invalid ciphertext is never interpreted as plaintext.
        if (!runCatching { encode(id, stored) }.getOrDefault(false)) {
            store.remove(id)
            return null
        }
        return stored
    }

    fun remove(id: String) = store.remove(id)
    fun allKeys(): Array<String>? = store.allKeys()
    fun clearAll() = store.clearAll()
}
