package com.v2ray.ang.colitu.api

import com.v2ray.ang.colitu.l10n.ColituLoc
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Sends the app language ("ru", "tr", "en") as Accept-Language with every API
 * request, so the panel can keep the account's language (users.locale) and
 * write its notifications and e-mails in it. Only the language the user picked
 * in the app, never the phone's full locale list.
 */
class ColituLanguageInterceptor(private val language: () -> String = { ColituLoc.language }) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val value = headerValue(runCatching(language).getOrNull())
            ?: return chain.proceed(chain.request())
        return chain.proceed(chain.request().newBuilder().header("Accept-Language", value).build())
    }

    companion object {
        private val CODE = Regex("^[a-z]{2}$")

        /** The language code as header value; null for anything that is not a plain two-letter code. */
        fun headerValue(language: String?): String? = language?.trim()?.lowercase()?.takeIf { CODE.matches(it) }
    }
}
