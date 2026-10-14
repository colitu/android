package com.v2ray.ang.colitu.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.InputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import org.junit.Test

class ColituCertPinsTest {
    private val before = Instant.parse("2026-10-10T00:00:00Z")
    private val after = Instant.parse("2028-01-01T00:00:00Z")

    private fun cert(name: String): X509Certificate {
        val stream: InputStream = javaClass.classLoader!!.getResourceAsStream("certpins/$name.pem")!!
        return stream.use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
    }

    private val pinnedRoots = listOf("isrg-root-x1", "isrg-root-x2", "isrg-root-ye", "isrg-root-yr", "gts-root-r1", "gts-root-r4")

    @Test
    fun everyPinnedRootMatchesItsPin() {
        for (name in pinnedRoots) {
            assertTrue("$name is in the pin set", ColituCertPins.spkiPin(cert(name)) in ColituCertPins.PINS)
            assertTrue(ColituCertPins.accepts(listOf(cert(name)), before))
        }
        assertEquals(pinnedRoots.size, ColituCertPins.PINS.size)
    }

    @Test
    fun pinnedRootAtTheEndOfAChainPasses() {
        // leaf-like certificate first, the pinned root last (what the platform returns)
        assertTrue(ColituCertPins.accepts(listOf(cert("self-made-root"), cert("isrg-root-x1")), before))
    }

    @Test
    fun selfMadeRootFails() {
        assertFalse(ColituCertPins.accepts(listOf(cert("self-made-root")), before))
    }

    @Test
    fun chainWithoutAPinnedRootFails() {
        assertFalse(ColituCertPins.accepts(emptyList(), before))
        assertFalse(ColituCertPins.accepts(listOf(cert("self-made-root"), cert("self-made-root")), before))
    }

    @Test
    fun pinningIsSkippedAfterExpiry() {
        assertTrue(ColituCertPins.accepts(listOf(cert("self-made-root")), after))
        assertTrue(ColituCertPins.accepts(emptyList(), after))
        // the expiry moment itself still pins
        assertFalse(ColituCertPins.accepts(listOf(cert("self-made-root")), ColituCertPins.EXPIRES))
        assertTrue(ColituCertPins.EXPIRES == Instant.parse("2027-12-31T23:59:59Z"))
    }

    // The trust manager pins what the platform VALIDATED, never what the server presented.

    private fun manager(validated: List<X509Certificate>, now: Instant, log: MutableList<String>) =
        ColituCertPins.PinningTrustManager(
            delegate = NoopTrustManager,
            validate = { _, _, _ -> validated },
            now = { now },
            log = { log += it },
        )

    @Test
    fun trustManagerUsesTheValidatedChainNotThePresentedOne() {
        val log = mutableListOf<String>()
        // Presented chain holds a pinned root, but the platform validated a different chain: refused.
        try {
            manager(listOf(cert("self-made-root")), before, log)
                .checkServerTrusted(arrayOf(cert("isrg-root-x1")), "RSA", null as java.net.Socket?)
            fail("expected a pin mismatch")
        } catch (e: ColituCertPins.PinMismatchException) {
            assertEquals(ColituCertPins.ERROR_CODE, e.message)
        }
        assertEquals(1, log.size)
        assertTrue(log[0].startsWith("CERT_PIN_MISMATCH host="))
        // The other way round: presented chain is foreign, validated chain is pinned: accepted.
        manager(listOf(cert("isrg-root-x2")), before, mutableListOf())
            .checkServerTrusted(arrayOf(cert("self-made-root")), "RSA")
    }

    @Test
    fun trustManagerPassesPlatformRejectionsThrough() {
        val tm = ColituCertPins.PinningTrustManager(
            delegate = NoopTrustManager,
            validate = { _, _, _ -> throw CertificateException("not trusted by the platform") },
            now = { before },
            log = { },
        )
        try {
            tm.checkServerTrusted(arrayOf(cert("isrg-root-x1")), "RSA")
            fail("expected the platform failure")
        } catch (e: CertificateException) {
            assertEquals("not trusted by the platform", e.message)
        }
    }

    @Test
    fun trustManagerSkipsPinsAfterExpiry() {
        manager(listOf(cert("self-made-root")), after, mutableListOf()).checkServerTrusted(arrayOf(cert("self-made-root")), "RSA")
    }

    private object NoopTrustManager : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** The updater (HttpURLConnection) is pinned by the manifest's network config: same roots, same end date. */
    @Test
    fun networkSecurityConfigMatchesThePinSet() {
        val xml = java.io.File("src/main/res/xml/network_security_config.xml").readText()
        val pinSet = xml.substringAfter("<pin-set").substringBefore("</pin-set>")
        val pins = Regex("""<pin digest="SHA-256">([^<]+)</pin>""").findAll(pinSet).map { it.groupValues[1] }.toSet()
        assertEquals(ColituCertPins.PINS, pins)
        val expiration = Regex("""expiration="([0-9-]+)"""").find(pinSet)!!.groupValues[1]
        assertEquals(ColituCertPins.EXPIRES.toString().substring(0, 10), expiration)
    }

    /**
     * The bundled trust anchor (Android 7.0 lacks it in the system store) is
     * the self-signed ISRG Root X1 whose key is pinned, and both trust-anchor
     * lists that need it name it next to the system store.
     */
    @Test
    fun bundledRootIsTheX1OfThePinSet() {
        val cert = java.io.File("src/main/res/raw/isrg_root_x1.pem").inputStream().use {
            java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        cert.verify(cert.publicKey)
        assertTrue(cert.subjectX500Principal.name.contains("CN=ISRG Root X1"))
        val spki = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded))
        assertEquals("C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", spki)
        assertTrue(spki in ColituCertPins.PINS)
        val xml = java.io.File("src/main/res/xml/network_security_config.xml").readText()
        val anchors = Regex("""<trust-anchors>(.*?)</trust-anchors>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(2, anchors.size)
        anchors.forEach {
            assertTrue(it.contains("""src="system""""))
            assertTrue(it.contains("""src="@raw/isrg_root_x1""""))
        }
    }
}
