package no.nav.sf.henvendelse.api.proxy.httpclient

import no.nav.sf.henvendelse.api.proxy.env
import no.nav.sf.henvendelse.api.proxy.env_HTTPS_PROXY
import org.apache.http.HttpHost
import org.apache.http.client.config.CookieSpecs
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler
import org.apache.http.impl.client.HttpClients
import org.http4k.client.ApacheClient
import org.http4k.core.HttpHandler
import java.net.URI

fun supportProxy(httpsProxy: String = env(env_HTTPS_PROXY)): HttpHandler {
    val proxyUri = URI(httpsProxy)
    return ApacheClient(
        client = HttpClients.custom()
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setProxy(HttpHost(proxyUri.host, proxyUri.port, proxyUri.scheme))
                    .setConnectTimeout(20000) // High - but not the default limitless wait for connection (max time establishing)
                    .setSocketTimeout(20000) // (Max time between data packets)
                    .setConnectionRequestTimeout(20000) // (Max time to be served from connection pool)
                    .setRedirectsEnabled(false)
                    .setCookieSpec(CookieSpecs.IGNORE_COOKIES)
                    .build()
            ).setRetryHandler(DefaultHttpRequestRetryHandler(0, false)).setMaxConnPerRoute(40).setMaxConnTotal(40)
            .build()
    )
}

fun noProxy(): HttpHandler {
    return ApacheClient(
        client = HttpClients.custom()
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setConnectTimeout(20000) // High - but not the default limitless wait for connection (max time establishing)
                    .setSocketTimeout(20000) // (Max time between data packets)
                    .setConnectionRequestTimeout(20000) // (Max time to be served from connection pool)
                    .setRedirectsEnabled(false)
                    .setCookieSpec(CookieSpecs.IGNORE_COOKIES)
                    .build()
            ).setRetryHandler(DefaultHttpRequestRetryHandler(0, false)).setMaxConnPerRoute(40).setMaxConnTotal(40)
            .build()
    )
}
