package no.nav.sf.henvendelse.api.proxy.token

import java.net.URL
import java.util.Optional
import kotlin.system.measureTimeMillis
import mu.KotlinLogging
import no.nav.security.token.support.core.configuration.IssuerProperties
import no.nav.security.token.support.core.configuration.MultiIssuerConfiguration
import no.nav.security.token.support.core.http.HttpRequest
import no.nav.security.token.support.core.jwt.JwtToken
import no.nav.security.token.support.core.validation.JwtTokenValidationHandler
import org.http4k.core.Request

const val env_AZURE_APP_WELL_KNOWN_URL = "AZURE_APP_WELL_KNOWN_URL"
const val env_AZURE_APP_CLIENT_ID = "AZURE_APP_CLIENT_ID"
const val env_AUDIENCE_TOKEN_SERVICE_URL = "AUDIENCE_TOKEN_SERVICE_URL"
const val env_AUDIENCE_TOKEN_SERVICE_ALIAS = "AUDIENCE_TOKEN_SERVICE_ALIAS"
const val env_AUDIENCE_TOKEN_SERVICE = "AUDIENCE_TOKEN_SERVICE"
const val env_AUDIENCE_ISSO_URL = "AUDIENCE_ISSO_URL"
const val env_AUDIENCE_ISSO_ALIAS = "AUDIENCE_ISSO_ALIAS"
const val env_AUDIENCE_ISSO = "AUDIENCE_ISSO"
const val env_SALESFORCE_AZURE_CLIENT_ID = "SALESFORCE_AZURE_CLIENT_ID"

const val claim_NAME = "name"

object TokenValidator {

    private val tokenServiceAlias = System.getenv(env_AUDIENCE_TOKEN_SERVICE_ALIAS)
    private val tokenServiceUrl = System.getenv(env_AUDIENCE_TOKEN_SERVICE_URL)
    private val tokenServiceAudience = System.getenv(env_AUDIENCE_TOKEN_SERVICE).split(',')

    private val azureAlias = "azure"
    private val azureUrl = System.getenv(env_AZURE_APP_WELL_KNOWN_URL)
    private val azureAudience = System.getenv(env_AZURE_APP_CLIENT_ID).split(',') + "a37c2c66-ca41-4445-b9f3-1cdc266c9559"

    private val log = KotlinLogging.logger { }

    private val callerList: MutableMap<String, Int> = mutableMapOf()

    private val multiIssuerConfiguration = MultiIssuerConfiguration(
        mapOf(tokenServiceAlias to IssuerProperties(URL(tokenServiceUrl), tokenServiceAudience),
            azureAlias to IssuerProperties(URL(azureUrl), azureAudience)))

    private val jwtTokenValidationHandler = JwtTokenValidationHandler(multiIssuerConfiguration)

    fun containsValidToken(request: Request): Boolean {
        val firstValidToken = jwtTokenValidationHandler.getValidatedTokens(request.toNavRequest()).firstValidToken
        return firstValidToken.isPresent
    }

    fun firstValidToken(request: Request): Optional<JwtToken> {
        lateinit var result: Optional<JwtToken>
        FetchStats.elapsedTimeTokenValidation = measureTimeMillis {
            result = jwtTokenValidationHandler.getValidatedTokens(request.toNavRequest()).firstValidToken
        }
        return result
    }

    fun Request.toNavRequest(): HttpRequest {
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
