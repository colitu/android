package com.v2ray.ang.colitu.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.google.gson.JsonObject
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.colitu.api.ColituApiClient
import com.v2ray.ang.colitu.data.ColituSupportConversation
import com.v2ray.ang.colitu.data.ColituSupportDiagnostics
import com.v2ray.ang.colitu.data.ColituSupportMessage
import com.v2ray.ang.colitu.l10n.ColituLoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * In-app live support against the panel's /api/v1/support API: conversations,
 * messages with attachments, diagnostics and the unread counter.
 */
object ColituSupportRepository {
    const val MAX_FILE_BYTES = 10 * 1024 * 1024
    const val MAX_FILES = 5

    /** The types the panel stores; it re-checks the content itself. */
    val allowedMimeTypes = arrayOf("image/*", "application/pdf", "text/plain", "application/zip", "application/json")

    class Attachment(val name: String, val bytes: ByteArray)

    suspend fun conversations(): Result<List<ColituSupportConversation>> =
        when (val result = ColituApiClient.get("/support/conversations")) {
            is ColituApiClient.ApiResult.Success -> Result.success(
                result.data.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.let(ColituSupportConversation::fromJson) }
                    .orEmpty()
                    .sortedByDescending { it.lastMessageAt },
            )
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(result.message))
        }

    suspend fun thread(id: String): Result<Pair<ColituSupportConversation?, List<ColituSupportMessage>>> =
        when (val result = ColituApiClient.get("/support/conversations/${Uri.encode(id)}")) {
            is ColituApiClient.ApiResult.Success -> {
                val data = result.data.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                val conversation = data?.get("conversation")?.takeIf { it.isJsonObject }?.asJsonObject?.let(ColituSupportConversation::fromJson)
                val messages = data?.get("messages")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.let(ColituSupportMessage::fromJson) }
                    .orEmpty()
                Result.success(conversation to messages)
            }
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(result.message))
        }

    suspend fun unread(): Result<Int> =
        when (val result = ColituApiClient.get("/support/unread")) {
            is ColituApiClient.ApiResult.Success -> Result.success(
                result.data.get("data")?.takeIf { it.isJsonObject }?.asJsonObject?.get("unread")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt?.coerceAtLeast(0) ?: 0,
            )
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(result.message))
        }

    suspend fun create(subject: String, message: String, files: List<Attachment>, diagnostics: ColituSupportDiagnostics?): Result<ColituSupportConversation?> {
        val payload = JsonObject().apply {
            addProperty("subject", subject)
            addProperty("message", message)
            addProperty("locale", ColituLoc.language)
            diagnostics?.let { add("diagnostics", it.toJson()) }
        }
        return when (val result = send("/support/conversations", payload, files)) {
            is ColituApiClient.ApiResult.Success -> Result.success(
                result.data.get("data")?.takeIf { it.isJsonObject }?.asJsonObject?.let(ColituSupportConversation::fromJson),
            )
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(result.message))
        }
    }

    suspend fun reply(id: String, body: String, files: List<Attachment>): Result<Unit> {
        val payload = JsonObject().apply { addProperty("body", body) }
        return when (val result = send("/support/conversations/${Uri.encode(id)}/messages", payload, files)) {
            is ColituApiClient.ApiResult.Success -> Result.success(Unit)
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(result.message))
        }
    }

    suspend fun attachment(id: String): ByteArray? = ColituApiClient.getBytes("/support/attachments/${Uri.encode(id)}")

    private suspend fun send(path: String, payload: JsonObject, files: List<Attachment>) =
        if (files.isEmpty()) ColituApiClient.postRenewing(path, payload)
        else ColituApiClient.postMultipart(path, payload, files.map { ColituApiClient.UploadFile(it.name, it.bytes) })

    /** Reads a picked file; null (with a reason key) when it is too big or unreadable. */
    suspend fun readAttachment(context: Context, uri: Uri): Pair<Attachment?, String?> = withContext(Dispatchers.IO) {
        runCatching<Pair<Attachment?, String?>> {
            val resolver = context.contentResolver
            var name = "file"
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0) ?: name
                    size = if (cursor.isNull(1)) -1L else cursor.getLong(1)
                }
            }
            if (size > MAX_FILE_BYTES) return@runCatching null to "support.err.file"
            val bytes = resolver.openInputStream(uri)?.use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                    if (buffer.size() > MAX_FILE_BYTES) return@runCatching null to "support.err.file"
                }
                buffer.toByteArray()
            } ?: return@runCatching null to "support.err.file"
            if (bytes.isEmpty()) null to "support.err.file" else Attachment(name, bytes) to null
        }.getOrElse { null to "support.err.file" }
    }

    /**
     * Versions, phone model, network type, connection state, the last error
     * and the app's own recent log lines with credentials masked.
     */
    suspend fun diagnostics(
        context: Context,
        server: String?,
        protocol: String?,
        connected: Boolean,
        lastError: String?,
    ): ColituSupportDiagnostics = withContext(Dispatchers.IO) {
        ColituSupportDiagnostics(
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            os = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            network = networkType(context),
            server = server,
            protocol = protocol,
            connected = connected,
            lastErrors = listOfNotNull(lastError?.takeIf { it.isNotBlank() }, ColituApiClient.getLastFriendlyError()).distinct(),
            logs = recentLog(),
        )
    }

    private fun networkType(context: Context): String? = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "offline"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "vpn+wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "vpn+cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }.getOrNull()

    /** Log tags of Colitu's own code; the Xray core and hev are left out of reports. */
    private val LOG_TAGS = listOf(
        com.v2ray.ang.AppConfig.TAG, "Colitu", "ColituApiClient", "ColituSecureStore", "ColituServerRepo", "ColituVpnRepo",
    )

    /**
     * The app's own Colitu log lines only (the Xray core, whose warnings can
     * name the sites a user visits, is filtered out by tag), at most ~24 KB,
     * with credentials and IP addresses masked.
     */
    private fun recentLog(maxChars: Int = 24_000): String? = runCatching {
        val command = listOf("logcat", "-d", "-t", "400", "-v", "time") + LOG_TAGS.map { "$it:V" } + "*:S"
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.destroy()
        redact(text.takeLast(maxChars)).takeIf { it.isNotBlank() }
    }.getOrNull()

    private val shareLink = Regex("((?:vless|vmess|trojan|hysteria2|hy2|ss|tuic)://)[^@\\s/]+@", RegexOption.IGNORE_CASE)
    private val bearer = Regex("Bearer\\s+[A-Za-z0-9\\-_.=]+", RegexOption.IGNORE_CASE)
    private val jsonSecret = Regex(
        "(\"(?:password|pass|user|uuid|id|auth|token|access_token|refresh_token|private_key|privateKey|public_key|publicKey|short_id|shortId)\"\\s*:\\s*)\"[^\"]*\"",
        RegexOption.IGNORE_CASE,
    )
    private val ipv4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val ipv6 = Regex("\\b(?:[0-9a-f]{1,4}:){2,7}[0-9a-f]{1,4}\\b", RegexOption.IGNORE_CASE)

    fun redact(text: String): String = text
        .replace(shareLink, "$1***@")
        .replace(bearer, "Bearer ***")
        .replace(jsonSecret, "$1\"***\"")
        .replace(ipv4, "x.x.x.x")
        .replace(ipv6, "x:x::x")
}
