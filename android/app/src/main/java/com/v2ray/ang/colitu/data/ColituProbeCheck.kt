package com.v2ray.ang.colitu.data

/**
 * Whether a connectivity check answer really came from the check page through the
 * tunnel. A status code alone is not enough: a server whose proxy refused us (an
 * empty user list, a Reality fallback to its cover site, a captive portal) can still
 * answer something with 200. Each check page has a fixed answer, and only that counts.
 */
object ColituProbeCheck {
    /** At most this much of a check answer is read. */
    const val MAX_BODY_BYTES = 4096

    fun accepts(url: String, code: Int, body: String): Boolean = when {
        url.endsWith("/generate_204") -> code == 204 && body.isEmpty()
        url.endsWith("/library/test/success.html") -> code == 200 && body.contains("<TITLE>Success</TITLE>", ignoreCase = true)
        url.endsWith("/connecttest.txt") -> code == 200 && body.trim() == "Microsoft Connect Test"
        // A page without a known answer never proves the tunnel.
        else -> false
    }
}
