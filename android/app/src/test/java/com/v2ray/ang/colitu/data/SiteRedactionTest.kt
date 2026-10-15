package com.v2ray.ang.colitu.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SiteRedactionTest {
    // Example names only; real node domains never appear in the repo.
    private val known = listOf("node1.vpn-example.net")

    private fun apply(text: String) = SiteRedaction.apply(text, known)

    @Test
    fun dialTargetsAreMasked() {
        assertEquals("dial tcp:<site>:443 ok", apply("dial tcp:www.example.org:443 ok"))
        assertEquals("udp:<ip>:53", apply("udp:93.184.216.34:53"))
        assertEquals("tcp:<ip>:443", apply("tcp:[2606:4700::1111]:443"))
        assertEquals("TCP:<site>:80", apply("TCP:Example.COM:80"))
    }

    @Test
    fun lookupsAreMaskedExceptFilesAndDotless() {
        assertEquals("lookup <site>", apply("lookup www.example.org"))
        assertEquals("response for <site>.", apply("response for www.example.org."))
        assertEquals("sniffed domain: <site>", apply("sniffed domain: video.example.org"))
        assertEquals("resolving <ip>", apply("resolving 93.184.216.34"))
        assertEquals("domain <site>", apply("domain Mail.Example.org"))
        assertEquals("lookup config.json", apply("lookup config.json"))
        assertEquals("query for server.log", apply("query for server.log"))
        assertEquals("lookup localhost", apply("lookup localhost"))
    }

    @Test
    fun urlHostsAreMasked() {
        assertEquals("GET https://<site>/path?q=1", apply("GET https://www.example.org/path?q=1"))
        assertEquals("wss://<site>:8443/x", apply("wss://chat.example.org:8443/x"))
        assertEquals("http://<ip>/", apply("http://93.184.216.34/"))
        assertEquals("https://<ip>/", apply("https://[2606:4700::1111]/"))
    }

    @Test
    fun keptHostsStayAsTheyAre() {
        val lines = listOf(
            "tcp:colitu.com:443", "https://api.colitu.com/v1/me", "lookup lab.colitu.com",
            "tcp:127.0.0.1:10808", "http://localhost:8080/", "tcp:[::1]:1080",
            "https://www.gstatic.com/generate_204", "http://connectivitycheck.gstatic.com/generate_204",
            "http://captive.apple.com/", "https://www.apple.com/library/test/success.html", "https://ya.ru/",
            "http://www.msftconnecttest.com/connecttest.txt",
            "tcp:8.8.8.8:53", "udp:8.8.4.4:53", "udp:77.88.8.8:53", "udp:208.67.222.222:53", "udp:1.1.1.1:53",
        )
        lines.forEach { assertEquals(it, apply(it)) }
    }

    @Test
    fun knownServersAndTheirDomainAreKept() {
        assertEquals("tcp:node1.vpn-example.net:443", apply("tcp:node1.vpn-example.net:443"))
        assertEquals("lookup node2.vpn-example.net", apply("lookup node2.vpn-example.net"))
        assertEquals("https://edge.vpn-example.net/health", apply("https://edge.vpn-example.net/health"))
        assertEquals("tcp:<site>:443", apply("tcp:vpn-example.org:443"))
        // Without the server list nothing is kept implicitly.
        assertEquals("lookup <site>", SiteRedaction.apply("lookup node1.vpn-example.net"))
    }

    @Test
    fun textWithoutSitesIsUntouched() {
        val text = "===== Device =====\nDevice: Pixel 8\nVersion: 2.8.6 (123)\nconnected=true"
        assertEquals(text, apply(text))
    }
}
