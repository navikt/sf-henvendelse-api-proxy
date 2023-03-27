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
                .setConnectTimeout(20000) // High but not the default limitless wait for connection (max time establishing)
                .setSocketTimeout(20000) // (Max time between data packets)
                .setConnectionRequestTimeout(20000) // (Max time to be served from connection pool)
                .setRedirectsEnabled(false)
                .setCookieSpec(CookieSpecs.IGNORE_COOKIES)
                .build()
        ).setMaxConnPerRoute(30).setMaxConnTotal(200)
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
