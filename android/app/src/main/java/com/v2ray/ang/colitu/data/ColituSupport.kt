package com.v2ray.ang.colitu.data

import com.google.gson.JsonObject
import java.time.Instant

/** One live-support conversation (panel /api/v1/support). */
data class ColituSupportConversation(
    val id: String,
    val subject: String,
    /** waiting, open, resolved or closed. */
    val status: String,
    val unread: Int,
    val lastMessage: String,
    val lastMessageAt: Instant?,
) {
    val closed: Boolean get() = status == "closed"

    companion object {
        fun fromJson(json: JsonObject): ColituSupportConversation? {
            val id = json.str("id") ?: return null
            return ColituSupportConversation(
                id = id,
                subject = json.str("subject").orEmpty(),
                status = json.str("status") ?: "waiting",
                unread = json.int("unread") ?: 0,
                lastMessage = json.str("last_message").orEmpty(),
                lastMessageAt = json.instant("last_message_at"),
            )
        }
    }
}

data class ColituSupportAttachment(
    val id: String,
    val fileName: String,
    val contentType: String,
    val sizeBytes: Long,
    val isImage: Boolean,
) {
    companion object {
        fun fromJson(json: JsonObject): ColituSupportAttachment? {
            val id = json.str("id") ?: return null
            val type = json.str("content_type") ?: "application/octet-stream"
            return ColituSupportAttachment(
                id = id,
                fileName = json.str("file_name") ?: "file",
                contentType = type,
                sizeBytes = json.long("size_bytes") ?: 0L,
                isImage = json.bool("is_image") ?: type.startsWith("image/"),
            )
        }
    }
}

data class ColituSupportMessage(
    val id: String,
    /** user, admin or bot. */
    val sender: String,
    val adminName: String?,
    val body: String,
    val createdAt: Instant?,
    val attachments: List<ColituSupportAttachment>,
) {
    val mine: Boolean get() = sender == "user"

    companion object {
        fun fromJson(json: JsonObject): ColituSupportMessage? {
            val id = json.str("id") ?: return null
            val files = json.get("attachments")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.let(ColituSupportAttachment::fromJson) }
                .orEmpty()
            return ColituSupportMessage(
                id = id,
                sender = json.str("sender") ?: "admin",
                adminName = json.str("admin_name"),
                body = json.str("body").orEmpty(),
                createdAt = json.instant("created_at"),
                attachments = files,
            )
        }
    }
}

/** What support needs to reproduce a problem; never browsing history. */
data class ColituSupportDiagnostics(
    val device: String,
    val os: String,
    val appVersion: String,
    val network: String?,
    val server: String?,
    val protocol: String?,
    val connected: Boolean,
    val lastErrors: List<String>,
    val logs: String?,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("platform", "android")
        addProperty("device", device)
        addProperty("os", os)
        addProperty("app_version", appVersion)
        network?.let { addProperty("network", it) }
        server?.let { addProperty("server", it) }
        protocol?.let { addProperty("protocol", it) }
        addProperty("connected", connected)
        if (lastErrors.isNotEmpty()) add("last_errors", com.google.gson.JsonArray().apply { lastErrors.forEach(::add) })
        logs?.let { addProperty("logs", it) }
    }
}

/**
 * A support agent's request for this device's connection report
 * (GET /support/diagnostics-request). The app answers it silently with the
 * redacted report (see [ColituDiagnosticsGate], [SiteRedaction]).
 */
data class ColituDiagnosticsRequest(
    val id: String,
    /** pending, sent, declined or expired; only pending ones are asked about. */
    val status: String,
    val createdAt: Instant?,
    val expiresAt: Instant?,
) {
    val pending: Boolean get() = status == "pending"

    companion object {
        fun fromJson(json: JsonObject): ColituDiagnosticsRequest? {
            val id = json.str("id") ?: return null
            return ColituDiagnosticsRequest(
                id = id,
                status = json.str("status") ?: "pending",
                createdAt = json.instant("created_at"),
                expiresAt = json.instant("expires_at"),
            )
        }

        /** `{"data":{"request":null | {...}}}`; null for no request, a request that is not pending or a bad shape. */
        fun parseResponse(root: JsonObject): ColituDiagnosticsRequest? {
            val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val request = data.get("request")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            return fromJson(request)?.takeIf { it.pending }
        }
    }
}

/** Body of POST /support/diagnostics-requests/{id}/report and /support/conversations/{id}/diagnostics. */
object ColituDiagnosticsUpload {
    fun body(report: String, appVersion: String, osVersion: String): JsonObject = JsonObject().apply {
        addProperty("report", report)
        addProperty("platform", "android")
        addProperty("app_version", appVersion)
        addProperty("os_version", osVersion)
    }
}

private fun JsonObject.str(key: String): String? =
    if (has(key) && !get(key).isJsonNull && get(key).isJsonPrimitive) get(key).asString.takeIf { it.isNotBlank() } else null

private fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()

private fun JsonObject.long(key: String): Long? = str(key)?.toLongOrNull()

private fun JsonObject.bool(key: String): Boolean? =
    if (has(key) && get(key).isJsonPrimitive && get(key).asJsonPrimitive.isBoolean) get(key).asBoolean else null

private fun JsonObject.instant(key: String): Instant? = str(key)?.let { raw ->
    runCatching { Instant.parse(raw) }.getOrNull() ?: runCatching { java.time.OffsetDateTime.parse(raw).toInstant() }.getOrNull()
}
