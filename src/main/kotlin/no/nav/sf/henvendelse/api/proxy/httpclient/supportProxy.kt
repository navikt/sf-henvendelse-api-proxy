package no.nav.sf.henvendelse.api.proxy.httpclient

import mu.KotlinLogging
import no.nav.sf.henvendelse.api.proxy.env
import no.nav.sf.henvendelse.api.proxy.env_HTTPS_PROXY
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.http4k.client.OkHttp
import org.http4k.core.HttpHandler
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.time.Duration

private val log = KotlinLogging.logger { }

private fun createOkHttpClient(proxy: Proxy? = null): OkHttpClient {
    return OkHttpClient.Builder()
        .apply {
            proxy?.let { this.proxy(it) }
        }
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(Duration.ofSeconds(20))
        .readTimeout(Duration.ofSeconds(20))
        .writeTimeout(Duration.ofSeconds(20))
        .retryOnConnectionFailure(false)
        .build()
}

fun supportProxy(httpsProxy: String = env(env_HTTPS_PROXY)): HttpHandler {
    val proxyUri = java.net.URI(httpsProxy)
    log.info("Setting up proxy with: " + proxyUri.host + " " + proxyUri.port)
    val proxy = Proxy(
        Proxy.Type.HTTP,
        InetSocketAddress(proxyUri.host, proxyUri.port)
    )
    File("/tmp/proxysettings").writeText(proxyUri.host + " " + proxyUri.port)
    return OkHttp(client = createOkHttpClient(proxy))
}

fun noProxy(): HttpHandler {
    return OkHttp(client = createOkHttpClient())
}
