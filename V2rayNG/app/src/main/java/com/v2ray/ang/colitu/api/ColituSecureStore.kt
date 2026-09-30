package com.v2ray.ang.colitu.api

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.v2ray.ang.AngApplication
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Small store for session tokens and VPN profiles, sealed with AES-GCM.
 *
 * The key normally lives in the Android Keystore. Some cheap TV boxes and old
 * phones ship a broken Keystore; there a random key in the app's private
 * files is used instead (values then carry the "s1." marker), so signing in
 * still works. Key creation is guarded by a file lock because the UI and the
 * VPN process can both start with no key yet; without it each would create
 * its own key and one process could no longer read what the other wrote.
 */
object ColituSecureStore {
    private const val TAG = "ColituSecureStore"
    private const val ALIAS = "colitu_client_secrets_v1"
    private const val FILE = "colitu_secure"
    private const val SOFT_MARKER = "s1."
    private val preferences by lazy { AngApplication.application.getSharedPreferences(FILE, 0) }

    @Volatile private var keystoreKey: SecretKey? = null
    @Volatile private var softwareKey: SecretKey? = null
    @Volatile private var keystoreWarned = false

    fun get(key: String): String? = preferences.getString(key, null)?.let { stored ->
        runCatching { String(open(stored, null), Charsets.UTF_8) }
            .onFailure { Log.w(TAG, "stored value $key could not be read: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    fun put(key: String, value: String?) {
        if (value == null) { preferences.edit().remove(key).apply(); return }
        preferences.edit().putString(key, seal(value.toByteArray(Charsets.UTF_8), null)).apply()
    }

    fun remove(vararg keys: String) { preferences.edit().also { editor -> keys.forEach(editor::remove) }.apply() }

    internal fun sealConfig(value: String, context: String): String = seal(value.toByteArray(Charsets.UTF_8), context)

    internal fun openConfig(value: String, context: String): String = String(open(value, context), Charsets.UTF_8)

    private fun seal(value: ByteArray, context: String?): String {
        val hardware = runCatching { keystore() }.getOrNull()
        val sealed = if (context != null) AuthenticatedConfigCodec.seal(value, hardware ?: software(), context)
        else gcm(value, hardware ?: software())
        val encoded = Base64.encodeToString(sealed, Base64.NO_WRAP)
        return if (hardware == null) SOFT_MARKER + encoded else encoded
    }

    private fun open(stored: String, context: String?): ByteArray {
        val softwareSealed = stored.startsWith(SOFT_MARKER)
        val bytes = Base64.decode(stored.removePrefix(SOFT_MARKER), Base64.NO_WRAP)
        val key = if (softwareSealed) software() else keystore()
        return if (context != null) AuthenticatedConfigCodec.open(bytes, key, context) else ungcm(bytes, key)
    }

    private fun gcm(value: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(value)
    }

    private fun ungcm(bytes: ByteArray, key: SecretKey): ByteArray {
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12))
        return cipher.doFinal(bytes, 12, bytes.size - 12)
    }

    /** The Keystore key; throws when the Keystore does not work on this device. */
    private fun keystore(): SecretKey {
        keystoreKey?.let { return it }
        return try {
            synchronized(this) {
                keystoreKey ?: withProcessLock {
                    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    (store.getKey(ALIAS, null) as? SecretKey) ?: run {
                        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                        generator.init(
                            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .setRandomizedEncryptionRequired(true)
                                .build(),
                        )
                        generator.generateKey()
                    }
                }.also { keystoreKey = it }
            }
        } catch (e: Exception) {
            if (!keystoreWarned) Log.w(TAG, "Android Keystore unavailable, using the app-private key: ${e.javaClass.simpleName}")
            keystoreWarned = true
            throw e
        }
    }

    /** Fallback key in app-private storage (no Keystore protection, but never leaves the app sandbox). */
    private fun software(): SecretKey {
        softwareKey?.let { return it }
        synchronized(this) {
            softwareKey?.let { return it }
            val key = withProcessLock {
                val file = File(AngApplication.application.noBackupFilesDir, "colitu_fallback.key")
                val bytes = if (file.length() == 32L) file.readBytes() else {
                    ByteArray(32).also(SecureRandom()::nextBytes).also { raw ->
                        val tmp = File(file.parentFile, file.name + ".tmp")
                        tmp.writeBytes(raw)
                        check(tmp.renameTo(file)) { "fallback key not stored" }
                    }
                }
                SecretKeySpec(bytes, "AES")
            }
            softwareKey = key
            return key
        }
    }

    /** Runs [block] while holding an exclusive lock shared by all of the app's processes. */
    private fun <T> withProcessLock(block: () -> T): T {
        val lockFile = File(AngApplication.application.noBackupFilesDir, "colitu_secure.lock")
        RandomAccessFile(lockFile, "rw").use { raf ->
            val lock = raf.channel.lock()
            try {
                return block()
            } finally {
                lock.release()
            }
        }
    }
}
