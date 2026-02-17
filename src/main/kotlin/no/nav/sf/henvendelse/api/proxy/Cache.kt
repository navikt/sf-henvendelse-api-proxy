package no.nav.sf.henvendelse.api.proxy

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import no.nav.sf.henvendelse.api.proxy.httpclient.noProxy
import no.nav.sf.henvendelse.api.proxy.token.EntraTokenHandler
import org.http4k.core.Headers
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis

object Cache {
    private val log = KotlinLogging.logger { }
    private val entraTokenHandler = EntraTokenHandler()

    // private val client: HttpHandler = supportProxy()
    private val clientNoProxy: HttpHandler = noProxy()

    private val endpointSfHenvendelserDb = "http://sf-henvendelse-db/cache/henvendelseliste"
//        if (isDev) {
//            "https://sf-henvendelse-db.intern.dev.nav.no/cache/henvendelseliste"
//        } else {
//            "https://sf-henvendelse-db.intern.nav.no/cache/henvendelseliste"
//        }

    private val authHeaders: Headers get() = listOf(HEADER_AUTHORIZATION to "Bearer ${entraTokenHandler.accessToken}")

    // TODO Add the pageSize and page parmeteres to rest call + use service discovery - store the default values as config.
    fun get(
        aktorId: String,
        page: Int,
        pageSize: Int,
        endpointLabel: String,
    ): Response {
        val request =
            Request(Method.GET, "$endpointSfHenvendelserDb?aktorId=$aktorId&page=$page&pageSize=$pageSize").headers(authHeaders)
        val response: Response

        val callTime =
            measureTimeMillis {
                response = clientNoProxy(request)
            }

        Metrics.postgresHenvendelselisteCache
            .labels(
                Method.GET.name,
                response.status.code.toString(),
                callTime.toLabel(),
                endpointLabel,
            ).inc()
        if (response.status.code != 200 && response.status.code != 204) {
            File(
                "/tmp/failedPostgresCacheGet-${response.status.code}",
            ).writeText("REQUEST\n" + request.toMessage() + "\n\nRESPONSE\n" + response.toMessage())
        }
        return response
    }

    fun put(
        aktorId: String,
        page: Int,
        pageSize: Int,
        json: String,
        endpointLabel: String,
    ) {
        val request =
            Request(Method.POST, "$endpointSfHenvendelserDb?aktorId=$aktorId&page=$page&pageSize=$pageSize").headers(authHeaders).body(json)
        lateinit var response: Response
        var retryCount = 0
        val maxRetries = 2

        val callTime =
            measureTimeMillis {
                while (retryCount <= maxRetries) {
                    response = clientNoProxy(request)

                    if (response.status.successful) {
                        break
                    }

                    retryCount++
                }
                if (!response.status.successful) {
                    delete(aktorId, "$endpointLabel - failed POST")
                }
            }

        val retryLbl = if (retryCount > 0) " - retry $retryCount" else ""
        Metrics.postgresHenvendelselisteCache
            .labels(
                Method.POST.name,
                response.status.code.toString(),
                callTime.toLabel(),
                endpointLabel + retryLbl,
            ).inc()
        if (!response.status.successful) {
            File(
                "/tmp/failedPostgresCachePut-${response.status.code}",
            ).writeText("REQUEST\n" + request.toMessage() + "\n\nRESPONSE\n" + response.toMessage())
        }
    }

    fun delete(
        aktorId: String,
        endpointLabel: String,
    ) {
        val request =
            Request(Method.DELETE, "$endpointSfHenvendelserDb?aktorId=$aktorId").headers(authHeaders)
        val response: Response
        val callTime =
            measureTimeMillis {
                response = clientNoProxy(request)
            }
        Metrics.postgresHenvendelselisteCache
            .labels(
                Method.DELETE.name,
                response.status.code.toString(),
                callTime.toLabel(),
                endpointLabel,
            ).inc()
    }

    fun doAsyncGet(
        aktorId: String,
        page: Int,
        pageSize: Int,
        endpointLabel: String,
    ) {
        log.info { "Will perform async postgres cache get with aktorId $aktorId" }
        GlobalScope.launch {
            get(aktorId, page, pageSize, endpointLabel)
        }
    }

    fun doAsyncPut(
        aktorId: String,
        page: Int,
        pageSize: Int,
        json: String,
        endpointLabel: String,
    ) {
        log.info { "Will perform async postgres cache put with aktorId $aktorId" }
        GlobalScope.launch {
            put(aktorId, page, pageSize, json, endpointLabel)
        }
    }

    fun doAsyncDelete(
        aktorId: String,
        endpointLabel: String,
    ) {
        log.info { "Will perform async cache postgres delete with aktorId $aktorId" }
        GlobalScope.launch {
            delete(aktorId, endpointLabel)
        }
    }

