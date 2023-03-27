package no.nav.sf.henvendelse.api.proxy

import java.io.File
import java.net.URI
import mu.KotlinLogging
import net.minidev.json.JSONArray
import no.nav.security.token.support.core.jwt.JwtToken
import org.apache.http.HttpHost
import org.apache.http.client.config.CookieSpecs
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.HttpClients
import org.http4k.client.ApacheClient
import org.http4k.core.HttpHandler

private val log = KotlinLogging.logger { }

fun ApacheClient.supportProxy(httpsProxy: String): HttpHandler {
    val proxyUri = URI(httpsProxy)
    return ApacheClient(client = HttpClients.custom()
        .setDefaultRequestConfig(
            RequestConfig.custom()
                .setProxy(HttpHost(proxyUri.host, proxyUri.port, proxyUri.scheme))
                .setConnectTimeout(40000)
                .setSocketTimeout(40000)
                .setConnectionRequestTimeout(40000)
                .setRedirectsEnabled(false)
                .setCookieSpec(CookieSpecs.IGNORE_COOKIES)
                .build()
        ).setMaxConnPerRoute(40).setMaxConnTotal(200)
        .build())
}

fun ApacheClient.withoutProxy(): HttpHandler {
    return ApacheClient(client = HttpClients.custom()
        .setDefaultRequestConfig(
            RequestConfig.custom()
                .setConnectTimeout(40000)
                .setSocketTimeout(40000)
                .setConnectionRequestTimeout(40000)
                .setRedirectsEnabled(false)
                .setCookieSpec(CookieSpecs.IGNORE_COOKIES)
                .build()
        )
        .build())
}

fun JwtToken.isMachineToken(callIndex: Long): Boolean {
    val rolesClaim = this.jwtTokenClaims.get(claim_roles)
    if (rolesClaim != null && rolesClaim is JSONArray) {
        if (rolesClaim.map { it.toString() }.any { it == "access_as_application" }) {
            log.info("($callIndex) Confirmed machine token")
            File("/tmp/machinetoken").writeText(this.tokenAsString)
            return true
        }
    }
    return false
}
