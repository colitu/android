package com.v2ray.ang.colitu.api

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object AuthenticatedConfigCodec {
    fun seal(value: ByteArray, key: SecretKey, context: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        return cipher.iv + cipher.doFinal(value)
    }

    fun open(value: ByteArray, key: SecretKey, context: String): ByteArray {
        require(value.size >= 28) { "Invalid encrypted config" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, value, 0, 12))
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(value, 12, value.size - 12)
    }
}
