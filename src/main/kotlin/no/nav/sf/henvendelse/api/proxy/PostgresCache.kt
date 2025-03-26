package no.nav.sf.henvendelse.api.proxy

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import no.nav.sf.henvendelse.api.proxy.token.EntraTokenHandler
import org.http4k.client.ApacheClient
import org.http4k.core.Headers
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.measureTimeMillis

object PostgresCache {
    private val log = KotlinLogging.logger { }
    private val entraTokenHandler = EntraTokenHandler()
    // private val client: HttpHandler = supportProxy()
    private val clientNoProxy: HttpHandler = ApacheClient()

    private val endpointSfHenvendelserDb = if (isDev) {
        "https://sf-henvendelse-db.intern.dev.nav.no/cache/postgreshenvendelseliste"
    } else {
        "https://sf-henvendelse-db.intern.nav.no/cache/postgreshenvendelseliste"
    }

    private val authHeaders: Headers get() = listOf(HEADER_AUTHORIZATION to "Bearer ${entraTokenHandler.accessToken}")

    fun get(aktorId: String, endpointLabel: String) {
        val request =
            Request(Method.GET, "$endpointSfHenvendelserDb?aktorId=$aktorId").headers(authHeaders)
        val response: Response
        val callTime = measureTimeMillis {
            response = clientNoProxy(request)
        }

        Metrics.postgresHenvendelselisteCache.labels(Method.GET.name, response.status.code.toString(), callTime.toLabel(), endpointLabel).inc()
        appendCacheLog("Postgres Get AktorId $aktorId $endpointLabel - status ${response.status}, body size ${response.body.length}")
        if (response.status.code != 200 && response.status.code != 204) {
            File("/tmp/failedPostgresCacheGet-${response.status.code}").writeText("REQUEST\n" + request.toMessage() + "\n\nRESPONSE\n" + response.toMessage())
        }
    }

    fun put(aktorId: String, json: String, endpointLabel: String) {
        val request =
            Request(Method.POST, "$endpointSfHenvendelserDb?aktorId=$aktorId").headers(authHeaders).body(json)
        val response: Response
        val callTime = measureTimeMillis {
            response = clientNoProxy(request)
        }
        Metrics.postgresHenvendelselisteCache.labels(Method.POST.name, response.status.code.toString(), callTime.toLabel(), endpointLabel).inc()
        appendCacheLog("Put Postgres AktorId $aktorId $endpointLabel - status ${response.status}, request body size ${json.length}")
        if (response.status.code != 200) {
            File("/tmp/failedPostgresCachePut-${response.status.code}").writeText("REQUEST\n" + request.toMessage() + "\n\nRESPONSE\n" + response.toMessage())
        }
    }

    fun delete(aktorId: String, endpointLabel: String) {
        val request =
            Request(Method.DELETE, "$endpointSfHenvendelserDb?aktorId=$aktorId").headers(authHeaders)
        val response: Response
        val callTime = measureTimeMillis {
            response = clientNoProxy(request)
        }
        Metrics.postgresHenvendelselisteCache.labels(Method.DELETE.name, response.status.code.toString(), callTime.toLabel(), endpointLabel).inc()
        appendCacheLog("Delete Postgres AktorId $aktorId $endpointLabel - status ${response.status}")
    }

    fun doAsyncGet(aktorId: String, endpointLabel: String) {
        log.info { "Will perform async postgres cache get with aktorId $aktorId" }
        appendCacheLog("Will perform postgres async cache get with aktorId $aktorId")
        GlobalScope.launch {
            get(aktorId, endpointLabel)
        }
    }

    fun doAsyncPut(aktorId: String, json: String, endpointLabel: String) {
        log.info { "Will perform async postgres cache put with aktorId $aktorId" }
        appendCacheLog("Will perform async postgres cache put with aktorId $aktorId")
        GlobalScope.launch {
            put(aktorId, json, endpointLabel)
        }
    }

    fun doAsyncDelete(aktorId: String, endpointLabel: String) {
        log.info { "Will perform async cache postgres delete with aktorId $aktorId" }
        appendCacheLog("Will perform async cache postgres delete with aktorId $aktorId")
        GlobalScope.launch {
            delete(aktorId, endpointLabel)
        }
    }

    private const val logLimit = 100
    private var logCounter = 0

    val currentDateTime: String get() = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME)

    fun appendCacheLog(msg: String) {
        logCounter++
        if (logCounter <= logLimit) {
            File("/tmp/cacheLog").appendText("$currentDateTime $msg\n")
        }
    }

    fun Long.toLabel(): String = when {
        this < 100 -> "< 100"
        this < 200 -> "< 200"
        this < 300 -> "< 300"
        this < 400 -> "< 400"
        this < 500 -> "< 500"
        else -> "500+"
    }
}
