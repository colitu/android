package com.v2ray.ang.colitu.repository

/** Offline grace never overrides an explicit control-plane denial. */
internal object ConfigCachePolicy {
    fun allowsFallback(statusCode: Int, isAuthError: Boolean): Boolean =
        !isAuthError && statusCode in setOf(-1, 502, 503, 504)
}
