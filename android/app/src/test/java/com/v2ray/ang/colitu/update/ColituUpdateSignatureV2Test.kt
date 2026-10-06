package com.v2ray.ang.colitu.update

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class ColituUpdateSignatureV2Test {
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
    private val decode: (String) -> ByteArray = { Base64.getDecoder().decode(it) }
    private val cert = "900e364616e5fce83766310f74bd06e4743a6936460a9e108edd096dfb5e82fc"

    private fun manifest(): JsonObject = JsonParser.parseString(
        """{
          "latestVersionCode":4026000,"versionName":"2.6.0","forceUpdate":false,"minAndroid":"7.0",
          "downloadUrl":"https://colitu.com/downloads/android/Colitu-2.6.0.apk","sha256":"AB12","sizeBytes":61000000,
          "signingCertSha256":"90:0E:36:46:16:E5:FC:E8:37:66:31:0F:74:BD:06:E4:74:3A:69:36:46:0A:9E:10:8E:DD:09:6D:FB:5E:82:FC",
          "variants":{
            "armeabi-v7a":{"url":"https://colitu.com/downloads/android/Colitu-2.6.0-armeabi-v7a.apk","sha256":"ef56","sizeBytes":25000000},
            "arm64-v8a":{"url":"https://colitu.com/downloads/android/Colitu-2.6.0-arm64-v8a.apk","sha256":"CD34","sizeBytes":24000000}
          }
        }""",
    ).asJsonObject

    private fun signed(json: JsonObject = manifest()) = json.apply {
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(ColituUpdateSignature.messageV2(this@apply)!!.toByteArray())
            sign()
        }
        addProperty("signature_v2", Base64.getEncoder().encodeToString(signature))
    }

    @Test
    fun canonicalPayloadIsExact() {
        assertEquals(
            listOf(
                "colitu-android-update-v2",
                "latestVersionCode=4026000",
                "versionName=2.6.0",
                "forceUpdate=false",
                "minAndroid=7.0",
                "downloadUrl=https://colitu.com/downloads/android/Colitu-2.6.0.apk",
                "sha256=ab12",
                "sizeBytes=61000000",
                "signingCertSha256=$cert",
                "variant=arm64-v8a https://colitu.com/downloads/android/Colitu-2.6.0-arm64-v8a.apk cd34 24000000",
                "variant=armeabi-v7a https://colitu.com/downloads/android/Colitu-2.6.0-armeabi-v7a.apk ef56 25000000",
            ).joinToString("\n"),
            ColituUpdateSignature.messageV2(manifest()),
        )
    }

    @Test
    fun signedManifestVerifies() {
        assertTrue(ColituUpdateSignature.verifyV2(signed(), decode, publicKey))
    }

    @Test
    fun everySignedFieldIsCovered() {
        val tampered = listOf<JsonObject.() -> Unit>(
            { addProperty("signingCertSha256", "11".repeat(32)) },
            { addProperty("sizeBytes", 1) },
            { addProperty("minAndroid", "5.0") },
            { addProperty("forceUpdate", true) },
            { addProperty("latestVersionCode", 4099999) },
            { addProperty("downloadUrl", "https://evil.example/x.apk") },
            { getAsJsonObject("variants").getAsJsonObject("arm64-v8a").addProperty("sizeBytes", 5) },
            { getAsJsonObject("variants").getAsJsonObject("arm64-v8a").addProperty("sha256", "00") },
            { getAsJsonObject("variants").add("x86_64", JsonParser.parseString("""{"url":"https://colitu.com/x.apk","sha256":"aa","sizeBytes":1}""")) },
        )
        tampered.forEach { change -> assertFalse(ColituUpdateSignature.verifyV2(signed().apply(change), decode, publicKey)) }
    }

    @Test
    fun v1OnlyUnsignedOrMalformedManifestsAreRejected() {
        assertFalse(ColituUpdateSignature.verifyV2(manifest(), decode, publicKey))
        // A valid signature for a manifest without a certificate is still refused.
        val noCert = manifest().apply { remove("signingCertSha256") }
        assertFalse(ColituUpdateSignature.verifyV2(signed(noCert), decode, publicKey))
        // Foreign key: checked against the embedded release key.
        assertFalse(ColituUpdateSignature.verifyV2(signed(), decode))
    }

    @Test
    fun lineBreaksInAFieldMakeThePayloadInvalid() {
        assertNull(ColituUpdateSignature.messageV2(manifest().apply { addProperty("versionName", "2.6.0\nforceUpdate=true") }))
        assertNull(
            ColituUpdateSignature.messageV2(
                manifest().apply { getAsJsonObject("variants").getAsJsonObject("arm64-v8a").addProperty("url", "https://a b") },
            ),
        )
    }

    @Test
    fun manifestSignedByTheReleaseScriptVerifiesWithTheEmbeddedKey() {
        // Output of scripts/sign-update-manifest.ps1 (v1 + v2) with the real release key.
        val signed = JsonParser.parseString(
            """{"latestVersionCode":4026000,"versionName":"2.6.0","downloadUrl":"https://colitu.com/downloads/android/Colitu-2.6.0.apk","sha256":"AB12","sizeBytes":61000000,"minAndroid":"7.0","signingCertSha256":"900e364616e5fce83766310f74bd06e4743a6936460a9e108edd096dfb5e82fc","forceUpdate":false,"variants":{"armeabi-v7a":{"url":"https://colitu.com/downloads/android/Colitu-2.6.0-armeabi-v7a.apk","sha256":"ef56","sizeBytes":25000000},"arm64-v8a":{"url":"https://colitu.com/downloads/android/Colitu-2.6.0-arm64-v8a.apk","sha256":"CD34","sizeBytes":24000000}},"signature":"MEUCIC9hFFIORP5kseq8rY+uO9wVDxdNaY3vXyYjWp4aYiY7AiEA/MS/0kwNtWFGfKJoHlo2A06TIwnlHylmtXHfVjCmRrA=","signature_v2":"MEQCIBy0ZtAceDK48R0Pge1r8uOJt0V4CmikMBSspOLb+ybWAiBhfLD+KonEDLDlCTveD5MH9ngnXg8Md+3CW/bMZ/tztg=="}""",
        ).asJsonObject
        assertTrue(ColituUpdateSignature.verifyV2(signed, decode))
        // Old apps keep accepting the same file through the v1 signature.
        assertTrue(ColituUpdateSignature.verify(signed, decode))
        assertFalse(ColituUpdateSignature.verifyV2(signed.deepCopy().apply { addProperty("signingCertSha256", "11".repeat(32)) }, decode))
        // signingCertSha256 is outside v1: changing it keeps v1 valid, which is why 2.6.0+ requires v2.
        assertTrue(ColituUpdateSignature.verify(signed.deepCopy().apply { addProperty("signingCertSha256", "11".repeat(32)) }, decode))
    }

    @Test
    fun apkMustCarryTheSignedAndInstalledCertificate() {
        val mine = setOf(cert)
        assertTrue(ColituUpdater.certificateMatches(mine, setOf(cert), cert))
        assertTrue(ColituUpdater.certificateMatches(mine, setOf(cert), cert.uppercase()))
        // Installed and downloaded agree, but the manifest signed another certificate.
        assertFalse(ColituUpdater.certificateMatches(mine, setOf(cert), "11".repeat(32)))
        // The manifest's certificate, but the phone runs a different one.
        assertFalse(ColituUpdater.certificateMatches(setOf("22".repeat(32)), setOf(cert), cert))
        // An extra signer is refused.
        assertFalse(ColituUpdater.certificateMatches(mine, setOf(cert, "33".repeat(32)), cert))
        assertFalse(ColituUpdater.certificateMatches(emptySet(), emptySet(), cert))
    }
}
