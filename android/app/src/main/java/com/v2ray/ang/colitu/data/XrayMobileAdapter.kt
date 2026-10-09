package com.v2ray.ang.colitu.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.colitu.api.ColituClock
import com.v2ray.ang.colitu.api.LocalProxy
import com.v2ray.ang.colitu.l10n.ColituLoc
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
        "vless-xhttp" to 2,
        "trojan" to 3,
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
        val revision = revisionOf(envelope)
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
            when (protocol) {
                "hysteria2" -> transportType == "hysteria"
                "vless-xhttp" -> transportType == "xhttp" && transport.requiredString("path").startsWith("/")
                else -> transportType in setOf("tcp", "ws", "grpc")
            },
        ) { "CONFIG_TRANSPORT_UNSUPPORTED" }
        require(
            when (protocol) {
                "vless-reality", "vless-xhttp" -> security.requiredString("type") == "reality"
                "trojan", "hysteria2" -> security.requiredString("type") == "tls"
                else -> security.requiredString("type") == "none"
            },
        ) { "CONFIG_SECURITY_INVALID" }
        val outbound = when (protocol) {
            "vless-reality", "vless-xhttp" -> vless(host, port, credentials, transport, security)
            "trojan" -> trojan(host, port, credentials, transport, security)
            "hysteria2" -> hysteria2(host, port, credentials, transport, security)
            else -> shadowsocks(host, port, credentials)
        }
        val runtime = JsonObject().apply {
            // No access log: it writes every visited host to logcat.
            add("log", JsonObject().apply {
                addProperty("loglevel", "warning")
                addProperty("access", "none")
                addProperty("dnsLog", false)
            })
            // Placeholder account; [withLocalProxy] puts the connection's own
            // port and account in before the profile is imported. The inbound
            // never listens without a password.
            add("inbounds", JsonArray().apply { add(socksInbound(LocalProxy(0, randomToken(), randomToken()))) })
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
            serverCountry = envelope.getAsJsonObject("server")?.get("country")
                ?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
            configType = "xray-mobile-v1",
            protocolType = protocol,
            rawConfig = runtime.toString(),
            expiresAt = expires.toString(),
            unlimited = false,
            revision = revision,
            offlineGraceUntil = grace.toString(),
        )
    }

    /**
     * [raw] with its SOCKS inbound replaced by one on [proxy]'s port that
     * accepts only [proxy]'s account (hev-socks5-tunnel and the API client
     * log in with it). Also used on the stored profile before every core
     * start (ColituVpnRepository.renewLocalProxy).
     */
    fun withLocalProxy(raw: String, proxy: LocalProxy): String {
        val json = com.google.gson.JsonParser.parseString(raw).asJsonObject
        val inbounds = JsonArray()
        var sniffing: com.google.gson.JsonElement? = null
        json.get("inbounds")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { inbound ->
            val isSocks = inbound.isJsonObject && inbound.asJsonObject.get("protocol")?.takeIf { it.isJsonPrimitive }?.asString == "socks"
            if (!isSocks) inbounds.add(inbound) else if (sniffing == null) sniffing = inbound.asJsonObject.get("sniffing")
        }
        // RuBypass/SplitTunnel add sniffing to this inbound; a renewal at start must keep it.
        inbounds.add(socksInbound(proxy).apply { sniffing?.let { add("sniffing", it) } })
        json.add("inbounds", inbounds)
        return json.toString()
    }

    /** Inbound and rule tag of the connect-time traffic check. */
    const val VERIFY_TAG = "colitu-verify"

    /**
     * [raw] with the traffic-check inbound on [verify]'s port (HTTP proxy,
     * loopback, [verify]'s account only) and, first of all rules, the rule
     * that sends it straight to the primary outbound `proxy`. The check then
     * tests the primary alone: the warm spare cannot hide a dead primary.
     * Runs last, before every core start, so no layer's rule gets in front.
     */
    /** Inbound and rule tag of the warm spare's own check (to `warm-spare` only). */
    const val VERIFY_SPARE_TAG = "colitu-verify-spare"

    /**
     * Also, when [raw] carries a warm spare and [verifySpare] is given, a
     * second check inbound ([VERIFY_SPARE_TAG]) routed straight to the
     * spare, so the spare can be checked alone (parallel connect, spare
     * health probe). Without a spare that inbound and its rule are removed:
     * a rule to a missing outbound would stop the core from starting.
     */
    fun withVerifyInbound(raw: String, verify: LocalProxy, verifySpare: LocalProxy? = null): String {
        val json = com.google.gson.JsonParser.parseString(raw).asJsonObject
        val hasSpare = json.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray?.any {
            it.isJsonObject && it.asJsonObject.get("tag")?.takeIf { t -> t.isJsonPrimitive }?.asString == ColituWarmSpare.SPARE_TAG
        } == true
        val spareCheck = verifySpare?.takeIf { hasSpare }
        val inbounds = JsonArray()
        json.get("inbounds")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { inbound ->
            val tag = inbound.takeIf { it.isJsonObject }?.asJsonObject?.get("tag")?.takeIf { it.isJsonPrimitive }?.asString
            if (tag != VERIFY_TAG && tag != VERIFY_SPARE_TAG) inbounds.add(inbound)
        }
        inbounds.add(checkInbound(VERIFY_TAG, verify))
        spareCheck?.let { inbounds.add(checkInbound(VERIFY_SPARE_TAG, it)) }
        json.add("inbounds", inbounds)
        val routing = json.get("routing")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val rules = JsonArray().apply {
            add(checkRule(VERIFY_TAG, "proxy"))
            if (spareCheck != null) add(checkRule(VERIFY_SPARE_TAG, ColituWarmSpare.SPARE_TAG))
            routing.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.filterNot {
                    val tag = it.takeIf { r -> r.isJsonObject }?.asJsonObject?.get("ruleTag")?.takeIf { t -> t.isJsonPrimitive }?.asString
                    tag == VERIFY_TAG || tag == VERIFY_SPARE_TAG
                }
                ?.forEach(::add)
        }
        routing.add("rules", rules)
        json.add("routing", routing)
        return json.toString()
    }

    private fun checkInbound(tag: String, proxy: LocalProxy) = JsonObject().apply {
        addProperty("tag", tag)
        addProperty("listen", "127.0.0.1")
        addProperty("port", proxy.port)
        addProperty("protocol", "http")
        add("settings", JsonObject().apply {
            add("accounts", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("user", proxy.user)
                    addProperty("pass", proxy.password)
                })
            })
            addProperty("allowTransparent", false)
        })
    }

    private fun checkRule(tag: String, outbound: String) = JsonObject().apply {
        addProperty("type", "field")
        add("inboundTag", JsonArray().apply { add(tag) })
        addProperty("outboundTag", outbound)
        addProperty("ruleTag", tag)
    }

    private fun socksInbound(proxy: LocalProxy) = JsonObject().apply {
        addProperty("tag", "socks")
        addProperty("listen", "127.0.0.1")
        addProperty("port", proxy.port)
        addProperty("protocol", "socks")
        add("settings", JsonObject().apply {
            addProperty("auth", "password")
            add("accounts", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("user", proxy.user)
                    addProperty("pass", proxy.password)
                })
            })
            addProperty("udp", true)
            addProperty("ip", "127.0.0.1")
        })
    }

    private fun randomToken(): String =
        ByteArray(16).also(java.security.SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    /**
     * The panel's revision is an unsigned 64-bit hash prefix, so half of all
     * values are above Long.MAX_VALUE. Reading it with asLong wrapped those to
     * negative numbers and rejected every such profile as
     * CONFIG_REVISION_INVALID (the "engine could not start" error on some
     * servers, accounts and TVs). The value is kept as the same 64 bits; only
     * zero means "no revision".
     */
    internal fun revisionOf(envelope: JsonObject): Long {
        val value = envelope.get("revision")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
            ?: throw IllegalArgumentException("CONFIG_REVISION_MISSING")
        val number = value.toBigIntegerOrNull() ?: throw IllegalArgumentException("CONFIG_REVISION_INVALID")
        require(number.signum() > 0 && number.bitLength() <= 64) { "CONFIG_REVISION_INVALID" }
        return number.toLong()
    }

    /**
     * Colitu's own name of a panel transport for the screens ("Fast" for
     * Hysteria2 and so on): the technical protocol names stay out of the UI.
     */
    fun transportName(protocol: String?): String = when (protocol) {
        null, "" -> ""
        in transportRank.keys -> ColituLoc["transport.$protocol"]
        else -> ColituLoc["transport.other"]
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
                            // Vision only works on raw TCP; ws/grpc must go without a flow.
                            if (t.requiredString("type") == "tcp") addProperty("flow", "xtls-rprx-vision")
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
    private fun hysteria2(host: String, port: Int, c: JsonObject, t: JsonObject, s: JsonObject) = JsonObject().apply {
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
            // Port hopping: Russian mobile networks throttle one long-lived
            // UDP flow, so the client moves to another port of the range every
            // few seconds. Xray 26 reads it only from finalmask.quicParams
            // (hysteriaSettings.udphop is silently ignored). settings.port
            // stays the endpoint port.
            hopPorts(t)?.let { ports ->
                add("finalmask", JsonObject().apply {
                    add("quicParams", JsonObject().apply {
                        add("udpHop", JsonObject().apply {
                            addProperty("ports", ports)
                            addProperty("interval", hopInterval(t).toString())
                        })
                    })
                })
            }
        })
    }

    private val hopPortsPattern = Regex("""^(\d{4,5})-(\d{4,5})$""")

    /** The panel's "hop_ports" range ("20000-40000"), or null when absent or malformed. */
    internal fun hopPorts(t: JsonObject): String? {
        val raw = t.get("hop_ports")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
        val match = hopPortsPattern.matchEntire(raw) ?: return null
        val from = match.groupValues[1].toInt()
        val to = match.groupValues[2].toInt()
        if (from !in 1..65535 || to !in 1..65535 || from > to) return null
        return raw
    }

    /** Seconds between hops; the panel's "hop_interval", 30 when absent or out of range. */
    internal fun hopInterval(t: JsonObject): Int {
        val value = t.get("hop_interval")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
        return value?.takeIf { it in 5..600 } ?: 30
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
        if (t.requiredString("type") == "xhttp") {
            add("xhttpSettings", JsonObject().apply {
                addProperty("path", t.requiredString("path"))
                addProperty("mode", t.get("mode")?.takeIf { it.isJsonPrimitive }?.asString ?: "auto")
            })
        }
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
