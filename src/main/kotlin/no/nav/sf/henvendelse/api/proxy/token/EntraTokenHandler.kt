package no.nav.sf.henvendelse.api.proxy.token

import com.google.gson.JsonParser
import no.nav.sf.henvendelse.api.proxy.env
import no.nav.sf.henvendelse.api.proxy.env_AZURE_APP_CLIENT_ID
import no.nav.sf.henvendelse.api.proxy.env_AZURE_APP_CLIENT_SECRET
import no.nav.sf.henvendelse.api.proxy.env_AZURE_OPENID_CONFIG_TOKEN_ENDPOINT
import no.nav.sf.henvendelse.api.proxy.httpclient.noProxy
import no.nav.sf.henvendelse.api.proxy.httpclient.supportProxy
import no.nav.sf.henvendelse.api.proxy.isDev
import no.nav.sf.henvendelse.api.proxy.isGcp
import org.http4k.core.Headers
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import java.io.File
import java.time.LocalDateTime

class EntraTokenHandler {
    val accessToken: String get() = fetchToken()

    private var token: String = ""
    private var expireTime: LocalDateTime = LocalDateTime.MIN

    private val client: HttpHandler = if (isGcp) noProxy() else supportProxy()

    private fun fetchToken(): String {
        if (LocalDateTime.now().isAfter(expireTime)) {
            fetchEntraToken()
        }
        return token
    }

    private fun fetchEntraToken() {
        val requestBody =
            "client_id=" + env(env_AZURE_APP_CLIENT_ID) +
                "&scope=" + "api://${if (isDev) "dev-gcp" else "prod-gcp"}.teamnks.sf-henvendelse-db/.default" +
                "&client_secret=" + env(env_AZURE_APP_CLIENT_SECRET) + "&grant_type=" + "client_credentials"
        val headers: Headers = listOf(Pair("Content-Type", "application/x-www-form-urlencoded"))
        val request =
            Request(Method.POST, env(env_AZURE_OPENID_CONFIG_TOKEN_ENDPOINT)).body(requestBody).headers(headers)

        val response = client(request)
        File("/tmp/responseEntra").writeText(response.toMessage())
        if (response.status.code == 200) {
            val jsonObject = JsonParser.parseString(response.bodyString()).asJsonObject

            token = jsonObject.get("access_token").asString
            val expiresIn = jsonObject.get("expires_in").asLong

            // Calculate expiry time with a 60-second safety margin
            expireTime =
                LocalDateTime
                    .now()
                    .plusSeconds(expiresIn - 60)
        } else {
            File("/tmp/responseEntraFail").writeText(response.toMessage())
        }
    }
}
