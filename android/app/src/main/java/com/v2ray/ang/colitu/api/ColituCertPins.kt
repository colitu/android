package com.v2ray.ang.colitu.api

import android.net.http.X509TrustManagerExtensions
import android.util.Log
import okhttp3.OkHttpClient
import okio.ByteString.Companion.toByteString
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Instant
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * Certificate pinning of the Colitu API client (API bases, mirror hosts, the
 * endpoint list files); never used for node connections or third-party requests.
 *
 * What is pinned is the platform-VALIDATED chain, not the chain the server
 * presented: the system trust manager builds and validates the chain (so a
 * made-up chain with an unrelated intermediate cannot carry a pin), and the
 * chain it returns must contain a certificate whose SubjectPublicKeyInfo SHA-256
 * is in [PINS] (the roots of the CAs we use: Let's Encrypt's ISRG roots, Google
 * Trust Services' R1/R4). The root is pinned, not the leaf, so certificate
 * renewals and intermediate rotations never break the app. After [EXPIRES] only
 * the normal TLS validation applies, so a forgotten pin set cannot lock users out.
 */
object ColituCertPins {
    const val ERROR_CODE = "CERT_PIN_MISMATCH"
    private const val TAG = "ColituCertPins"

    /** base64(SHA-256(SubjectPublicKeyInfo)) of the accepted roots. */
    internal val PINS: Set<String> = setOf(
        "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", // ISRG Root X1
        "diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=", // ISRG Root X2
        "sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=", // ISRG Root YE
        "fk6IOKit1ild5647BH06ujSIq5XbCgqlbYl6ANhhi88=", // ISRG Root YR
        "hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Gc=", // GTS Root R1
        "mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c=", // GTS Root R4
    )

    /** From this moment on pinning is skipped (normal TLS validation only). */
    internal val EXPIRES: Instant = Instant.parse("2027-12-31T23:59:59Z")

    /** The failure that ends a handshake with a chain that is valid but not ours. The base failover treats it like any TLS failure. */
    class PinMismatchException(val host: String?) : CertificateException(ERROR_CODE)

    /** base64(SHA-256(SubjectPublicKeyInfo DER)) of [cert]. */
    internal fun spkiPin(cert: X509Certificate): String =
        cert.publicKey.encoded.toByteString().sha256().base64()

    /**
     * True when [validatedChain] (the chain returned by the platform validation)
     * holds a pinned certificate, or when [now] is past [expires].
     */
    internal fun accepts(
        validatedChain: List<X509Certificate>,
        now: Instant = Instant.now(),
        pins: Set<String> = PINS,
        expires: Instant = EXPIRES,
    ): Boolean {
        if (now.isAfter(expires)) return true
        return validatedChain.any { spkiPin(it) in pins }
    }

    /**
     * Trust manager that validates with [validate] (the platform) and then
     * applies [accepts] to the validated chain it returns. A mismatch logs one
     * line with the host and throws [PinMismatchException].
     */
    internal class PinningTrustManager(
        private val delegate: X509TrustManager,
        private val validate: (chain: Array<X509Certificate>, authType: String, host: String?) -> List<X509Certificate>,
        private val now: () -> Instant = { Instant.now() },
        private val pins: Set<String> = PINS,
        private val expires: Instant = EXPIRES,
        private val log: (String) -> Unit = { Log.w(TAG, it) },
    ) : X509ExtendedTrustManager() {

        private fun verify(chain: Array<X509Certificate>, authType: String, host: String?) {
            val validated = validate(chain, authType, host)
            if (!accepts(validated, now(), pins, expires)) {
                log("$ERROR_CODE host=${host ?: "?"}")
                throw PinMismatchException(host)
            }
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = verify(chain, authType, null)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
            verify(chain, authType, hostOf(socket))

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
            verify(chain, authType, engine?.peerHost)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = delegate.checkClientTrusted(chain, authType)
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) = delegate.checkClientTrusted(chain, authType)
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = delegate.checkClientTrusted(chain, authType)
        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

        private fun hostOf(socket: Socket?): String? {
            val ssl = socket as? SSLSocket
            return runCatching { ssl?.handshakeSession?.peerHost }.getOrNull()
                ?: runCatching { socket?.inetAddress?.hostName }.getOrNull()
        }
    }

    private val platformTrustManager: X509TrustManager by lazy {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
            init(null as KeyStore?)
            trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }

    private val pinningTrustManager: PinningTrustManager by lazy {
        val platform = platformTrustManager
        val extensions = X509TrustManagerExtensions(platform)
        PinningTrustManager(platform, { chain, authType, host -> extensions.checkServerTrusted(chain, authType, host ?: "") })
    }

    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(pinningTrustManager), null) }
    }

    /** [builder] with the pinning trust manager; clients derived with `newBuilder()` keep it (tunnel path included). */
    fun applyTo(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        builder.sslSocketFactory(sslContext.socketFactory, pinningTrustManager)
}
