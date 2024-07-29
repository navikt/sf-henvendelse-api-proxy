package no.nav.sf.henvendelse.api.proxy.token

import mu.KotlinLogging
import no.nav.security.token.support.core.jwt.JwtToken
import no.nav.sf.henvendelse.api.proxy.Metrics
import no.nav.sf.henvendelse.api.proxy.config_SALESFORCE_AZURE_ALIAS
import no.nav.sf.henvendelse.api.proxy.env
import no.nav.sf.henvendelse.api.proxy.env_AZURE_APP_CLIENT_ID
import no.nav.sf.henvendelse.api.proxy.env_AZURE_APP_CLIENT_SECRET
import no.nav.sf.henvendelse.api.proxy.env_AZURE_OPENID_CONFIG_TOKEN_ENDPOINT
import no.nav.sf.henvendelse.api.proxy.httpclient.supportProxy
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.body.toBody
import org.json.JSONObject
import java.time.Instant

/**
 * A handler for azure on-behalf-of exchange flow.
 * @see [v2_oauth2_on_behalf_of_flow](https://learn.microsoft.com/en-us/azure/active-directory/develop/v2-oauth2-on-behalf-of-flow)
 *
 * Exchanges an azure on-behalf-of-token with audience to this app for one with audience to salesforce. Caches the result
 */
object OboTokenExchangeHandler {
    /*
    OBO exchange currently not in use
    Example lines for usage:
        var oboToken = ""
        tokenFetchStats.elapsedTimeOboHandling = measureTimeMillis { oboToken = OboTokenExchangeHandler.exchange(token, tokenFetchStats).tokenAsString }

        // Given above, the following can be added as headers to forward call:
        if (oboToken.isNotEmpty()) { listOf(Pair("X-Nav-Token", oboToken)) } else { listOf() }
     */

    private val log = KotlinLogging.logger { }

    private val client: HttpHandler = supportProxy()

    private val clientId: String = env(env_AZURE_APP_CLIENT_ID)
    private val clientSecret: String = env(env_AZURE_APP_CLIENT_SECRET)
    private val azureTokenEndPoint: String = env(env_AZURE_OPENID_CONFIG_TOKEN_ENDPOINT)
    private val sfAlias: String = env(config_SALESFORCE_AZURE_ALIAS)

    private var OBOcache: MutableMap<String, JwtToken> = mutableMapOf()

    private var droppedCacheElements = 0L

    fun refreshCache() {
        OBOcache = OBOcache.filterValues {
            val stillEligable = it.jwtTokenClaims.expirationTime.toInstant().minusSeconds(10) > Instant.now()
            if (!stillEligable) droppedCacheElements++
            stillEligable
        }.toMutableMap()
        log.info { "Dropped cache elements during lifetime $droppedCacheElements" }
    }

    fun exchange(jwtIn: JwtToken, tokenFetchStats: Statistics): JwtToken {
        val NAVident = jwtIn.jwtTokenClaims.getStringClaim(CLAIM_NAV_IDENT)
        val azp_name = jwtIn.jwtTokenClaims.getStringClaim(CLAIM_AZP_NAME)
        val key = "$azp_name:$NAVident"
        OBOcache[key]?.let { cachedToken ->
            if (cachedToken.jwtTokenClaims.expirationTime.toInstant().minusSeconds(10) > Instant.now()) {
                // tokenFetchStats.oboCached++
                return cachedToken
            }
        }
        Metrics.cacheSize.set(OBOcache.size.toDouble())

        val req = Request(Method.POST, azureTokenEndPoint)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .body(
                listOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:jwt-bearer",
                    "assertion" to jwtIn.tokenAsString,
                    "client_id" to clientId,
                    "scope" to "api://$sfAlias/.default",
                    "client_secret" to clientSecret,
                    "requested_token_use" to "on_behalf_of"
                ).toBody()
            )

        lateinit var res: Response
        // tokenFetchStats.elapsedTimeOboExchangeRequest = measureTimeMillis {
        res = client(req)
        // }
        val jwt = JwtToken(JSONObject(res.bodyString()).get("access_token").toString())
        OBOcache[key] = jwt
        // tokenFetchStats.oboFetches++
        return jwt
    }
}
