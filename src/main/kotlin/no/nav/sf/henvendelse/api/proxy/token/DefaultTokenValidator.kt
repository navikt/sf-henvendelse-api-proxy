package no.nav.sf.henvendelse.api.proxy.token

import no.nav.security.token.support.core.configuration.IssuerProperties
import no.nav.security.token.support.core.configuration.MultiIssuerConfiguration
import no.nav.security.token.support.core.http.HttpRequest
import no.nav.security.token.support.core.jwt.JwtToken
import no.nav.security.token.support.core.validation.JwtTokenValidationHandler
import no.nav.sf.henvendelse.api.proxy.Metrics
import no.nav.sf.henvendelse.api.proxy.config_AUDIENCE_TOKEN_SERVICE
import no.nav.sf.henvendelse.api.proxy.config_AUDIENCE_TOKEN_SERVICE_ALIAS
import no.nav.sf.henvendelse.api.proxy.config_AUDIENCE_TOKEN_SERVICE_URL
import no.nav.sf.henvendelse.api.proxy.env
import no.nav.sf.henvendelse.api.proxy.env_AZURE_APP_CLIENT_ID
import no.nav.sf.henvendelse.api.proxy.env_AZURE_APP_WELL_KNOWN_URL
import org.http4k.core.Request
import java.io.File
import java.net.URL
import kotlin.system.measureTimeMillis

class DefaultTokenValidator : TokenValidator {
    private val azureAlias = "azure"
    private val azureUrl = env(env_AZURE_APP_WELL_KNOWN_URL)
    private val azureAudience = env(env_AZURE_APP_CLIENT_ID).split(',')

    private val multiIssuerConfiguration = MultiIssuerConfiguration(
        mapOf(
            // tokenServiceAlias to IssuerProperties(URL(tokenServiceUrl), tokenServiceAudience),
            azureAlias to IssuerProperties(URL(azureUrl), azureAudience)
        )
    )

    private val jwtTokenValidationHandler = JwtTokenValidationHandler(multiIssuerConfiguration)

    override fun firstValidToken(request: Request, tokenFetchStats: Statistics): JwtToken? {
        var result: JwtToken?
        tokenFetchStats.elapsedTimeTokenValidation = measureTimeMillis {
            result = jwtTokenValidationHandler.getValidatedTokens(request.toNavRequest()).firstValidToken
        }
        if (result == null) {
            File("/tmp/novalidtoken").writeText(request.toMessage())
            Metrics.noauth.inc()
        }
        return result
    }

    private fun Request.toNavRequest(): HttpRequest {
        val req = this
        return object : HttpRequest {
            override fun getHeader(headerName: String): String {
                return req.header(headerName) ?: ""
            }
        }
    }
}
