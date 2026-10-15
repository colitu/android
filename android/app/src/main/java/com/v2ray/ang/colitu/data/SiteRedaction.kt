package com.v2ray.ang.colitu.data

/**
 * Masks the sites a user visited in a connection report: dial targets
 * ("tcp:host:443"), DNS lookups ("lookup host", "response for host") and URL
 * hosts. Applied on the device after the general credential redaction, so
 * sites never leave the phone. IPs become `<ip>`, names `<site>`.
 *
 * Kept: loopback, colitu.com and its subdomains, the app's own connectivity
 * probes, public resolvers, and the Colitu servers the app knows (their host
 * names, and any host under the same registrable domain, taken from the
 * server list at run time; none are hard-coded here).
 */
object SiteRedaction {
    private val dial = Regex("""\b(tcp|udp):(\[[0-9a-f:.]+\]|[a-z0-9._-]+):(\d{1,5})\b""", RegexOption.IGNORE_CASE)
    private val lookup = Regex(
        """\b(response for|lookup|resolve|resolving|query for|sniffed domain:|domain:?|domain) (\[?[a-z0-9_-]+(?:\.[a-z0-9_-]+)+\]?)\.?""",
        RegexOption.IGNORE_CASE,
    )
    private val url = Regex("""\b(https?|wss?)://([a-z0-9._-]+|\[[0-9a-f:.]+\])""", RegexOption.IGNORE_CASE)
    private val ipv4 = Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""")

    private val fileExtensions = setOf("json", "jsonl", "log", "txt", "yaml", "yml", "dat", "db", "go", "swift", "kt", "cs", "dart", "js")

    private val alwaysKept = setOf(
        "localhost", "colitu.com",
        "www.gstatic.com", "connectivitycheck.gstatic.com", "captive.apple.com", "www.apple.com", "ya.ru", "www.msftconnecttest.com",
        "8.8.8.8", "8.8.4.4", "77.88.8.8", "208.67.222.222", "1.1.1.1",
    )

    /** [knownHosts]: host names of the Colitu servers the app knows. */
    fun apply(text: String, knownHosts: Collection<String> = emptyList()): String {
        val known = knownHosts.map(::normalize).filter { it.isNotEmpty() }.toSet()
        val knownDomains = known.filter { !isIp(it) }.map(::lastTwoLabels).toSet()
        fun keep(host: String): Boolean {
            val h = normalize(host)
            return h in alwaysKept || h in known || isLoopback(h) || h.endsWith(".colitu.com") ||
                (!isIp(h) && lastTwoLabels(h) in knownDomains)
        }
        fun mask(host: String) = if (isIp(normalize(host))) "<ip>" else "<site>"

        return text
            .replace(dial) { m ->
                val host = m.groupValues[2]
                if (keep(host)) m.value else "${m.groupValues[1]}:${mask(host)}:${m.groupValues[3]}"
            }
            .replace(lookup) { m ->
                val host = m.groupValues[2]
                val extension = normalize(host).substringAfterLast('.').lowercase()
                if (keep(host) || extension in fileExtensions) m.value
                else {
                    val range = m.groups[2]!!.range
                    m.value.replaceRange(range.first - m.range.first, range.last - m.range.first + 1, mask(host))
                }
            }
            .replace(url) { m ->
                val host = m.groupValues[2]
                if (keep(host)) m.value else "${m.groupValues[1]}://${mask(host)}"
            }
    }

    private fun normalize(host: String) = host.trim().trim('[', ']').trimEnd('.').lowercase()

    private fun isIp(host: String) = ipv4.matches(host) || host.contains(':')

    private fun isLoopback(host: String) = host == "::1" || host.startsWith("127.") || host == "0.0.0.0"

    private fun lastTwoLabels(host: String): String = host.split('.').takeLast(2).joinToString(".")
}
