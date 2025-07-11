package no.nav.sf.henvendelse.api.proxy

import com.google.gson.JsonParser
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import mu.withLoggingContext
import no.nav.security.token.support.core.jwt.JwtToken
import no.nav.sf.henvendelse.api.proxy.Cache.get
import no.nav.sf.henvendelse.api.proxy.handler.TwincallHandler
import no.nav.sf.henvendelse.api.proxy.httpclient.enforceHttp1_1
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
import org.http4k.core.Body
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
import org.http4k.server.Http4kServer
import org.http4k.server.Netty
import org.http4k.server.asServer
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
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

val useHenvendelseListeCache = env(secret_USE_CACHE) == "true"

class Application(
    private val tokenValidator: TokenValidator = DefaultTokenValidator(),
    private val accessTokenHandler: AccessTokenHandler = DefaultAccessTokenHandler(),
    private val devContext: Boolean = isDev,
    val client: HttpHandler = if (isGcp) noProxy() else supportProxy(),
    private val twincallsEnabled: Boolean = env(config_TWINCALL) == "ON",
    private val twincallHandler: TwincallHandler = TwincallHandler(accessTokenHandler, client, devContext)
) {
    private val log = KotlinLogging.logger { }
    private var lifeTimeCallIndex = 0L

    // List of headers that will not be forwarded
    private val restrictedHeaders = listOf("host", "content-length", "user-agent", "authorization", "x-correlation-id")

    fun start() {
        log.info { "Starting ${if (devContext) "DEV" else "PROD"} - twincalls enabled: $twincallsEnabled, use cache $useHenvendelseListeCache, enforce 1.1 $enforceHttp1_1" }
        apiServer(8080).start()
        try {
            Cache.get("dummy", "dummy")
        } catch (e: Exception) {
            File("/tmp/CacheTestException").writeText(e.stackTraceToString())
        }
        refreshLoop() // Refresh access token and cache in advance outside of calls
    }

    fun apiServer(port: Int): Http4kServer = api().asServer(Netty(port))

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
            if (firstValidToken == null) {
                log.warn { "Proxy: Not authorized" }
                return Response(Status.UNAUTHORIZED).body("Not authorized")
            } else if (!request.uri.path.contains("/kodeverk/") && firstValidToken.isMachineToken()) {
                // Request is authorized with a machine token instead of an obo token, we only allow access to
                // kodeverk endpoints in that case:
                log.warn { "Proxy: Machine token authorization not sufficient" }
                return Response(Status.FORBIDDEN).body("Machine token authorization not sufficient")
            } else {
                val navIdent = fetchNavIdent(firstValidToken, stats)

                try {
                    Metrics.issuer.labels(firstValidToken.issuer).inc()
                } catch (e: java.lang.Exception) {
                    log.error { "Failed to fetch issuer from token" }
                }

                val chosenTestUser = try {
                    val chosenTestUsers = listOf("Z990454", "Z993068", "N175808")
                    val navIdentOnToken = firstValidToken.jwtTokenClaims.get("NAVident")?.toString()
                    if (chosenTestUsers.contains(navIdentOnToken)) {
                        File("/tmp/testBrukerWasHere-$navIdentOnToken").writeText("true")
                        true
                    } else {
                        false
                    }
                } catch (e: java.lang.Exception) {
                    log.error { "Failed to check navident on token" }
                    false
                }

                val cache = request.query("cache")
                val forceCache = if (chosenTestUser || (cache != null && cache == "true")) {
                    log.info { "Force cache true - due to chosen test user? $chosenTestUser" }
                    true
                } else { false }

                if (navIdent.isEmpty()) {
                    File("/tmp/message-missing").writeText("($callIndex)" + request.toMessage())
                    return Response(Status.BAD_REQUEST).body("Missing Nav identifier")
                } else {
                    val forwardRequest = createForwardRequest(request, navIdent, stats)

                    var aktorIdInFocus = ""

                    var henvendelseCacheResponse: Response? = null
                    if (request.uri.path.contains("henvendelseliste")) {
                        aktorIdInFocus = request.query("aktorid") ?: "null"
                        // Cache.doAsyncGet(aktorId, "henvendelseliste")
                        henvendelseCacheResponse = decompressIfGzipped(get(aktorIdInFocus, "henvendelseliste"))

                        if ((forceCache || useHenvendelseListeCache) && henvendelseCacheResponse.status.code == 200) {
                            stats.logAndUpdateMetrics(
                                henvendelseCacheResponse.status.code,
                                forwardRequest.uri,
                                forwardRequest,
                                henvendelseCacheResponse
                            )

                            withLoggingContext(
                                mapOf(
                                    "status" to henvendelseCacheResponse.status.code.toString(),
                                    "event.duration" to stats.latestCallElapsedTime.toString(),
                                    "src" to stats.srcLabel,
                                    "uri" to forwardRequest.uri.toString(),
                                    "aktorId" to aktorIdInFocus,
                                    "x-acting-nav-ident" to navIdent
                                )
                            ) {
                                log.info {
                                    "Summary : Cached Response, test user $chosenTestUser, status=${henvendelseCacheResponse.status.code}, call_ms=${stats.latestCallElapsedTime}, " +
                                        "method=${forwardRequest.method.name}, uri=${forwardRequest.uri}, src=${stats.srcLabel}"
                                }
                            }
                            val response = Response(Status.OK).header("Content-Type", "application/json").body(henvendelseCacheResponse.body)

                            File("/tmp/responseFromCache").writeText(response.toMessage())
                            return response
                        }
                    }

                    val response = invokeRequest(forwardRequest, stats)

                    if (request.uri.path.contains("henvendelseliste") && response.status.code == 200) {
                        val sfDecompressed = decompressIfGzipped(response)
                        Cache.doAsyncPut(aktorIdInFocus, sfDecompressed.bodyString(), "henvendelseliste")
                        if (henvendelseCacheResponse != null && henvendelseCacheResponse.status.code == 200) {
                            File("/tmp/latestCompare").writeText("REQUEST:\n${request.toMessage()}\n\nCACHE:\n${henvendelseCacheResponse.toMessage()}\n\nSF:\n${sfDecompressed.toMessage()}")
                        }
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
                                aktorIdInFocus = jsonObject.get("aktorId").asString
                                Cache.doAsyncDelete(aktorIdInFocus, pathLabel)
                            } catch (e: Exception) {
                                File("/tmp/failedRequestParsing").writeText("On ${request.uri.path}\n" + e.stackTraceToString())
                            }
                        } else if (request.uri.path.contains("journal")) {
                            // Parse aktorId from response:
                            try {
                                val jsonObject = JsonParser.parseString(decompressIfGzipped(response).bodyString()).asJsonObject
                                aktorIdInFocus = jsonObject.get("aktorId").asString
                                Cache.doAsyncDelete(aktorIdInFocus, "journal")
                            } catch (e: Exception) {
                                File("/tmp/failedResponseParsing").writeText("On ${request.uri.path}\n" + e.stackTraceToString())
                            }
                        } else if (request.uri.path.contains("meldingskjede")) {
                            try {
                                val jsonObject = JsonParser.parseString(decompressIfGzipped(response).bodyString()).asJsonObject
                                aktorIdInFocus = jsonObject.get("aktorId").asString
                                Cache.doAsyncDelete(aktorIdInFocus, "lukk")
                            } catch (e: Exception) {
                                File("/tmp/failedLukkParsing").writeText("On ${request.uri.path}\n" + e.stackTraceToString())
                            }
                        }
                    } else {
                        File("/tmp/failResponse-${response.status.code}").writeText(decompressIfGzipped(response).toMessage())
                    }

                    stats.logAndUpdateMetrics(response.status.code, forwardRequest.uri, forwardRequest, response)

                    withLoggingContext(
                        mapOf(
                            "status" to response.status.code.toString(),
                            "event.duration" to stats.latestCallElapsedTime.toString(),
                            "src" to stats.srcLabel,
                            "uri" to forwardRequest.uri.toString(),
                            "aktorId" to aktorIdInFocus,
                            "x-acting-nav-ident" to navIdent
                        )
                    ) {
                        log.info {
                            "Summary : test user $chosenTestUser, status=${response.status.code}, call_ms=${stats.latestCallElapsedTime}, " +
                                "method=${forwardRequest.method.name}, uri=${forwardRequest.uri}, src=${stats.srcLabel}"
                        }
                    }

                    File("/tmp/latestStatus-${response.status.code}").writeText("FORWARD REQUEST:\n${forwardRequest.toMessage()}\n\nRESPONSE:\n${decompressIfGzipped(response).toMessage()}")

                    if (henvendelseCacheResponse != null && henvendelseCacheResponse.status.code == 200) {
                        if (Cache.compareRealToCache(decompressIfGzipped(response), decompressIfGzipped(henvendelseCacheResponse), aktorIdInFocus)) {
                            GlobalScope.launch {
                                Cache.retryCallVsCache(forwardRequest, aktorIdInFocus)
                            }
                        }
                    }

                    // Fix: We remove introduction of a standard cookie (BrowserId) from salesforce response that is not used and
                    //      creates noise in clients due to cookie source mismatch.
                    return response.removeHeader("Set-Cookie").withoutBlockedHeaders()
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
                    HEADER_X_CORRELATION_ID to (request.header(HEADER_X_CORRELATION_ID) ?: ""), // Make sure expected case on header
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

    // Hop-by-hop headers as defined by RFC 7230 section 6.1.
    // These headers are specific to a single transport-level connection and should not be forwarded by proxies
    private val blockFromResponse = listOf(
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailer",
        "transfer-encoding",
        "content-length",
        "upgrade"
    )

    private fun Response.withoutBlockedHeaders(): Response {
        val filteredHeaders = this.headers.filter { (key, _) -> key.lowercase() !in blockFromResponse }

        val bodyBytes = this.body.stream.use { it.readBytes() }
        return Response(this.status)
            .headers(filteredHeaders)
            .body(Body(ByteBuffer.wrap(bodyBytes)))
    }

    fun decompressIfGzipped(response: Response): Response {
        return if (response.header("Content-Encoding") == "gzip") {
            val decompressed = GZIPInputStream(response.body.stream).bufferedReader().use { it.readText() }
            response
                .removeHeader("Content-Encoding")
                .body(decompressed)
                .header("Content-Length", decompressed.length.toString())
        } else {
            response
        }
    }
}
