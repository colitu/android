package com.v2ray.ang.colitu.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.colitu.api.ColituClock
import java.time.Instant

/** Maps the backend-owned xray-mobile-v1 profile to the existing Xray custom-config runtime. */
object XrayMobileAdapter {
    /**
     * Transports in the order they are preferred. Hysteria2 (QUIC) keeps
     * working on lossy links where the TCP transports stall, so it goes first,
     * like on iOS and Windows.
     */
    val transportRank = mapOf(
        "hysteria2" to 0,
        "vless-reality" to 1,
        "trojan" to 2,
        "shadowsocks" to 4,
    )

    val supportedProtocols = transportRank.keys

    fun render(envelope: JsonObject, now: Instant = ColituClock.now()): ColituVpnConfig =
        renderProfile(envelope, envelope.getAsJsonObject("profile") ?: error("CONFIG_PROFILE_MISSING"), now)

    /**
     * The primary profile plus every optional transport the panel offers in
     * `candidates`, one runtime config each. A malformed optional transport is
     * skipped instead of hiding the working ones.
     */
    fun renderCandidates(envelope: JsonObject, now: Instant = ColituClock.now()): List<ColituVpnConfig> {
        // A primary transport this build cannot render must not hide the
        // others; only when nothing renders is its reason reported.
        val primary = runCatching { render(envelope, now) }
        val out = mutableListOf<ColituVpnConfig>()
        val seen = mutableSetOf<String?>()
        primary.getOrNull()?.let { out += it; seen += it.protocolType }
        val candidates = envelope.get("candidates")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        for (element in candidates) {
            val profile = element.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("profile")?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val config = runCatching { renderProfile(envelope, profile, now) }.getOrNull() ?: continue
            if (seen.add(config.protocolType)) out += config
        }
        if (out.isEmpty()) throw primary.exceptionOrNull() ?: IllegalStateException("CONFIG_NOT_READY")
        return out
    }

