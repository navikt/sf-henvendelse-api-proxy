package no.nav.sf.henvendelse.api.proxy.httpclient

import org.apache.http.client.config.CookieSpecs
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.HttpClients
import org.http4k.client.ApacheClient
import org.http4k.core.HttpHandler

fun supportProxy(/**/): HttpHandler {
    // val proxyUri = URI(httpsProxy)
    return ApacheClient(
        client = HttpClients.custom()
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    // .setProxy(HttpHost(proxyUri.host, proxyUri.port, proxyUri.scheme))
                    .setConnectTimeout(20000) // High - but not the default limitless wait for connection (max time establishing)
                    .setSocketTimeout(20000) // (Max time between data packets)
                    .setConnectionRequestTimeout(20000) // (Max time to be served from connection pool)
                    .setRedirectsEnabled(false)
                    .setCookieSpec(CookieSpecs.IGNORE_COOKIES)
                    .build()
            ).setMaxConnPerRoute(40).setMaxConnTotal(40)
            .build()
    )
}
