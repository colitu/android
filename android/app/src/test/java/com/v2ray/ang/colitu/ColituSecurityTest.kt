package com.v2ray.ang.colitu

import com.google.gson.JsonParser
import com.v2ray.ang.colitu.api.ColituApiClient
import com.v2ray.ang.colitu.api.ColituLocalProxy
import com.v2ray.ang.colitu.app.planLeftOf
import com.v2ray.ang.colitu.l10n.ColituLoc
import com.v2ray.ang.colitu.repository.ColituSupportRepository
import com.v2ray.ang.colitu.update.ColituUpdateSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64

class ColituSecurityTest {

    // ── Update manifest signature ──────────────────────────────────────────

    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
    private val decode: (String) -> ByteArray = { Base64.getDecoder().decode(it) }

    private fun manifest() = JsonParser.parseString(
        """{
          "latestVersionCode":23000,"versionName":"2.3.0","forceUpdate":false,
          "downloadUrl":"https://colitu.com/downloads/android/Colitu-2.3.0.apk","sha256":"AB12",
          "variants":{
            "arm64-v8a":{"url":"https://colitu.com/downloads/android/Colitu-2.3.0-arm64-v8a.apk","sha256":"cd34"},
            "armeabi-v7a":{"url":"https://colitu.com/downloads/android/Colitu-2.3.0-armeabi-v7a.apk","sha256":"ef56"}
          }
        }""",
    ).asJsonObject

    private fun signed() = manifest().apply {
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(ColituUpdateSignature.message(this@apply).toByteArray())
            sign()
        }
        addProperty("signature", Base64.getEncoder().encodeToString(signature))
    }

    @Test
    fun updateManifestSignedWithTheReleaseKeyIsAccepted() {
        assertTrue(ColituUpdateSignature.verify(signed(), decode, publicKey))
    }

    @Test
    fun anyChangedFieldBreaksTheSignature() {
        assertFalse(ColituUpdateSignature.verify(signed().apply { addProperty("forceUpdate", true) }, decode, publicKey))
        assertFalse(ColituUpdateSignature.verify(signed().apply { addProperty("latestVersionCode", 99999) }, decode, publicKey))
        assertFalse(
            ColituUpdateSignature.verify(
                signed().apply { getAsJsonObject("variants").getAsJsonObject("arm64-v8a").addProperty("url", "https://evil.example/x.apk") },
                decode,
                publicKey,
            ),
        )
    }

    @Test
    fun unsignedOrForeignManifestsAreIgnored() {
        assertFalse(ColituUpdateSignature.verify(manifest(), decode, publicKey))
        // Signed by our key, checked against the app's embedded (different) key.
        assertFalse(ColituUpdateSignature.verify(signed(), decode))
    }

    @Test
    fun manifestSignedByTheReleaseScriptVerifiesWithTheEmbeddedKey() {
        // Output of scripts/sign-update-manifest.ps1 with the real release key.
        val signed = JsonParser.parseString(
            """{"versionName":"2.3.0","latestVersionCode":4023000,"downloadUrl":"https://colitu.com/downloads/android/Colitu-2.3.0.apk","sha256":"ABCDEF","sizeBytes":60208441,"minAndroid":24,"forceUpdate":false,"variants":{"armeabi-v7a":{"url":"https://colitu.com/downloads/android/Colitu-2.3.0-armeabi-v7a.apk","sha256":"11aa","sizeBytes":1},"arm64-v8a":{"url":"https://colitu.com/downloads/android/Colitu-2.3.0-arm64-v8a.apk","sha256":"22BB","sizeBytes":2}},"signature":"MEYCIQDBqghuQeynTix+PYtbFi6WxuKYS9MwBUBaczG4s+zTLgIhALbe964Q9pR4/a9S5NpyDfLrKZCWSPsomLlI+JmH5z7Z"}""",
        ).asJsonObject
        assertTrue(ColituUpdateSignature.verify(signed, decode))
        assertFalse(ColituUpdateSignature.verify(signed.apply { addProperty("forceUpdate", true) }, decode))
    }

    // ── Session refresh ────────────────────────────────────────────────────

    @Test
    fun onlyARejectedRefreshTokenEndsTheSession() {
        assertEquals("Renewed", ColituApiClient.refreshOutcomeFor(200).name)
        for (code in listOf(400, 401, 403)) assertEquals("Rejected", ColituApiClient.refreshOutcomeFor(code).name)
        // Deploys, rate limits and outages must not sign anybody out.
        for (code in listOf(-1, 429, 500, 502, 503, 504)) assertEquals("Unavailable", ColituApiClient.refreshOutcomeFor(code).name)
    }

    // ── Support diagnostics ────────────────────────────────────────────────

    @Test
    fun diagnosticsMaskCredentialsAndAddresses() {
        val text = """
            vless://11111111-1111@node.example:443
            Authorization: Bearer abc.def.ghi
            {"id":"uuid-1","password":"p","publicKey":"k","short_id":"s"}
            dial tcp 203.0.113.77:8443 failed, v6 2001:db8:21e0::98:36
            https://node.example/sub?token=tok123&pbk=pbk456 sid=sid789 user mail@example.com
        """.trimIndent()
        val masked = ColituSupportRepository.redact(text)
        for (secret in listOf("11111111-1111", "abc.def.ghi", "uuid-1", "\"p\"", "\"k\"", "\"s\"", "203.0.113.77", "2001:db8", "tok123", "pbk456", "sid789", "mail@example.com")) {
            assertFalse("$secret leaked", masked.contains(secret))
        }
    }

    // ── Local SOCKS account ────────────────────────────────────────────────

    @Test
    fun localProxyTokensAreRandomHex() {
        val a = ColituLocalProxy.token(24)
        val b = ColituLocalProxy.token(24)
        assertEquals(48, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(a != b)
    }

    // ── Plan display ───────────────────────────────────────────────────────

    @Test
    fun farAwayExpiryIsShownAsNoExpiry() {
        assertEquals(ColituLoc["plan.lifetime"], planLeftOf(Instant.parse("2099-01-01T00:00:00Z")))
    }
}
