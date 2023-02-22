package no.nav.sf.henvendelse.api.proxy.token

import com.google.gson.Gson
import java.io.File
import java.time.Instant
import mu.KotlinLogging
import no.nav.security.token.support.core.jwt.JwtToken
import no.nav.sf.henvendelse.api.proxy.claim_NAVident
import no.nav.sf.henvendelse.api.proxy.claim_azp_name
import no.nav.sf.henvendelse.api.proxy.supportProxy
import org.http4k.client.ApacheClient
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.body.toBody
import org.json.JSONObject

object OboTokenExchangeHandler {
    private val log = KotlinLogging.logger { }

    private val gson = Gson()

    private val client: Lazy<HttpHandler> = lazy { ApacheClient.supportProxy(System.getenv("HTTPS_PROXY")) }

    val clientId: Lazy<String> = lazy { System.getenv("AZURE_APP_CLIENT_ID") }
    val sfClientId: Lazy<String> = lazy { System.getenv("AZURE_APP_CLIENT_SECRET") }
    val sfClientSecret: Lazy<String> = lazy { System.getenv("SALESFORCE_AZURE_CLIENT_ID") }

    val OBOcache: MutableMap<String, JwtToken> = mutableMapOf()

    fun fetchAzureTokenOBO(jwtIn: JwtToken): JwtToken {
        val NAVident = jwtIn.jwtTokenClaims.getStringClaim(claim_NAVident)
        val azp_name = jwtIn.jwtTokenClaims.getStringClaim(claim_azp_name)
        val key = azp_name + ":" + NAVident
        OBOcache.get(key)?.let { cachedToken ->
            if (cachedToken.jwtTokenClaims.expirationTime.toInstant().minusSeconds(10) > Instant.now()) {
                log.info("Used cached token for $key")
                File("/tmp/latestusedcachedkey").writeText(key)
                return cachedToken
            }
        }
        val req = Request(Method.POST, AccessTokenHandler.SFTokenHost.value)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .body(
                listOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:jwt-bearer",
                    "assertion" to jwtIn.tokenAsString,
                    "client_id" to clientId.value,
                    "scope" to "api://${sfClientId.value}/.default",
                    "client_secret" to sfClientSecret.value,
                    "requested_token_use" to "on_behalf_of"
                ).toBody()
            )

        val res = client.value(req)

        File("/tmp/azureOBOtestresult").writeText(res.toMessage())

        val jwt = JwtToken(JSONObject(res.body).get("access_token").toString())
        File("/tmp/azurejwtclaimset").writeText(jwt.jwtTokenClaims.toString())
        OBOcache[key] = jwt
        return jwt
    }
}
