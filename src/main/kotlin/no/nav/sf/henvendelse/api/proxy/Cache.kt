package no.nav.sf.henvendelse.api.proxy

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import no.nav.sf.henvendelse.api.proxy.httpclient.supportProxy
import org.http4k.core.Headers
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import java.io.File

object Cache {
    private val client: HttpHandler = supportProxy()

    fun get(aktorId: String) {
    }

    fun put(aktorId: String) {
    }

    fun delete(aktorId: String) {
    }

    fun doAsyncGet(aktorId: String) {
        File("/tmp/cacheLog").appendText("Will perform cache get with aktoerId $aktorId")
        GlobalScope.launch {
            get(aktorId)
        }
    }

    fun fetchEntraToken() {
        val requestBody = "client_id=" + env(env_AZURE_APP_CLIENT_ID) +
            "&scope=" + "api://${if (application.devContext) "dev-gcp" else "prod-gcp"}.teamnks.sf-henvendelse-db/.default" +
            "&client_secret=" + env(env_AZURE_APP_CLIENT_SECRET) + "&grant_type=" + "client_credentials"
        val headers: Headers = listOf(Pair("Content-Type", "application/x-www-form-urlencoded"))
        val request =
            Request(Method.POST, env(env_AZURE_OPENID_CONFIG_TOKEN_ENDPOINT)).body(requestBody).headers(headers)

        val response = client(request)
        File("/tmp/responseEntra").writeText(response.toMessage())
    }
}