    val currentDateTime: String get() = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME)

    fun Long.toLabel(): String =
        when {
            this < 100 -> "< 100"
            this < 200 -> "< 200"
            this < 300 -> "< 300"
            this < 400 -> "< 400"
            this < 500 -> "< 500"
            else -> "500+"
        }

    val scheduler = Executors.newSingleThreadScheduledExecutor()

    val secondsToRetry = 10

    /*
    fun retryCallVsCache(
        request: Request,
        aktorIdInFocus: String,
    ) {
        Thread.sleep(secondsToRetry * 1000L)
        File("/tmp/investigate-redemption-$aktorIdInFocus").appendText("Trigger cache get\n")
        val cacheResponse = get(aktorIdInFocus, "henvendelseliste-retry")
        File("/tmp/investigate-redemption-$aktorIdInFocus").appendText("Trigger sf call\n")
        val sfResponse = application.client(request)
        File(
            "/tmp/investigate-redemption-$aktorIdInFocus",
        ).appendText("Cache status ${cacheResponse.status.code}, SF status ${sfResponse.status.code}\n")
        if (cacheResponse.status.code == 200) {
            val stillFail = compareRealToCache(sfResponse, cacheResponse, aktorIdInFocus, true)
            if (stillFail) {
                File("/tmp/investigate-redemption-$aktorIdInFocus").appendText("Still fail\n")
            } else {
                File("/tmp/investigate-redemption-$aktorIdInFocus").appendText("Redemption - match\n")
            }
        } else {
            Metrics.cacheControl.labels("redemption-success", "", "0", "0").inc()
            File("/tmp/investigate-redemption-$aktorIdInFocus").appendText("Redemption - cache cleared\n")
            File("/tmp/redemption-cache-cleared-${cacheResponse.status.code}-success-$aktorIdInFocus").writeText(cacheResponse.toMessage())
        }
    }

    fun compareRealToCache(
        sfResponse: Response,
        cacheResponse: Response,
        aktorIdInFocus: String,
        rechecking: Boolean = false,
    ): Boolean {
        val cache = cacheResponse.bodyString()
        val sf = sfResponse.bodyString()
        val journalPostIdNullsSF = JsonComparator.numberOfJournalPostIdNull(sf)
        val journalPostIdNullsCache = JsonComparator.numberOfJournalPostIdNull(cache)
        val fnrFieldsSF = JsonComparator.numberOfFnrFields(sf)
        val fnrFieldsCache = JsonComparator.numberOfFnrFields(cache)
        val moreFnrsInSF = fnrFieldsSF - fnrFieldsCache
        val prefix = if (rechecking) "redemption-" else ""
        if (sf == cache) {
            Metrics.cacheControl.labels("${prefix}success", "", journalPostIdNullsSF.toString(), moreFnrsInSF.toString()).inc()
            if (rechecking) {
                File("/tmp/${prefix}success-$aktorIdInFocus").writeText(cacheResponse.toMessage())
            }
            return false
        } else if (JsonComparator.jsonEquals(sf, cache)) {
            Metrics.cacheControl.labels("${prefix}success", "", journalPostIdNullsSF.toString(), moreFnrsInSF.toString()).inc()
            if (rechecking) {
                File("/tmp/${prefix}success-$aktorIdInFocus").writeText(cacheResponse.toMessage())
            }
            return false
        } else if (moreFnrsInSF != 0) {
            File("/tmp/${prefix}latestCacheMismatchResponseCache-henvendelser-diff-$aktorIdInFocus").writeText(cacheResponse.toMessage())
            File("/tmp/${prefix}latestCacheMismatchResponseSF-henvendelser-diff-$aktorIdInFocus").writeText(sfResponse.toMessage())
            Metrics.cacheControl
                .labels(
                    "${prefix}fail",
                    "henvendelser diff (fnr)",
                    journalPostIdNullsSF.toString(),
                    moreFnrsInSF.toString(),
                ).inc()
        } else if (journalPostIdNullsCache > journalPostIdNullsSF) {
            File("/tmp/${prefix}latestCacheMismatchResponseCache-journalidnull-$aktorIdInFocus").writeText(cacheResponse.toMessage())
            File("/tmp/${prefix}latestCacheMismatchResponseSF-journalidnull-$aktorIdInFocus").writeText(sfResponse.toMessage())
            Metrics.cacheControl
                .labels(
                    "${prefix}fail",
                    "unset journalpostId",
                    journalPostIdNullsSF.toString(),
                    moreFnrsInSF.toString(),
                ).inc()
        } else {
            val cacheLines = cacheResponse.bodyString().lines()
            val responseLines = sfResponse.bodyString().lines()

            var avsluttetDatoCount = 0
            var sistEndretAvCount = 0

            var journalpostIdCount = 0
            var journalfortDatoCount = 0

            for ((i, pair) in cacheLines.zip(responseLines).withIndex()) {
                if (pair.first != pair.second) {
                    if (pair.first.contains("avsluttetDato")) avsluttetDatoCount++
                    if (pair.first.contains("sistEndretAv")) sistEndretAvCount++
                    if (pair.first.contains("journalpostId")) journalpostIdCount++
                    if (pair.first.contains("journalfortDato")) journalfortDatoCount++
                }
            }
            val type =
                if (avsluttetDatoCount == 1 && sistEndretAvCount == 1) {
                    "Avsluttet"
                } else if (journalpostIdCount == 1 && journalfortDatoCount == 1) {
                    "journalpostId"
                } else {
                    "Undefined"
                }
            Metrics.cacheControl.labels("${prefix}fail", type, journalPostIdNullsSF.toString(), moreFnrsInSF.toString()).inc()
            File("/tmp/${prefix}latestCacheMismatchResponseCache-$type-$aktorIdInFocus").writeText(cacheResponse.toMessage())
            File("/tmp/${prefix}latestCacheMismatchResponseSF-$type-$aktorIdInFocus").writeText(sfResponse.toMessage())
            File("/tmp/${prefix}latestCacheMismatchMismatches-$type-$aktorIdInFocus").writeText("")
            for ((i, pair) in cacheLines.zip(responseLines).withIndex()) {
                if (pair.first != pair.second) {
                    File("/tmp/${prefix}latestCacheMismatchMismatches-$type-$aktorIdInFocus").appendText(
                        "Mismatch at line $i:\n" +
                            "CACHE: ${pair.first}\n" +
                            "SF: ${pair.second}\n\n",
                    )
                }
            }
        }
        return true
    }

     */
}
