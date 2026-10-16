package com.v2ray.ang.colitu.api

import com.v2ray.ang.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds the app headers to every request, but the Bearer token and the device id
 * only to the Colitu API hosts ([ColituEndpoints.isApiHost]): the hosts of the
 * built-in bases, the signed endpoint list and the build's own API base. The
 * client does not follow redirects either, so the credentials cannot be carried
 * to another host by a Location header.
 */
class ColituAuthInterceptor(
    private val isApiHost: (String) -> Boolean = ColituEndpoints::isApiHost,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = ColituTokenManager.getAccessToken()
        val deviceId = ColituTokenManager.getDeviceId()

        val builder = original.newBuilder()
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("X-App-Name", "Colitu")
            .header("X-Client-Platform", "android")
            .header("X-Bundle-ID", BuildConfig.APPLICATION_ID)
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")

        if (!isApiHost(original.url.host)) return chain.proceed(builder.build())

        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        if (!deviceId.isNullOrBlank()) {
            builder.header("X-Device-ID", deviceId)
        }

        return chain.proceed(builder.build())
    }
}
