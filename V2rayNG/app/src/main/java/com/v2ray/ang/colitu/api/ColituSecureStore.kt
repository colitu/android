package com.v2ray.ang.colitu.api

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.v2ray.ang.AngApplication
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small Android Keystore backed store. Values are authenticated with AES-GCM. */
object ColituSecureStore {
    private const val ALIAS = "colitu_client_secrets_v1"
    private const val FILE = "colitu_secure"
    private val preferences by lazy { AngApplication.application.getSharedPreferences(FILE, 0) }

    fun get(key: String): String? = preferences.getString(key, null)?.let { encoded ->
        runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    fun put(key: String, value: String?) {
        if (value == null) { preferences.edit().remove(key).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        preferences.edit().putString(key, Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).apply()
    }

    fun remove(vararg keys: String) { preferences.edit().also { editor -> keys.forEach(editor::remove) }.apply() }

    internal fun sealConfig(value: String, context: String): String = Base64.encodeToString(
        AuthenticatedConfigCodec.seal(value.toByteArray(Charsets.UTF_8), key(), context), Base64.NO_WRAP)

    internal fun openConfig(value: String, context: String): String = String(
        AuthenticatedConfigCodec.open(Base64.decode(value, Base64.NO_WRAP), key(), context), Charsets.UTF_8)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
}
