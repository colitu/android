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
import com.v2ray.ang.colitu.data.ColituDiagnosticsRequest
import com.v2ray.ang.colitu.data.ColituDiagnosticsUpload
import com.v2ray.ang.colitu.data.ColituSupportConversation
import com.v2ray.ang.colitu.data.ColituSupportDiagnostics
import com.v2ray.ang.colitu.data.SiteRedaction
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
    /** All attachments of one message together; they are held in memory until sent (low-RAM TVs). */
    const val MAX_TOTAL_BYTES = 20L * 1024 * 1024

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

    /**
     * Reads a picked file; null (with a reason key) when it is too big or
     * unreadable, or when it does not fit in [remaining] bytes of the
     * message's [MAX_TOTAL_BYTES].
     */
    suspend fun readAttachment(context: Context, uri: Uri, remaining: Long = MAX_TOTAL_BYTES): Pair<Attachment?, String?> = withContext(Dispatchers.IO) {
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
            if (size > remaining) return@runCatching null to "support.err.total"
            val bytes = resolver.openInputStream(uri)?.use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                    if (buffer.size() > MAX_FILE_BYTES) return@runCatching null to "support.err.file"
                    if (buffer.size() > remaining) return@runCatching null to "support.err.total"
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

    // ── Connection report requests ──────────────────────────────────────────

    /** The open request of a support agent, or null (also for an older panel without the endpoint). */
    suspend fun pendingDiagnosticsRequest(): Result<ColituDiagnosticsRequest?> =
        when (val result = ColituApiClient.get("/support/diagnostics-request")) {
            is ColituApiClient.ApiResult.Success -> Result.success(ColituDiagnosticsRequest.parseResponse(result.data))
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(result.message))
        }

    /** Attaches [report] to a conversation (a new one, with diagnostics on). */
    suspend fun uploadReport(conversationId: String, report: String): Result<Unit> {
        val body = ColituDiagnosticsUpload.body(report, appVersionLabel(), osLabel())
        return when (val result = ColituApiClient.post("/support/conversations/${Uri.encode(conversationId)}/diagnostics", body)) {
            is ColituApiClient.ApiResult.Success -> Result.success(Unit)
            is ColituApiClient.ApiResult.Error -> Result.failure(Exception(result.message))
        }
    }

    enum class RequestReportResult { Sent, NoLongerPending, Failed }

    /** Answers an agent's request; a 404 means it is no longer pending (never retried). */
    suspend fun sendRequestedReport(requestId: String, report: String): RequestReportResult {
        val body = ColituDiagnosticsUpload.body(report, appVersionLabel(), osLabel())
        return when (val result = ColituApiClient.post("/support/diagnostics-requests/${Uri.encode(requestId)}/report", body)) {
            is ColituApiClient.ApiResult.Success -> RequestReportResult.Sent
            is ColituApiClient.ApiResult.Error -> if (result.code == 404) RequestReportResult.NoLongerPending else RequestReportResult.Failed
        }
    }

    private fun appVersionLabel() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    private fun osLabel() = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    /**
     * The full connection report as plain text in "===== Section =====" blocks:
     * the header fields of [diagnostics] plus a long tail of the app's own Colitu
     * log. Everything is passed through [redact] (credentials, node names,
     * addresses, e-mails) and then by [SiteRedaction] (dial targets, lookups, URL hosts); the Xray core's log, which can name visited sites, is
     * never included.
     */
    suspend fun fullReport(
        context: Context,
        server: String?,
        protocol: String?,
        connected: Boolean,
        lastError: String?,
        knownHosts: Collection<String> = emptyList(),
    ): String = withContext(Dispatchers.IO) {
        val errors = listOfNotNull(lastError?.takeIf { it.isNotBlank() }, ColituApiClient.getLastFriendlyError()).distinct()
        val log = recentLog(FULL_LOG_CHARS, FULL_LOG_LINES)
        val report = buildString {
            section("Report") {
                line("Generated", java.time.Instant.now().toString())
                line("Language", ColituLoc.language)
            }
            section("Device") {
                line("Device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                line("OS", osLabel())
            }
            section("App") {
                line("Version", appVersionLabel())
                line("Package", BuildConfig.APPLICATION_ID)
            }
            section("Connection") {
                line("Connected", connected.toString())
                line("Server", server)
                line("Protocol", protocol)
                line("Network", networkType(context))
            }
            section("Last errors") {
                if (errors.isEmpty()) append("(none)\n") else errors.forEach { append(it).append('\n') }
            }
            section("Connection events (newest last)") {
                val events = runCatching { com.v2ray.ang.colitu.data.ColituAcEvents.journal() }.getOrDefault(emptyList())
                if (events.isEmpty()) append("(none)\n") else events.forEach { append(it).append('\n') }
            }
            section("Log (Colitu, last lines)") { append(log ?: "(empty)").append('\n') }
        }
        SiteRedaction.apply(redact(report), knownHosts)
    }

    private fun StringBuilder.section(title: String, body: StringBuilder.() -> Unit) {
        append("===== ").append(title).append(" =====\n")
        body()
        append('\n')
    }

    private fun StringBuilder.line(key: String, value: String?) {
        if (!value.isNullOrBlank()) append(key).append(": ").append(value).append('\n')
    }

    private const val FULL_LOG_CHARS = 200_000
    private const val FULL_LOG_LINES = 3000

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
     * name the sites a user visits, is filtered out by tag), at most ~24 KB (more for the full report),
     * with credentials and IP addresses masked. The last [lines] are taken
     * here, after the tag filter: `logcat -t` counts the buffer's lines
     * before it, and while a transport stalls the core's own warnings fill
     * the last few hundred, which left the report's log empty.
     */
    private fun recentLog(maxChars: Int = 24_000, lines: Int = 400): String? = runCatching {
        val command = listOf("logcat", "-d", "-v", "time") + LOG_TAGS.map { "$it:V" } + "*:S"
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val tail = ArrayDeque<String>(lines)
        process.inputStream.bufferedReader().useLines { all ->
            all.forEach { line ->
                if (tail.size == lines) tail.removeFirst()
                tail.addLast(line)
            }
        }
        process.destroy()
        redact(tail.joinToString("\n").takeLast(maxChars)).takeIf { it.isNotBlank() }
    }.getOrNull()

    private val shareLink = Regex("((?:vless|vmess|trojan|hysteria2|hy2|ss|tuic)://)[^@\\s/]+@", RegexOption.IGNORE_CASE)
    private val bearer = Regex("Bearer\\s+[A-Za-z0-9\\-_.=]+", RegexOption.IGNORE_CASE)
    private val jsonSecret = Regex(
        "(\"(?:password|pass|user|uuid|id|auth|token|access_token|refresh_token|private_key|privateKey|public_key|publicKey|short_id|shortId)\"\\s*:\\s*)\"[^\"]*\"",
        RegexOption.IGNORE_CASE,
    )
    // Share-link / URL parameters such as pbk=, sid=, password=, token=.
    private val querySecret = Regex(
        "\\b(password|pass|pwd|pbk|sid|token|access_token|refresh_token|key|auth|uuid)=[^&\\s\"]+",
        RegexOption.IGNORE_CASE,
    )
    // Node names: TLS SNI / server_name and host fields, share-link sni=/host=
    // parameters, "host:port" pairs and URL hosts other than colitu.com.
    private val jsonHost = Regex(
        "(\"(?:serverName|server_name|sni|host|address|latency_host|latencyHost)\"\\s*:\\s*)\"[^\"]*\"",
        RegexOption.IGNORE_CASE,
    )
    private val queryHost = Regex("\\b(sni|host|servername|peer)=[^&\\s\"]+", RegexOption.IGNORE_CASE)
    private val urlHost = Regex("\\b([a-z][a-z0-9+.-]*://(?:[^@/\\s]*@)?)([a-z0-9-]+(?:\\.[a-z0-9-]+)+)", RegexOption.IGNORE_CASE)
    private val hostPort = Regex("\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}(?=:\\d{1,5}\\b)", RegexOption.IGNORE_CASE)

    private fun ownHost(host: String): Boolean = host.lowercase().let { it == "colitu.com" || it.endsWith(".colitu.com") }

    private val email = Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}\\b")
    private val ipv4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    // An IPv6 address has "::" or at least five colons; "05:15:16" in a log
    // line's time stamp has two and stays readable.
    private val ipv6 = Regex("(?<![\\w:])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![\\w:])", RegexOption.IGNORE_CASE)
    private fun isIpv6(v: String): Boolean = "::" in v || v.count { it == ':' } >= 5

    fun redact(text: String): String = text
        .replace(shareLink, "$1***@")
        .replace(bearer, "Bearer ***")
        .replace(jsonSecret, "$1\"***\"")
        .replace(querySecret, "$1=***")
        .replace(email, "***@***")
        .replace(jsonHost, "$1\"***\"")
        .replace(queryHost, "$1=***")
        .replace(urlHost) { m -> if (ownHost(m.groupValues[2])) m.value else m.groupValues[1] + "***" }
        .replace(hostPort) { m -> if (ownHost(m.value)) m.value else "***" }
        .replace(ipv4, "x.x.x.x")
        .replace(ipv6) { m -> if (isIpv6(m.value)) "x:x::x" else m.value }
}
