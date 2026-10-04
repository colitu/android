package com.v2ray.ang.colitu.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Updates the sideloaded app from colitu.com: reads
 * https://colitu.com/downloads/android/latest.json, checks the manifest's
 * release signature ([ColituUpdateSignature]), downloads the APK for this
 * phone's CPU, checks its SHA-256 and that it is signed with the same key as
 * the installed app, then hands it to Android's package installer.
 *
 * Only the "direct" build (the APK on colitu.com) has this; Google Play and
 * F-Droid do not allow apps that install their own updates.
 */
object ColituUpdater {
    private val MANIFEST_URL = com.v2ray.ang.BuildConfig.COLITU_UPDATE_MANIFEST_URL
    private const val TRUSTED_HOST = "colitu.com"
    /** No Colitu APK comes near this; a bigger download is cut off. */
    private const val MAX_APK_BYTES = 250L * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 64 * 1024

    val enabled: Boolean get() = com.v2ray.ang.BuildConfig.COLITU_SELF_UPDATE

    /** The release the update dialog is showing; null hides it. */
    var offered by mutableStateOf<Update?>(null)

    data class Update(
        val versionName: String,
        val versionCode: Long,
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
        val force: Boolean,
    )

    /** The newer release on colitu.com, or null when this build is current or the check failed. */
    suspend fun check(context: Context): Update? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext null
        runCatching {
            val json = JsonParser.parseString(fetchText("$MANIFEST_URL?t=${System.currentTimeMillis() / 60000}")).asJsonObject
            if (!ColituUpdateSignature.verify(json, { android.util.Base64.decode(it, android.util.Base64.DEFAULT) })) {
                LogUtil.w(AppConfig.TAG, "Colitu update manifest is not signed by the release key; ignored")
                return@runCatching null
            }
            val remoteCode = json.get("latestVersionCode")?.asLong ?: return@runCatching null
            if (remoteCode <= installedVersionCode(context)) return@runCatching null
            val variant = pickVariant(json)
            val url = variant?.get("url")?.asString ?: json.get("downloadUrl")?.asString ?: return@runCatching null
            val sha = variant?.get("sha256")?.asString ?: json.get("sha256")?.asString ?: return@runCatching null
            val size = variant?.get("sizeBytes")?.asLong ?: json.get("sizeBytes")?.asLong ?: 0L
            if (!trusted(url)) return@runCatching null
            Update(
                versionName = json.get("versionName")?.asString ?: remoteCode.toString(),
                versionCode = remoteCode,
                url = url,
                sha256 = sha.lowercase(),
                sizeBytes = size,
                // Same strict reading as the signed message: only a JSON true forces.
                force = json.get("forceUpdate")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true,
            )
        }.onFailure { LogUtil.w(AppConfig.TAG, "Colitu update check failed: ${it.message}") }.getOrNull()
    }

    /** Downloads and verifies the APK; [onProgress] gets 0..1. */
    suspend fun download(context: Context, update: Update, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "Colitu-${update.versionCode}.apk")
        dir.listFiles()?.forEach { if (it.name != target.name) it.delete() }
        if (target.exists() && sha256(target) == update.sha256) return@withContext target.also { onProgress(1f) }

        val partial = File(dir, target.name + ".part")
        val connection = (URL(update.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("User-Agent", "ColituVPN Android updater")
        }
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: update.sizeBytes
            // The manifest's size (plus slack) bounds the download; without it a hard cap.
            val limit = update.sizeBytes.takeIf { it > 0 }?.let { it + it / 20 + 1024 }?.coerceAtMost(MAX_APK_BYTES) ?: MAX_APK_BYTES
            if (total > limit) error("download too large")
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        if (done > limit) error("download too large")
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != update.sha256) {
                partial.delete()
                error("checksum mismatch")
            }
            if (!signedLikeThisApp(context, partial)) {
                partial.delete()
                error("signature mismatch")
            }
            target.delete()
            if (!partial.renameTo(target)) {
                partial.delete()
                error("could not store the update")
            }
            target
        } catch (e: Exception) {
            partial.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    /** False on Android 8+ until the user lets Colitu install apps. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Opens the system installer; the app data stays because the key matches. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.cache", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun installedVersionCode(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    private fun pickVariant(json: JsonObject): JsonObject? {
        val variants = json.getAsJsonObject("variants") ?: return null
        val abi = Build.SUPPORTED_ABIS.firstOrNull { variants.has(it) } ?: return null
        return variants.getAsJsonObject(abi)
    }

    private fun trusted(url: String): Boolean {
        val uri = runCatching { URL(url) }.getOrNull() ?: return false
        if (com.v2ray.ang.BuildConfig.DEBUG && uri.host == "10.0.2.2") return true
        return uri.protocol == "https" && (uri.host == TRUSTED_HOST || uri.host.endsWith(".$TRUSTED_HOST"))
    }

    private fun fetchText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_MANIFEST_BYTES) error("manifest too large")
                }
                out.toByteArray()
            }
            return String(bytes, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The downloaded APK must carry the installed app's signing certificate. */
    private fun signedLikeThisApp(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
        if (archive.packageName != context.packageName) return false
        val installed = pm.getPackageInfo(context.packageName, flags)
        val mine = certificates(installed)
        val theirs = certificates(archive)
        return mine.isNotEmpty() && mine == theirs
    }

    private fun certificates(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            @Suppress("DEPRECATION") info.signatures
        } ?: return emptySet()
        return signatures.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}
