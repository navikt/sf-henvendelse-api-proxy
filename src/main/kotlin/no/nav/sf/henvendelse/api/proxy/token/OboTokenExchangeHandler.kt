package no.nav.sf.henvendelse.api.proxy.token

import java.time.Instant
import kotlin.system.measureTimeMillis
import mu.KotlinLogging
import no.nav.security.token.support.core.jwt.JwtToken
import no.nav.sf.henvendelse.api.proxy.claim_NAVident
import no.nav.sf.henvendelse.api.proxy.claim_azp_name
import no.nav.sf.henvendelse.api.proxy.supportProxy
import org.http4k.client.ApacheClient
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.body.toBody
import org.json.JSONObject

/**
 * A handler for azure on-behalf-of exchange flow.
 * @see [v2_oauth2_on_behalf_of_flow](https://learn.microsoft.com/en-us/azure/active-directory/develop/v2-oauth2-on-behalf-of-flow)
 *
 * Exchanges an azure on-behalf-of-token with audience to this app for one with audience to salesforce. Caches the result
 */
object OboTokenExchangeHandler {
    private val log = KotlinLogging.logger { }

    private val client: Lazy<HttpHandler> = lazy { ApacheClient.supportProxy(System.getenv("HTTPS_PROXY")) }

    private val clientId: Lazy<String> = lazy { System.getenv("AZURE_APP_CLIENT_ID") }
    private val clientSecret: Lazy<String> = lazy { System.getenv("AZURE_APP_CLIENT_SECRET") }
    private val azureTokenEndPoint: Lazy<String> = lazy { System.getenv("AZURE_OPENID_CONFIG_TOKEN_ENDPOINT") }
    private val sfAlias: Lazy<String> = lazy { System.getenv("SALESFORCE_AZURE_ALIAS") }

    private var OBOcache: MutableMap<String, JwtToken> = mutableMapOf()

    fun refreshCache() {
        OBOcache.filterValues { it.jwtTokenClaims.expirationTime.toInstant().minusSeconds(10) > Instant.now() }.toMutableMap()
    }

    fun exchange(jwtIn: JwtToken): JwtToken {
        val NAVident = jwtIn.jwtTokenClaims.getStringClaim(claim_NAVident)
        val azp_name = jwtIn.jwtTokenClaims.getStringClaim(claim_azp_name)
        val key = azp_name + ":" + NAVident
        OBOcache.get(key)?.let { cachedToken ->
            if (cachedToken.jwtTokenClaims.expirationTime.toInstant().minusSeconds(10) > Instant.now()) {
                FetchStats.OBOcached++
                return cachedToken
            }
        }

        val req = Request(Method.POST, azureTokenEndPoint.value)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .body(
                listOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:jwt-bearer",
                    "assertion" to jwtIn.tokenAsString,
                    "client_id" to clientId.value,
                    "scope" to "api://${sfAlias.value}/.default",
                    "client_secret" to clientSecret.value,
                    "requested_token_use" to "on_behalf_of"
                ).toBody()
            )

        lateinit var res: Response
        FetchStats.elapsedTimeOboExchangeRequest = measureTimeMillis {
            res = client.value(req)
        }
        val jwt = JwtToken(JSONObject(res.bodyString()).get("access_token").toString())
        OBOcache[key] = jwt
        FetchStats.OBOfetches++
        return jwt
    }
}
