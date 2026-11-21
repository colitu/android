package com.v2ray.ang.colitu.data

/**
 * The connection method picked on the home screen (advanced mode), like the
 * protocol chip on Windows: "" is automatic. The picked transport goes first
 * on every server that offers it; the automatic order stays behind it as the
 * fallback.
 */
object ColituTransportChoice {
    /** The methods the menu offers, in menu order. */
    val METHODS = listOf("hysteria2", "vless-reality", "vless-xhttp", "trojan", "shadowsocks")

    /** ColituGecko: listed after [METHODS] while the panel offers it; a pick of it connects Gecko-only (no server's transport). */
    const val GECKO = ColituGeckoRules.PROTOCOL

    /** A known method (or [GECKO]) in its canonical spelling, or "" (automatic) for anything else. */
    fun normalize(protocol: String?): String {
        val trimmed = protocol?.trim().orEmpty()
        return (METHODS + GECKO).firstOrNull { it.equals(trimmed, ignoreCase = true) }.orEmpty()
    }

    /** The menu's options: automatic, the server transports, then ColituGecko when [geckoListed]. */
    fun options(geckoListed: Boolean): List<String> = listOf("") + METHODS + if (geckoListed) listOf(GECKO) else emptyList()

    /**
     * [ordered] with the items of [picked] moved to the front, the rest in
     * their order. Unchanged when nothing is picked, the method is not
     * offered here, or it already stalled during this connect ([skip]): a
     * recovery then moves on instead of starting the stalled method again.
     */
    fun <T> putFirst(ordered: List<T>, protocolOf: (T) -> String?, picked: String?, skip: Set<String> = emptySet()): List<T> {
        val method = normalize(picked)
        if (method.isEmpty() || method in skip) return ordered
        val first = ordered.filter { protocolOf(it).equals(method, ignoreCase = true) }
        return if (first.isEmpty()) ordered else first + ordered.filter { it !in first }
    }
}
