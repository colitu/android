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

    /** A known method in its canonical spelling, or "" (automatic) for anything else. */
    fun normalize(protocol: String?): String {
        val trimmed = protocol?.trim().orEmpty()
        return METHODS.firstOrNull { it.equals(trimmed, ignoreCase = true) }.orEmpty()
    }

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
