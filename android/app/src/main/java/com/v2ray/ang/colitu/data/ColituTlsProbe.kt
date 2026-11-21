package com.v2ray.ang.colitu.data

import android.net.Network
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * TLS probe of one endpoint (spec F:/DEV/wt/TLS-PROBE-SPEC.md §1): TCP connect (<= 3 s), then a
 * ClientHello and <= 2.5 s for the first server bytes. Certificates are NOT validated (the probe only
 * asks whether the server answers TLS; Reality shows the dest's certificate). Outside the tunnel, on
 * the physical network the caller passes. Where a Russian operator resets the handshake for every SNI
 * the server's IP is blocked, whatever the TCP port says.
 */
object ColituTlsProbe {
    const val CONNECT_TIMEOUT_MS = 3_000
    const val HANDSHAKE_TIMEOUT_MS = 2_500

    enum class Result(val wire: String) {
        Ok("ok"), TlsRst("tls_rst"), Timeout("timeout"), TcpFail("tcp_fail");

        companion object {
            fun of(wire: String?): Result? = entries.firstOrNull { it.wire == wire }
        }
    }

    data class Target(val host: String, val port: Int, val sni: String)

    /** [ms]: time to the first server bytes for [Result.Ok], else the time spent. */
    data class Outcome(val target: Target, val result: Result, val ms: Long)

    /** The two network steps, replaceable in tests. */
    interface Io {
        /** Connected plain socket, or an exception (any: [Result.TcpFail]). */
        fun connect(target: Target, timeoutMs: Int): Socket

        /** The TLS handshake on [socket]; throws whatever the stack throws. */
        fun handshake(socket: Socket, target: Target, readTimeoutMs: Int)
    }

    /** Wire of the probe event's `reason` (spec §4); "" for ok. */
    fun reasonOf(result: Result): String = when (result) {
        Result.Ok -> ""
        Result.TlsRst -> "tls_rst"
        Result.Timeout -> "timeout"
        Result.TcpFail -> "refused"
    }

    private val RESET_HINTS = listOf(
        "reset", "terminated", "closed", "unexpected end", "eof", "broken pipe", "i/o error during system call",
        "end of stream", "peer closed", "0 bytes",
    )

    /**
     * Class of an exception thrown AFTER the TCP connection was up: a timeout anywhere in the chain is
     * [Result.Timeout]; a reset / EOF / "terminated" is [Result.TlsRst]; any other TLS exception
     * (certificate, alert, protocol) means the server sent TLS bytes: [Result.Ok]. Anything else
     * cannot be told and counts as [Result.Timeout] (never a block verdict).
     */
    fun classifyHandshakeError(error: Throwable): Result {
        val chain = generateSequence(error) { it.cause?.takeIf { c -> c !== it } }.take(8).toList()
        if (chain.any { it is SocketTimeoutException }) return Result.Timeout
        if (chain.any { it is EOFException || (it.message?.lowercase()?.let { m -> RESET_HINTS.any(m::contains) } == true) }) {
            return Result.TlsRst
        }
        if (chain.any { it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException }) return Result.Ok
        return Result.Timeout
    }

    /** Public hosts only: no loopback, private or link-local literal (no LAN scan from the phone). */
    fun hostAllowed(host: String, port: Int): Boolean {
        if (port !in 1..65535) return false
        val h = host.trim()
        if (h.isEmpty() || h.length > 253 || h.equals("localhost", ignoreCase = true) || h.endsWith(".localhost", ignoreCase = true)) return false
        val literal = ColituAddressRanges.parseLiteral(h.removePrefix("[").removeSuffix("]"))
        return literal == null || !ColituAddressRanges.isNonPublic(literal)
    }

    /** One probe; never throws. */
    suspend fun probe(target: Target, io: Io): Outcome = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        if (!hostAllowed(target.host, target.port)) return@withContext Outcome(target, Result.TcpFail, 0)
        val socket = try {
            io.connect(target, CONNECT_TIMEOUT_MS)
        } catch (_: Exception) {
            return@withContext Outcome(target, Result.TcpFail, elapsed())
        }
        try {
            io.handshake(socket, target, HANDSHAKE_TIMEOUT_MS)
            Outcome(target, Result.Ok, elapsed())
        } catch (e: Throwable) {
            val result = classifyHandshakeError(e)
            Outcome(target, result, elapsed())
        } finally {
            runCatching { socket.close() }
        }
    }

    /** All [targets] at the same time, [budgetMs] at most for the whole run (late ones are dropped). */
    suspend fun probeAll(targets: List<Target>, io: Io, budgetMs: Long): List<Outcome> = coroutineScope {
        val deferred = targets.map { async { runCatching { kotlinx.coroutines.withTimeoutOrNull(budgetMs) { probe(it, io) } }.getOrNull() } }
        deferred.awaitAll().filterNotNull()
    }

    /** The real thing: sockets of [network] (null: the default route), trust-all TLS with the target's SNI. */
    fun androidIo(network: Network?): Io = object : Io {
        override fun connect(target: Target, timeoutMs: Int): Socket {
            val resolved = network?.getByName(target.host) ?: java.net.InetAddress.getByName(target.host)
            if (ColituAddressRanges.isNonPublic(resolved)) throw java.io.IOException("non-public address")
            val socket = network?.socketFactory?.createSocket() ?: Socket()
            try {
                socket.connect(InetSocketAddress(resolved, target.port), timeoutMs)
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
            return socket
        }

        override fun handshake(socket: Socket, target: Target, readTimeoutMs: Int) {
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(TrustAll), null)
            socket.soTimeout = readTimeoutMs
            val tls = context.socketFactory.createSocket(socket, target.sni.ifBlank { target.host }, target.port, true) as SSLSocket
            val params = tls.sslParameters
            val name = target.sni.ifBlank { target.host }
            if (ColituAddressRanges.parseLiteral(name.removePrefix("[").removeSuffix("]")) == null) {
                params.serverNames = listOf(SNIHostName(name))
            }
            tls.sslParameters = params
            tls.soTimeout = readTimeoutMs
            tls.startHandshake()
        }
    }

    /** Probe only: the answer matters, not who gave it. */
    @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
    }
}
