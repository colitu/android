package com.v2ray.ang.colitu.api

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColituLanguageInterceptorTest {
    /** Runs one request through [interceptor] and returns the request the network would have seen (no network used). */
    private fun sent(interceptor: ColituLanguageInterceptor, request: Request = Request.Builder().url("https://api.example.test/api/v1/client/bootstrap").build()): Request {
        var seen: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor { chain ->
                seen = chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("{}".toResponseBody()).build()
            }
            .build()
        client.newCall(request).execute().close()
        return seen!!
    }

    @Test
    fun everyRequestCarriesTheAppLanguage() {
        assertEquals("tr", sent(ColituLanguageInterceptor { "tr" }).header("Accept-Language"))
        assertEquals("ru", sent(ColituLanguageInterceptor { " RU " }).header("Accept-Language"))
        // The app's choice replaces whatever the platform would send.
        val withPhoneLocale = Request.Builder().url("https://api.example.test/x").header("Accept-Language", "de-DE,de;q=0.9").build()
        assertEquals("en", sent(ColituLanguageInterceptor { "en" }, withPhoneLocale).header("Accept-Language"))
    }

    @Test
    fun followsALanguageChangeAtOnce() {
        var language = "en"
        val interceptor = ColituLanguageInterceptor { language }
        assertEquals("en", sent(interceptor).header("Accept-Language"))
        language = "ru"
        assertEquals("ru", sent(interceptor).header("Accept-Language"))
    }

    @Test
    fun onlyPlainTwoLetterCodes() {
        assertNull(sent(ColituLanguageInterceptor { "tr\r\nX-Evil: 1" }).header("Accept-Language"))
        assertNull(ColituLanguageInterceptor.headerValue(""))
        assertNull(ColituLanguageInterceptor.headerValue("tur"))
        assertNull(ColituLanguageInterceptor.headerValue(null))
        assertEquals("tr", ColituLanguageInterceptor.headerValue("TR"))
    }
}
