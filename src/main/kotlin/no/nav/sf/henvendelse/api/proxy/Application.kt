package no.nav.sf.henvendelse.api.proxy

import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import mu.withLoggingContext
import no.nav.security.token.support.core.jwt.JwtToken
import no.nav.sf.henvendelse.api.proxy.Cache.get
import no.nav.sf.henvendelse.api.proxy.handler.TwincallHandler
import no.nav.sf.henvendelse.api.proxy.httpclient.noProxy
import no.nav.sf.henvendelse.api.proxy.httpclient.supportProxy
import no.nav.sf.henvendelse.api.proxy.token.AccessTokenHandler
import no.nav.sf.henvendelse.api.proxy.token.DefaultAccessTokenHandler
import no.nav.sf.henvendelse.api.proxy.token.DefaultTokenValidator
import no.nav.sf.henvendelse.api.proxy.token.Statistics
import no.nav.sf.henvendelse.api.proxy.token.TokenValidator
import no.nav.sf.henvendelse.api.proxy.token.getAzpName
import no.nav.sf.henvendelse.api.proxy.token.getNAVIdent
import no.nav.sf.henvendelse.api.proxy.token.isMachineToken
import no.nav.sf.henvendelse.api.proxy.token.isNavOBOToken
import org.http4k.core.Headers
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.ResourceLoader.Companion.Classpath
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.routing.static
import org.http4k.server.ApacheServer
import org.http4k.server.Http4kServer
import org.http4k.server.asServer
import java.io.File
import kotlin.system.measureTimeMillis

// Common headers from calling apps. Added to logging for traceability:
const val HEADER_X_REQUEST_ID = "X-Request-ID"
const val HEADER_NAV_CALL_ID = "Nav-Call-Id"
const val HEADER_NAV_CONSUMER_ID = "Nav-Consumer-Id"

// Required in forward request by salesforce api:
const val HEADER_AUTHORIZATION = "Authorization"
const val HEADER_X_CORRELATION_ID = "X-Correlation-ID"
const val HEADER_X_ACTING_NAV_IDENT = "X-ACTING-NAV-IDENT"

const val API_BASE_PATH = "/api"
const val APEX_REST_BASE_PATH = "/services/apexrest"

val isDev: Boolean = env(config_DEPLOY_CLUSTER) == "dev-fss" || env(config_DEPLOY_CLUSTER) == "dev-gcp"
val isGcp: Boolean = env(config_DEPLOY_CLUSTER) == "dev-gcp" || env(config_DEPLOY_CLUSTER) == "prod-gcp"

