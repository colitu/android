package com.v2ray.ang.colitu.api

import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.internal.tls.OkHostnameVerifier
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier

/**
 * The client for a request tagged with an [ApiRelay] (see [ColituEndpoints.requestFor]).
 *
 * The request is built for `https://api.colitu.com`, so every interceptor of the
 * base client (auth, language) sees the API host. The last interceptor then points
 * it at the relay: URL host = the relay's SNI (so TLS sends that server name on
 * every Android version), port = the relay's port, and `Host: api.colitu.com`.
 * The SNI resolves to the relay's address, the certificate must be valid for
 * [ApiRelay.verify] (api.colitu.com), the pinning trust manager of the base client
 * stays, and HTTP/1.1 is used so the Host header is what the server routes on.
 */
internal object ColituRelayTransport {
    const val API_HOST = "api.colitu.com"

    private class Parts(relay: ApiRelay, resolve: (String) -> List<InetAddress>) {
        val dns = Dns { name ->
            if (!name.equals(relay.sni, ignoreCase = true)) throw UnknownHostException("not a relay name")
            resolve(relay.host)
        }
        val verifier = HostnameVerifier { _, session -> OkHostnameVerifier.verify(relay.verify, session) }
        val interceptor = Interceptor { chain -> rewrite(chain, relay) }
    }

    private val parts = ConcurrentHashMap<Pair<ApiRelay, (String) -> List<InetAddress>>, Parts>()
    private val systemResolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }

    /** [base] (timeouts, pinning, interceptors) pointed at [relay]. Same relay → same Dns/verifier, so connections are reused. */
    fun client(
        base: OkHttpClient,
        relay: ApiRelay,
        resolve: (String) -> List<InetAddress> = systemResolve,
    ): OkHttpClient {
        val p = parts.getOrPut(relay to resolve) { Parts(relay, resolve) }
        return base.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .dns(p.dns)
            .hostnameVerifier(p.verifier)
            .addInterceptor(p.interceptor)
            .build()
    }

    private fun rewrite(chain: Interceptor.Chain, relay: ApiRelay): Response {
        val request = chain.request()
        if (!request.url.host.equals(API_HOST, ignoreCase = true) || !request.isHttps) throw IOException("relay request for another host")
        val url = request.url.newBuilder().host(relay.sni).port(relay.port).build()
        return chain.proceed(request.newBuilder().url(url).header("Host", API_HOST).build())
    }
}
