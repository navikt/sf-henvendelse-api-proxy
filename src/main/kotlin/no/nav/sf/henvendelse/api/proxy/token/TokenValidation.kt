package no.nav.sf.henvendelse.api.proxy.token

import mu.KotlinLogging
import no.nav.security.token.support.core.configuration.IssuerProperties
import no.nav.security.token.support.core.configuration.MultiIssuerConfiguration
import no.nav.security.token.support.core.http.HttpRequest
import no.nav.security.token.support.core.jwt.JwtToken
import no.nav.security.token.support.core.validation.JwtTokenValidationHandler
import org.http4k.core.Request
import java.io.File
import java.net.URL
import java.util.Optional
import kotlin.system.measureTimeMillis

const val env_AZURE_APP_WELL_KNOWN_URL = "AZURE_APP_WELL_KNOWN_URL"
const val env_AZURE_APP_CLIENT_ID = "AZURE_APP_CLIENT_ID"
const val env_AUDIENCE_TOKEN_SERVICE_URL = "AUDIENCE_TOKEN_SERVICE_URL"
const val env_AUDIENCE_TOKEN_SERVICE_ALIAS = "AUDIENCE_TOKEN_SERVICE_ALIAS"
const val env_AUDIENCE_TOKEN_SERVICE = "AUDIENCE_TOKEN_SERVICE"

object TokenValidator {
    private val tokenServiceAlias = System.getenv(env_AUDIENCE_TOKEN_SERVICE_ALIAS)
    private val tokenServiceUrl = System.getenv(env_AUDIENCE_TOKEN_SERVICE_URL)
    private val tokenServiceAudience = System.getenv(env_AUDIENCE_TOKEN_SERVICE).split(',')

    private val azureAlias = "azure"
    private val azureUrl = System.getenv(env_AZURE_APP_WELL_KNOWN_URL)
    private val azureAudience = System.getenv(env_AZURE_APP_CLIENT_ID).split(',')

    private val log = KotlinLogging.logger { }

    private val callerList: MutableMap<String, Int> = mutableMapOf()

    private val multiIssuerConfiguration = MultiIssuerConfiguration(
        mapOf(
            tokenServiceAlias to IssuerProperties(URL(tokenServiceUrl), tokenServiceAudience),
            azureAlias to IssuerProperties(URL(azureUrl), azureAudience)
        )
    )

    private val jwtTokenValidationHandler = JwtTokenValidationHandler(multiIssuerConfiguration)

    fun containsValidToken(request: Request): Boolean {
        val firstValidToken = jwtTokenValidationHandler.getValidatedTokens(request.toNavRequest()).firstValidToken
        return firstValidToken.isPresent
    }

    var latestValidationTime = 0L

    fun firstValidToken(request: Request, fetchStats: FetchStats): Optional<JwtToken> {
        lateinit var result: Optional<JwtToken>
        fetchStats.elapsedTimeTokenValidation = measureTimeMillis {
            result = jwtTokenValidationHandler.getValidatedTokens(request.toNavRequest()).firstValidToken
        }
        if (!result.isPresent) {
            File("/tmp/novalidtoken").writeText(request.toMessage())
        }
        return result
    }

    private fun Request.toNavRequest(): HttpRequest {
        val req = this
        return object : HttpRequest {
            override fun getHeader(headerName: String): String {
                return req.header(headerName) ?: ""
            }
            override fun getCookies(): Array<HttpRequest.NameValue> {
                return arrayOf()
            }
        }
    }
}