class Application(
    private val tokenValidator: TokenValidator = DefaultTokenValidator(),
    private val accessTokenHandler: AccessTokenHandler = DefaultAccessTokenHandler(),
    private val devContext: Boolean = isDev,
    private val client: HttpHandler = if (isGcp) noProxy() else supportProxy(),
    private val twincallsEnabled: Boolean = env(config_TWINCALL) == "ON",
    private val twincallHandler: TwincallHandler = TwincallHandler(accessTokenHandler, client, devContext)
) {
    private val log = KotlinLogging.logger { }
    private var lifeTimeCallIndex = 0L

    // List of headers that will not be forwarded
    private val restrictedHeaders = listOf("host", "content-length", "user-agent", "authorization")

    fun start() {
        log.info { "Starting ${if (devContext) "DEV" else "PROD"} - twincalls enabled: $twincallsEnabled" }
        apiServer(8080).start()
        try {
            Cache.get("dummy", "dummy")
        } catch (e: Exception) {
            File("/tmp/CacheTestException").writeText(e.stackTraceToString())
        }
        refreshLoop() // Refresh access token and cache in advance outside of calls
    }

    fun apiServer(port: Int): Http4kServer = api().asServer(ApacheServer(port))

    fun api(): HttpHandler = routes(
        "$API_BASE_PATH/{rest:.*}" bind ::handleApiRequest,
        "/static" bind static(Classpath("/static")),
        "/internal/isAlive" bind Method.GET to { Response(Status.OK) },
        "/internal/isReady" bind Method.GET to { Response(Status.OK) },
        "/internal/metrics" bind Method.GET to Metrics.metricsHandler
    )

    tailrec fun refreshLoop() {
        runBlocking { delay(60000) } // 1 min
        if (twincallsEnabled) twincallHandler.performTestCalls()
        accessTokenHandler.refreshToken()
        runBlocking { delay(900000) } // 15 min

        refreshLoop()
    }

    fun handleApiRequest(request: Request): Response {
        val callIndex = lifeTimeCallIndex++
        val stats = Statistics()

        withLoggingContext(
            mapOf(
                HEADER_X_REQUEST_ID to (request.header(HEADER_X_REQUEST_ID) ?: ""),
                HEADER_NAV_CALL_ID to (request.header(HEADER_NAV_CALL_ID) ?: ""),
                HEADER_X_CORRELATION_ID to (request.header(HEADER_X_CORRELATION_ID) ?: ""),
                HEADER_NAV_CONSUMER_ID to (request.header(HEADER_NAV_CONSUMER_ID) ?: ""),
                "callIndex" to callIndex.toString()
            )
        ) {
            log.info { "Incoming call ${request.uri}" }
            val firstValidToken = tokenValidator.firstValidToken(request, stats)
            if (!firstValidToken.isPresent) {
                log.warn { "Proxy: Not authorized" }
                return Response(Status.UNAUTHORIZED).body("Not authorized")
            } else if (!request.uri.path.contains("/kodeverk/") && firstValidToken.get().isMachineToken()) {
                // Request is authorized with a machine token instead of an obo token, we only allow access to
                // kodeverk endpoints in that case:
                log.warn { "Proxy: Machine token authorization not sufficient" }
                return Response(Status.FORBIDDEN).body("Machine token authorization not sufficient")
            } else {
                val navIdent = fetchNavIdent(firstValidToken.get(), stats)

                try {
                    Metrics.issuer.labels(firstValidToken.get().issuer).inc()
                } catch (e: java.lang.Exception) {
                    log.error { "Failed to fetch issuer from token" }
                }

                if (navIdent.isEmpty()) {
                    File("/tmp/message-missing").writeText("($callIndex)" + request.toMessage())
                    return Response(Status.BAD_REQUEST).body("Missing Nav identifier")
                } else {
                    val forwardRequest = createForwardRequest(request, navIdent, stats)

                    var henvendelseCacheResponse: Response? = null
                    if (request.uri.path.contains("henvendelseliste")) {
                        val aktorId = request.query("aktorid") ?: "null"
                        // Cache.doAsyncGet(aktorId, "henvendelseliste")
                        henvendelseCacheResponse = get(aktorId, "henvendelseliste")
                    }

                    val response = invokeRequest(forwardRequest, stats)

                    if (request.uri.path.contains("henvendelseliste") && response.status.code == 200) {
                        val aktorId = request.query("aktorid") ?: "null"
                        Cache.doAsyncPut(aktorId, response.bodyString(), "henvendelseliste")
                    }

                    if (response.status.successful) {
                        val pathLabel = listOf(
                            "/behandling" to "behandling",
                            "/ny/samtalereferat" to "samtalereferat",
                            "/ny/melding" to "melding"
                        ).firstOrNull { request.uri.path.contains(it.first) }?.second.orEmpty()

                        if (pathLabel.isNotEmpty()) {
                            // Parse aktorId from request:
                            try {
                                val jsonObject = JsonParser.parseString(request.bodyString()).asJsonObject
                                val aktorId = jsonObject.get("aktorId").asString
                                Cache.appendCacheLog("Parsed aktorId $aktorId on call to ${request.uri.path}")
                                Cache.doAsyncDelete(aktorId, pathLabel)
                            } catch (e: Exception) {
                                File("/tmp/failedRequestParsing").writeText("On ${request.uri.path}\n" + e.stackTraceToString())
                            }
                        } else if (request.uri.path.contains("journal")) {
                            // Parse aktorId from response:
                            try {
                                val jsonObject = JsonParser.parseString(response.bodyString()).asJsonObject
                                val aktorId = jsonObject.get("aktorId").asString
                                Cache.appendCacheLog("Parsed aktorId $aktorId on response from ${request.uri.path}")
                                Cache.doAsyncDelete(aktorId, "journal")
                            } catch (e: Exception) {
                                File("/tmp/failedResponseParsing").writeText("On ${request.uri.path}\n" + e.stackTraceToString())
                            }
                        } else if (request.uri.path.contains("meldingskjede")) {
                            try {
                                val kjedeId = request.query("kjedeId")!!
                                Cache.doAsyncDeleteByKjedeId(kjedeId, "lukk")
                            } catch (e: Exception) {
                                File("/tmp/failedLukkParsing").writeText("On ${request.uri.path}\n" + e.stackTraceToString())
                            }
                        }
                    }

                    stats.logAndUpdateMetrics(response.status.code, forwardRequest.uri, forwardRequest, response)

                    withLoggingContext(
                        mapOf(
                            "status" to response.status.code.toString(),
                            "event.duration" to stats.latestCallElapsedTime.toString(),
                            "src" to stats.srcLabel,
                            "uri" to forwardRequest.uri.toString()
                        )
                    ) {
                        log.info {
                            "Summary : status=${response.status.code}, call_ms=${stats.latestCallElapsedTime}, " +
                                "method=${forwardRequest.method.name}, uri=${forwardRequest.uri}, src=${stats.srcLabel}"
                        }
                    }

                    if (henvendelseCacheResponse != null && henvendelseCacheResponse.status.code == 200) {
                        val cache = henvendelseCacheResponse.bodyString()
                        val sf = response.bodyString()
                        val journalPostIdNullsSF = JsonComparator.numberOfJournalPostIdNull(sf)
                        val journalPostIdNullsCache = JsonComparator.numberOfJournalPostIdNull(cache)
                        val fnrFieldsSF = JsonComparator.numberOfFnrFields(sf)
                        val fnrFieldsCache = JsonComparator.numberOfFnrFields(cache)
                        val moreFnrsInSF = fnrFieldsSF - fnrFieldsCache
                        if (sf == cache) {
                            Metrics.cacheControl.labels("success", "", journalPostIdNullsSF.toString(), moreFnrsInSF.toString()).inc()
                        } else if (JsonComparator.jsonEquals(sf, cache)) {
                            Metrics.cacheControl.labels("success", "", journalPostIdNullsSF.toString(), moreFnrsInSF.toString()).inc()
                        } else if (moreFnrsInSF != 0) {
                            Metrics.cacheControl.labels("fail", "henvendelser diff (fnr)", journalPostIdNullsSF.toString(), moreFnrsInSF.toString()).inc()
                        } else if (journalPostIdNullsCache > journalPostIdNullsSF) {
                            Metrics.cacheControl.labels("fail", "unset journalpostId", journalPostIdNullsSF.toString(), moreFnrsInSF.toString()).inc()
                        } else {
                            val cacheLines = henvendelseCacheResponse.bodyString().lines()
                            val responseLines = response.bodyString().lines()

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
                            val type = if (avsluttetDatoCount == 1 && sistEndretAvCount == 1) {
                                "Avsluttet"
                            } else if (journalpostIdCount == 1 && journalfortDatoCount == 1) {
                                "journalpostId"
                            } else {
                                "Undefined"
                            }
                            Metrics.cacheControl.labels("fail", type, journalPostIdNullsSF.toString(), moreFnrsInSF.toString()).inc()
                            File("/tmp/latestCacheMismatchResponseCache-$type").writeText(henvendelseCacheResponse.toMessage())
                            File("/tmp/latestCacheMismatchResponseSF-$type").writeText(response.toMessage())
                            File("/tmp/latestCacheMismatchMismatches-$type").writeText("")
                            for ((i, pair) in cacheLines.zip(responseLines).withIndex()) {
                                if (pair.first != pair.second) {
                                    File("/tmp/latestCacheMismatchMismatches-$type").appendText(
                                        "Mismatch at line $i:\n" +
                                            "CACHE: ${pair.first}\n" +
                                            "SF: ${pair.second}\n\n"
                                    )
                                }
                            }
                        }
                    }

                    // Fix: We remove introduction of a standard cookie (BrowserId) from salesforce response that is not used and
                    //      creates noise in clients due to cookie source mismatch.
                    return response.removeHeader("Set-Cookie")
                }
            }
        }
    }

    private fun fetchNavIdent(token: JwtToken, tokenFetchStats: Statistics): String {
        tokenFetchStats.srcLabel = token.getAzpName()
        return if (token.isNavOBOToken()) {
            Metrics.callSource.labels("obo-${tokenFetchStats.srcLabel}").inc()
            token.getNAVIdent()
        } else if (token.isMachineToken()) {
            Metrics.callSource.labels("m2m-${tokenFetchStats.srcLabel}").inc()
            tokenFetchStats.machine = true
            token.getAzpName()
        } else {
            log.warn { "Not able do deduce navIdent from request" }
            ""
        }
    }

    private fun createForwardRequest(request: Request, navIdent: String, tokenFetchStats: Statistics): Request {
        // Measure x-forwarded-host header to determine if requests are coming from ingress or service discovery
        try {
            if (request.header("x-forwarded-host") != null) {
                Metrics.forwardedHost.labels(request.header("x-forwarded-host")).inc()
            } else {
                Metrics.forwardedHost.labels("service discovery").inc()
            }
        } catch (e: Exception) {
            log.error { "Failed metric measure forwarded-host" }
        }

        // Measure a refresh of accessToken if needed
        tokenFetchStats.elapsedTimeAccessTokenRequest = measureTimeMillis { accessTokenHandler.accessToken }

        // Drop API_BASE_PATH from url and replace with salesforce path
        val dstUrl = "${accessTokenHandler.instanceUrl}$APEX_REST_BASE_PATH${request.uri.toString().removePrefix(API_BASE_PATH)}"
        val headers: Headers =
            request.headers.filter { !restrictedHeaders.contains(it.first.lowercase()) } +
                listOf(
                    HEADER_AUTHORIZATION to "Bearer ${accessTokenHandler.accessToken}",
                    HEADER_X_ACTING_NAV_IDENT to navIdent
                )

        return Request(request.method, dstUrl).headers(headers).body(request.body)
    }

    private fun invokeRequest(request: Request, tokenFetchStats: Statistics): Response {
        lateinit var response: Response
        tokenFetchStats.latestCallElapsedTime = measureTimeMillis {
            response =
                if (twincallsEnabled && request.method == Method.GET) {
                    twincallHandler.performTwinCall(request)
                } else client(request)
        }
        return response
    }
}
