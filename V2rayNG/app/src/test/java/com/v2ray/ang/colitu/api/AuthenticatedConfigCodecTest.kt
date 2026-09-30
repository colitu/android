package com.v2ray.ang.colitu.api

import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class AuthenticatedConfigCodecTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun encryptionIsRandomizedAndRoundTrips() {
        val key = key()
        val plain = "fixture-config".toByteArray()
        val first = AuthenticatedConfigCodec.seal(plain, key, "profile:one")
        val second = AuthenticatedConfigCodec.seal(plain, key, "profile:one")
        assertFalse(first.contentEquals(second))
        assertArrayEquals(plain, AuthenticatedConfigCodec.open(first, key, "profile:one"))
    }

    @Test fun movingCiphertextToAnotherRecordOrBucketIsRejected() {
        val key = key()
        val value = AuthenticatedConfigCodec.seal("fixture".toByteArray(), key, "profile:one")
        for (context in listOf("profile:two", "raw:one")) {
            assertTrue(runCatching { AuthenticatedConfigCodec.open(value, key, context) }.isFailure)
        }
    }

    @Test fun tamperingWrongKeyAndTruncationAreRejected() {
        val key = key()
        val value = AuthenticatedConfigCodec.seal("fixture".toByteArray(), key, "profile:one")
        assertTrue(runCatching { AuthenticatedConfigCodec.open(value, key(), "profile:one") }.isFailure)
        val modified = value.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertTrue(runCatching { AuthenticatedConfigCodec.open(modified, key, "profile:one") }.isFailure)
        assertTrue(runCatching { AuthenticatedConfigCodec.open(value.copyOf(10), key, "profile:one") }.isFailure)
    }
}
