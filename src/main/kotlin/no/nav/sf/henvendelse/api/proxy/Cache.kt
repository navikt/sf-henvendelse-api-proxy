package no.nav.sf.henvendelse.api.proxy

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import no.nav.sf.henvendelse.api.proxy.httpclient.supportProxy
import no.nav.sf.henvendelse.api.proxy.token.EntraTokenHandler
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import java.io.File

object Cache {
    private val entraTokenHandler = EntraTokenHandler()
    private val client: HttpHandler = supportProxy()

    val endpointSfHenvendelserDb = if (application.devContext) {
        "https://sf-henvendelse-db.intern.dev.nav.no/cache/henvendelseliste"
    } else {
        "https://sf-henvendelse-db.intern.nav.no/cache/henvendelseliste"
    }

    fun get(aktorId: String) {
        val request =
            Request(Method.GET, "$endpointSfHenvendelserDb?aktorId=$aktorId")
        val response = client(request)
        File("/tmp/cacheLog").appendText("Get AktoerId $aktorId - status ${response.status}, body ${response.bodyString()}\n")
    }

    fun put(aktorId: String, json: String) {
        val request =
            Request(Method.POST, "$endpointSfHenvendelserDb?aktorId=$aktorId").body(json)
        val response = client(request)
        File("/tmp/cacheLog").appendText("Put AktoerId $aktorId - status ${response.status}, request body size ${json.length}\n")
    }

    fun delete(aktorId: String) {
        val request =
            Request(Method.DELETE, "$endpointSfHenvendelserDb?aktorId=$aktorId")
        val response = client(request)
        File("/tmp/cacheLog").appendText("Delete AktoerId $aktorId - status ${response.status}\n")
    }

    fun doAsyncGet(aktorId: String) {
        File("/tmp/cacheLog").appendText("Will perform cache get with aktoerId $aktorId\n")
        GlobalScope.launch {
            get(aktorId)
        }
    }
}
