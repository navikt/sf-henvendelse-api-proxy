package no.nav.sf.henvendelse.api.proxy.httpclient

import no.nav.sf.henvendelse.api.proxy.HEADER_AUTHORIZATION
import no.nav.sf.henvendelse.api.proxy.HEADER_X_ACTING_NAV_IDENT
import no.nav.sf.henvendelse.api.proxy.HEADER_X_CORRELATION_ID
import no.nav.sf.henvendelse.api.proxy.application
import org.apache.http.client.config.CookieSpecs
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler
import org.apache.http.impl.client.HttpClients
import org.http4k.client.ApacheClient
import org.http4k.core.Headers
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import java.util.UUID

fun configuredClient(): HttpHandler {
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

fun testRequestDev(): Request {
    val headers: Headers = listOf(
        HEADER_X_CORRELATION_ID to UUID.randomUUID().toString(),
        HEADER_AUTHORIZATION to "Bearer ${application.accessTokenHandler.accessToken}",
        HEADER_X_ACTING_NAV_IDENT to "H159337"
    )
    val body = """{"journalforendeEnhet":"4151","temakode":"STO","kjedeId":"a1jc1b0246dd03deb7"}"""
    return Request(Method.POST, "https://navdialog--sit2.sandbox.my.salesforce.com/services/apexrest/henvendelse/journal").headers(headers).body(body)
}
