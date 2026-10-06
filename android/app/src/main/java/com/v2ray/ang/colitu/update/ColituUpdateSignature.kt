package com.v2ray.ang.colitu.update

import com.google.gson.JsonObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * latest.json is signed with the release key (ECDSA P-256, SHA-256, DER
 * signature) by scripts/sign-update-manifest.ps1. The SHA-256 of the APK
 * alone only proves the file matches the manifest; the signature proves the
 * manifest (version, force flag, every download URL and hash) came from us,
 * so a compromised web server or CDN cannot push a "forced update" prompt.
 * Unsigned or altered manifests are ignored.
 */
object ColituUpdateSignature {
    /** SubjectPublicKeyInfo (DER) of _gizli_anahtarlar/colitu-android--update-signing-public.pem. */
    private const val PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAETUT5bez5lIH2oLF3GsTJYpT65eBbF5qR5paXJQYvclihmQsQ/bZPHQ5QWMFT4kh1qzVdB6QWu0+4AzlV+i53tA=="

    /** The exact text the release script signs; any change here must be mirrored there. */
    fun message(json: JsonObject): String {
        val lines = mutableListOf(
            "colitu-android-update-v1",
            json.long("latestVersionCode").toString(),
            json.str("versionName"),
            if (json.bool("forceUpdate")) "true" else "false",
            json.str("downloadUrl"),
            json.str("sha256").lowercase(),
        )
        val variants = json.get("variants")?.takeIf { it.isJsonObject }?.asJsonObject
        variants?.keySet()?.sorted()?.forEach { abi ->
            val v = variants.get(abi)?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            lines += "$abi ${v.str("url")} ${v.str("sha256").lowercase()}"
        }
        return lines.joinToString("\n")
    }

    /** [decode] turns base64 into bytes (android.util.Base64 in the app, java.util.Base64 in tests). */
    fun verify(json: JsonObject, decode: (String) -> ByteArray, publicKeyB64: String = PUBLIC_KEY_B64): Boolean =
        runCatching {
            val signature = json.str("signature").takeIf { it.isNotBlank() } ?: return false
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decode(publicKeyB64)))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(message(json).toByteArray(Charsets.UTF_8))
                verify(decode(signature.trim()))
            }
        }.getOrDefault(false)

    // ── v2: versioned payload that also binds the signing certificate ─────

    /**
     * The canonical text behind "signature_v2" (2.6.0+). Unlike v1 it covers
     * every field the updater acts on, including the APK signing certificate
     * ("signingCertSha256"), the sizes and minAndroid, as "key=value" lines
     * so no field can bleed into another:
     *
     *     colitu-android-update-v2
     *     latestVersionCode=<integer>
     *     versionName=<text>
     *     forceUpdate=<true|false>
     *     minAndroid=<text>
     *     downloadUrl=<url>
     *     sha256=<lower-case hex>
     *     sizeBytes=<integer>
     *     signingCertSha256=<lower-case hex, no colons>
     *     variant=<abi> <url> <lower-case sha256> <sizeBytes>   (ABIs in ordinal order)
     *
     * LF separated, no trailing newline, UTF-8. scripts/sign-update-manifest.ps1
     * builds the same text. Null when a value contains a line break or space
     * where it must not (such a manifest is never accepted).
     */
    fun messageV2(json: JsonObject): String? {
        val lines = mutableListOf(
            V2_HEADER,
            "latestVersionCode=${json.long("latestVersionCode")}",
            "versionName=${json.str("versionName")}",
            "forceUpdate=${if (json.bool("forceUpdate")) "true" else "false"}",
            "minAndroid=${json.str("minAndroid")}",
            "downloadUrl=${json.str("downloadUrl")}",
            "sha256=${json.str("sha256").lowercase()}",
            "sizeBytes=${json.long("sizeBytes").coerceAtLeast(0)}",
            "signingCertSha256=${normalizeCert(json.str("signingCertSha256"))}",
        )
        val variants = json.get("variants")?.takeIf { it.isJsonObject }?.asJsonObject
        variants?.keySet()?.sorted()?.forEach { abi ->
            val v = variants.get(abi)?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val parts = listOf(abi, v.str("url"), v.str("sha256").lowercase(), v.long("sizeBytes").coerceAtLeast(0).toString())
            if (parts.any { ' ' in it }) return null
            lines += "variant=${parts.joinToString(" ")}"
        }
        if (lines.any { '\n' in it || '\r' in it }) return null
        return lines.joinToString("\n")
    }

    /** "AB:CD:…" or "abcd…" to lower-case hex without separators. */
    fun normalizeCert(value: String): String = value.trim().replace(":", "").lowercase()

    /**
     * True when "signature_v2" is a valid release signature over [messageV2]
     * and the manifest names a well-formed signing certificate (64 hex).
     */
    fun verifyV2(json: JsonObject, decode: (String) -> ByteArray, publicKeyB64: String = PUBLIC_KEY_B64): Boolean =
        runCatching {
            if (!CERT_PATTERN.matches(normalizeCert(json.str("signingCertSha256")))) return false
            val signature = json.str("signature_v2").takeIf { it.isNotBlank() } ?: return false
            val message = messageV2(json) ?: return false
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decode(publicKeyB64)))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(message.toByteArray(Charsets.UTF_8))
                verify(decode(signature.trim()))
            }
        }.getOrDefault(false)

    const val V2_HEADER = "colitu-android-update-v2"
    private val CERT_PATTERN = Regex("^[0-9a-f]{64}$")

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.long(key: String): Long =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() } ?: -1L

    private fun JsonObject.bool(key: String): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
}
