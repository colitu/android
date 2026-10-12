package com.v2ray.ang.colitu.data

import java.net.InetAddress

/**
 * IP literal parsing and the non-public address ranges (loopback, private,
 * link-local, CGNAT, unique-local). Never does a DNS lookup: only literals are
 * parsed. Pure, no Android types.
 */
internal object ColituAddressRanges {
    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    private val IPV6 = Regex("""^[0-9a-fA-F:.]+$""")

    private class Range(val base: ByteArray, val prefix: Int)

    private fun range(literal: String, prefix: Int) = Range(requireNotNull(parseLiteral(literal)).address, prefix)

    /**
     * Loopback, RFC 1918, link-local, CGNAT (RFC 6598), "this network",
     * IETF protocol assignments / benchmarking / reserved, IPv6
     * loopback/ULA/link-local, NAT64 well-known prefix and 6to4.
     */
    private val NON_PUBLIC: List<Range> by lazy {
        listOf(
            range("0.0.0.0", 8),
            range("10.0.0.0", 8),
            range("100.64.0.0", 10),
            range("127.0.0.0", 8),
            range("169.254.0.0", 16),
            range("172.16.0.0", 12),
            range("192.0.0.0", 24),
            range("192.168.0.0", 16),
            range("198.18.0.0", 15),
            range("240.0.0.0", 4),
            range("::", 128),
            range("::1", 128),
            range("64:ff9b::", 96),
            range("2002::", 16),
            range("fc00::", 7),
            range("fe80::", 10),
        )
    }

    /** The address of an IPv4 or IPv6 literal, or null for anything else (host names included). */
    fun parseLiteral(value: String): InetAddress? {
        val v = value.trim()
        IPV4.matchEntire(v)?.let { match ->
            val parts = match.groupValues.drop(1).map { it.toInt() }
            if (parts.any { it > 255 }) return null
            return InetAddress.getByAddress(ByteArray(4) { parts[it].toByte() })
        }
        // Only hex digits, colons and dots: InetAddress parses it as a literal, no lookup.
        if (':' !in v || !IPV6.matches(v) || v.length > 45) return null
        return runCatching { InetAddress.getByName(v) }.getOrNull()
    }

    fun isIpLiteral(value: String): Boolean = parseLiteral(value) != null

    /** True when [address] is not reachable on the public internet (see [NON_PUBLIC], plus multicast). */
    fun isNonPublic(address: InetAddress): Boolean =
        address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress ||
            NON_PUBLIC.any { contains(it, address.address, it.prefix) }

    /**
     * True when [cidr] ("10.0.0.0/8", "192.168.1.1", "fc00::/7") lies wholly
     * inside a non-public range. A wider prefix ("10.0.0.0/1") is refused.
     */
    fun isNonPublicCidr(cidr: String): Boolean {
        val text = cidr.trim()
        val slash = text.indexOf('/')
        val address = parseLiteral(if (slash < 0) text else text.substring(0, slash)) ?: return false
        val bits = address.address.size * 8
        val prefix = if (slash < 0) bits else text.substring(slash + 1).toIntOrNull() ?: return false
        if (prefix !in 0..bits) return false
        val bytes = address.address
        return NON_PUBLIC.any { it.base.size == bytes.size && prefix >= it.prefix && contains(it, bytes, it.prefix) }
    }

    private fun contains(range: Range, bytes: ByteArray, prefix: Int): Boolean {
        if (range.base.size != bytes.size) return false
        var left = prefix
        var i = 0
        while (left > 0) {
            val mask = if (left >= 8) 0xFF else (0xFF shl (8 - left)) and 0xFF
            if ((range.base[i].toInt() and mask) != (bytes[i].toInt() and mask)) return false
            left -= 8
            i++
        }
        return true
    }
}
