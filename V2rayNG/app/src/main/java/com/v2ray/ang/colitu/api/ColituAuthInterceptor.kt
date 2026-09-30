package com.v2ray.ang.colitu.api

import com.v2ray.ang.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

class ColituAuthInterceptor : Interceptor {
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

        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        if (!deviceId.isNullOrBlank()) {
            builder.header("X-Device-ID", deviceId)
        }

        return chain.proceed(builder.build())
    }
}