    private fun renderProfile(envelope: JsonObject, profile: JsonObject, now: Instant): ColituVpnConfig {
        require(envelope.has("revision")) { "CONFIG_REVISION_MISSING" }
        val revision = envelope.get("revision").asLong
        require(revision > 0) { "CONFIG_REVISION_INVALID" }
        val expires = Instant.parse(envelope.requiredString("expires_at"))
        val grace = Instant.parse(envelope.requiredString("offline_grace_until"))
        require(!grace.isBefore(expires)) { "CONFIG_LIFETIME_INVALID" }
        require(now.isBefore(grace)) { "CONFIG_EXPIRED" }
        require(profile.requiredString("format") == "xray-mobile-v1") { "CONFIG_FORMAT_UNSUPPORTED" }
        val payload = profile.getAsJsonObject("payload") ?: error("CONFIG_PAYLOAD_MISSING")
        require(payload.get("schema_version")?.asInt == 1) { "CONFIG_SCHEMA_UNSUPPORTED" }
        val protocol = payload.requiredString("protocol")
        require(protocol in supportedProtocols) { "CONFIG_PROTOCOL_UNSUPPORTED" }
        val endpoint = payload.getAsJsonObject("endpoint") ?: error("CONFIG_ENDPOINT_MISSING")
        val host = endpoint.requiredString("host")
        val port = endpoint.get("port")?.asInt ?: error("CONFIG_PORT_MISSING")
        require(port in 1..65535)
        val credentials = payload.getAsJsonObject("credentials") ?: error("CONFIG_CREDENTIALS_MISSING")
        val transport = payload.getAsJsonObject("transport") ?: error("CONFIG_TRANSPORT_MISSING")
        val security = payload.getAsJsonObject("security") ?: error("CONFIG_SECURITY_MISSING")
        val transportType = transport.requiredString("type")
        require(
            if (protocol == "hysteria2") transportType == "hysteria" else transportType in setOf("tcp", "ws", "grpc"),
        ) { "CONFIG_TRANSPORT_UNSUPPORTED" }
        require(
            when (protocol) {
                "vless-reality" -> security.requiredString("type") == "reality"
                "trojan", "hysteria2" -> security.requiredString("type") == "tls"
                else -> security.requiredString("type") == "none"
            },
        ) { "CONFIG_SECURITY_INVALID" }
        val outbound = when (protocol) {
            "vless-reality" -> vless(host, port, credentials, transport, security)
            "trojan" -> trojan(host, port, credentials, transport, security)
            "hysteria2" -> hysteria2(host, port, credentials, security)
            else -> shadowsocks(host, port, credentials)
        }
        val runtime = JsonObject().apply {
            add("log", JsonObject().apply { addProperty("loglevel", "warning") })
            add("inbounds", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("tag", "socks")
                    addProperty("listen", "127.0.0.1")
                    addProperty("port", 10808)
                    addProperty("protocol", "socks")
                    add("settings", JsonObject().apply { addProperty("udp", true) })
                })
            })
            add("outbounds", JsonArray().apply {
                add(outbound)
                add(JsonObject().apply {
                    addProperty("tag", "direct")
                    addProperty("protocol", "freedom")
                })
            })
            add("dns", payload.getAsJsonObject("dns_policy")?.deepCopy() ?: JsonObject())
            add("routing", payload.getAsJsonObject("routing_policy")?.deepCopy() ?: JsonObject())
            // Per-outbound counters feed the live upload/download speed on the home screen.
            add("stats", JsonObject())
            add("policy", JsonObject().apply {
                add("system", JsonObject().apply {
                    addProperty("statsOutboundUplink", true)
                    addProperty("statsOutboundDownlink", true)
                })
            })
        }
        return ColituVpnConfig(
            serverId = envelope.getAsJsonObject("server")?.get("id")?.asString ?: "",
            configType = "xray-mobile-v1",
            protocolType = protocol,
            rawConfig = runtime.toString(),
            expiresAt = expires.toString(),
            unlimited = false,
            revision = revision,
            offlineGraceUntil = grace.toString(),
        )
    }

    /** Human name of a panel transport for the status line. */
    fun transportName(protocol: String?): String = when (protocol) {
        "hysteria2" -> "Hysteria2"
        "vless-reality" -> "VLESS Reality"
        "trojan" -> "Trojan"
        "shadowsocks" -> "Shadowsocks"
        null -> ""
        else -> protocol
    }

    private fun vless(host: String, port: Int, c: JsonObject, t: JsonObject, s: JsonObject) = JsonObject().apply {
        addProperty("tag", "proxy")
        addProperty("protocol", "vless")
        add("settings", JsonObject().apply {
            add("vnext", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("address", host)
                    addProperty("port", port)
                    add("users", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("id", c.requiredString("uuid"))
                            addProperty("encryption", "none")
                            addProperty("flow", "xtls-rprx-vision")
                        })
                    })
                })
            })
        })
        add("streamSettings", stream(t, s))
    }

    private fun trojan(host: String, port: Int, c: JsonObject, t: JsonObject, s: JsonObject) = JsonObject().apply {
        addProperty("tag", "proxy")
        addProperty("protocol", "trojan")
        add("settings", JsonObject().apply {
            add("servers", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("address", host)
                    addProperty("port", port)
                    addProperty("password", c.requiredString("password"))
                })
            })
        })
        add("streamSettings", stream(t, s))
    }

    /** Xray's native Hysteria2 outbound (QUIC with TLS), as on iOS. */
    private fun hysteria2(host: String, port: Int, c: JsonObject, s: JsonObject) = JsonObject().apply {
        addProperty("tag", "proxy")
        addProperty("protocol", "hysteria")
        add("settings", JsonObject().apply {
            addProperty("version", 2)
            addProperty("address", host)
            addProperty("port", port)
        })
        add("streamSettings", JsonObject().apply {
            addProperty("network", "hysteria")
            addProperty("security", "tls")
            add("tlsSettings", JsonObject().apply {
                addProperty("serverName", s.requiredString("server_name"))
                add("alpn", JsonArray().apply { add("h3") })
            })
            add("hysteriaSettings", JsonObject().apply {
                addProperty("version", 2)
                addProperty("auth", c.requiredString("password"))
            })
        })
    }

    private fun shadowsocks(host: String, port: Int, c: JsonObject) = JsonObject().apply {
        addProperty("tag", "proxy")
        addProperty("protocol", "shadowsocks")
        add("settings", JsonObject().apply {
            add("servers", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("address", host)
                    addProperty("port", port)
                    addProperty("method", c.requiredString("method"))
                    addProperty("password", c.requiredString("password"))
                })
            })
        })
    }

    private fun stream(t: JsonObject, s: JsonObject) = JsonObject().apply {
        addProperty("network", t.requiredString("type"))
        val type = s.requiredString("type")
        addProperty("security", type)
        if (type == "reality") {
            add("realitySettings", JsonObject().apply {
                addProperty("serverName", s.requiredString("server_name"))
                addProperty("publicKey", s.requiredString("public_key"))
                addProperty("shortId", s.requiredString("short_id"))
                addProperty("fingerprint", s.get("fingerprint")?.asString ?: "chrome")
            })
        } else if (type == "tls") {
            // Present a Chrome TLS fingerprint (uTLS): DPI in Russia drops
            // handshakes that look like Go's default client, which made Trojan
            // time out while the same server answered normal browsers.
            add("tlsSettings", JsonObject().apply {
                addProperty("serverName", s.requiredString("server_name"))
                addProperty("fingerprint", "chrome")
                add("alpn", JsonArray().apply { add("h2"); add("http/1.1") })
            })
        }
    }

    private fun JsonObject.requiredString(key: String): String =
        takeIf { has(key) && !get(key).isJsonNull && get(key).isJsonPrimitive }?.get(key)?.asString
            ?.takeIf { it.isNotBlank() } ?: error("CONFIG_${key.uppercase()}_MISSING")
}
