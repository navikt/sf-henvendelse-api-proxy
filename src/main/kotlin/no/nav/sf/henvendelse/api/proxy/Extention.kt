package no.nav.sf.henvendelse.api.proxy

import java.io.File
import java.net.URI
import java.util.Arrays
import java.util.stream.Collectors
import no.nav.security.token.support.core.jwt.JwtToken
import org.apache.http.HttpHost
import org.apache.http.client.config.CookieSpecs
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.HttpClients
import org.http4k.client.ApacheClient
import org.http4k.core.HttpHandler

fun ApacheClient.supportProxy(httpsProxy: String): HttpHandler = httpsProxy.let { p ->
    when {
        p.isEmpty() -> this()
        else -> {
            val up = URI(p)
            this(
                client =
                HttpClients.custom()
                    .setDefaultRequestConfig(
                        RequestConfig.custom()
                            .setProxy(HttpHost(up.host, up.port, up.scheme))
                            .setConnectTimeout(5000)
                            .setSocketTimeout(5000)
                            .setConnectionRequestTimeout(5000)
                            .setRedirectsEnabled(false)
                            .setCookieSpec(CookieSpecs.IGNORE_COOKIES)
                            .build()
                    )
                    .build()
            )
        }
    }
}

var latestAzp = ""
var latestName = ""

const val claim_NAVident = "NAVident"
const val claim_azp_name = "azp_name"
const val claim_name = "name"

private val azpMap: MutableMap<String, Int> = mutableMapOf()
private val nameMap: MutableMap<String, Int> = mutableMapOf()

private fun MutableMap<String, Int>.inc(key: String) {
    if (!this.containsKey(key)) this[key] = 0
    this[key] = this[key]!! + 1
}

fun JwtToken.logStatsInTmp() {
    try {
        val azp = this.jwtTokenClaims.get(claim_azp_name)?.toString() ?: "null"
        val name = this.jwtTokenClaims.get(claim_name)?.toString() ?: "null"
        azpMap.inc(azp)
        nameMap.inc(name)
        latestAzp = azp
        latestName = name
        File("/tmp/azpMap").writeText(azpMap.toString())
        File("/tmp/nameMap").writeText(nameMap.toString())
    } catch (e: Exception) {
        File("/tmp/exception").writeText(e.message.toString())
    }
}

fun getTokensFromHeader(authorizationHeader: String?): List<JwtToken> {
    try {
        if (authorizationHeader != null) {
            val headerValues = authorizationHeader.split(",").toTypedArray()
            return extractBearerTokens(*headerValues)
                .stream()
                .map { encodedToken: String? -> JwtToken(encodedToken) } // .filter(jwtToken -> config.getIssuer(jwtToken.getIssuer()).isPresent())
                .collect(Collectors.toList())
        }
    } catch (e: Exception) {
    }
    return emptyList()
}

private fun extractBearerTokens(vararg headerValues: String): List<String> {
    return Arrays.stream(headerValues)
        .map { s: String -> s.split(" ").toTypedArray() }
        .filter { pair: Array<String> -> pair.size == 2 }
        .filter { pair: Array<String> -> pair[0].trim { it <= ' ' }.equals("Bearer", ignoreCase = true) }
        .map { pair: Array<String> -> pair[1].trim { it <= ' ' } }
        .collect(Collectors.toList())
}
