package no.nav.sf.henvendelse.api.proxy

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import mu.withLoggingContext
import no.nav.security.token.support.core.jwt.JwtToken
import no.nav.sf.henvendelse.api.proxy.handler.TwincallHandler
import no.nav.sf.henvendelse.api.proxy.httpclient.supportProxy
import no.nav.sf.henvendelse.api.proxy.token.AccessTokenHandler
import no.nav.sf.henvendelse.api.proxy.token.DefaultAccessTokenHandler
import no.nav.sf.henvendelse.api.proxy.token.DefaultTokenValidator
import no.nav.sf.henvendelse.api.proxy.token.TokenFetchStatistics
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

const val HEADER_NAV_IDENT = "Nav-Ident"
const val HEADER_X_REQUEST_ID = "X-Request-ID"
const val HEADER_NAV_CALL_ID = "Nav-Call-Id"
const val HEADER_NAV_CONSUMER_ID = "Nav-Consumer-Id"

// Required by salesforce api:
const val HEADER_AUTHORIZATION = "Authorization"
const val HEADER_X_CORRELATION_ID = "X-Correlation-ID"
const val HEADER_X_ACTING_NAV_IDENT = "X-ACTING-NAV-IDENT"

class Application(
    private val tokenValidator: TokenValidator = DefaultTokenValidator(),
    private val accessTokenHandler: AccessTokenHandler = DefaultAccessTokenHandler(),
    private val client: HttpHandler = supportProxy(env(env_HTTPS_PROXY)),
    private val devContext: Boolean = env(config_DEPLOY_CLUSTER) == "dev-fss",
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
        refreshLoop() // Refresh access token and cache in advance outside of calls
    }

    fun apiServer(port: Int): Http4kServer = api().asServer(ApacheServer(port))

    fun api(): HttpHandler = routes(
        "/api/{rest:.*}" bind ::handleApiRequest,
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
        val tokenFetchStats = TokenFetchStatistics()

        withLoggingContext(
            mapOf(
                HEADER_X_REQUEST_ID to (request.header(HEADER_X_CORRELATION_ID) ?: ""),
                HEADER_NAV_CALL_ID to (request.header(HEADER_NAV_CALL_ID) ?: ""),
                HEADER_X_CORRELATION_ID to (request.header(HEADER_X_CORRELATION_ID) ?: ""),
                "callIndex" to callIndex.toString()
            )
        ) {
            log.info { "Incoming call ${request.uri}" }
            val firstValidToken = tokenValidator.firstValidToken(request, tokenFetchStats)
            if (!firstValidToken.isPresent) {
                return Response(Status.UNAUTHORIZED).body("Not authorized")
            } else if (!request.uri.path.contains("/kodeverk/") && firstValidToken.get().isMachineToken()) {
                // Request is authorized with a machine token instead of an obo token, we only allow access to
                // kodeverk endpoints in that case
                return Response(Status.FORBIDDEN).body("Machine token authorization not sufficient")
            } else {
                val navIdent = fetchNavIdent(firstValidToken.get(), tokenFetchStats)

                if (navIdent.isEmpty()) {
                    File("/tmp/message-missing").writeText("($callIndex)" + request.toMessage())
                    return Response(Status.BAD_REQUEST).body("Missing Nav identifier")
                } else {
                    val forwardRequest = createForwardRequest(request, navIdent, tokenFetchStats)

                    val response = invokeRequest(forwardRequest, tokenFetchStats)

                    tokenFetchStats.logStats(response.status.code, forwardRequest.uri)

                    withLoggingContext(
                        mapOf(
                            "status" to response.status.code.toString(),
                            "processing_time" to tokenFetchStats.latestCallElapsedTime.toString(),
                            "src" to tokenFetchStats.srcLabel,
                            "uri" to forwardRequest.uri.toString()
                        )
                    ) {
                        log.info {
                            "Summary : status=${response.status.code}, call_ms=${tokenFetchStats.latestCallElapsedTime}, " +
                                "method=${forwardRequest.method.name}, uri=${forwardRequest.uri}, src=${tokenFetchStats.srcLabel}"
                        }
                    }

                    // Fix: We remove introduction of a standard cookie (BrowserId) from salesforce response that is not used and
                    //      creates noise in clients due to cookie source mismatch.
                    return response.removeHeader("Set-Cookie")
                }
            }
        }
    }

    private fun fetchNavIdent(token: JwtToken, tokenFetchStats: TokenFetchStatistics): String =
        if (token.isNavOBOToken()) {
            tokenFetchStats.srcLabel = token.getAzpName()
            Metrics.callSource.labels("obo-${tokenFetchStats.srcLabel}").inc()
            token.getNAVIdent()
        } else if (token.isMachineToken()) {
            tokenFetchStats.srcLabel = token.getAzpName()
            // tokenFetchStats.srcLabel = request.header(HEADER_NAV_CONSUMER_ID) ?: "Unidentified"
            Metrics.callSource.labels("m2m-${tokenFetchStats.srcLabel}").inc()
            tokenFetchStats.machine = true
            token.getAzpName()
        } else {
            log.warn { "Not able do deduce navIdent from request" }
            ""
        }

    private fun createForwardRequest(request: Request, navIdent: String, tokenFetchStats: TokenFetchStatistics): Request {
        // Refresh accessToken if needed
        tokenFetchStats.elapsedTimeAccessTokenRequest = measureTimeMillis { accessTokenHandler.accessToken }

        // Drop first 4 chars = "/api" from url and replace with salesforce path
        val dstUrl = "${accessTokenHandler.instanceUrl}/services/apexrest${request.uri.toString().drop(4)}"
        val headers: Headers =
            request.headers.filter { !restrictedHeaders.contains(it.first.lowercase()) } +
                listOf(
                    HEADER_AUTHORIZATION to "Bearer ${accessTokenHandler.accessToken}",
                    HEADER_X_ACTING_NAV_IDENT to navIdent
                )

        return Request(request.method, dstUrl).headers(headers).body(request.body)
    }

    private fun invokeRequest(request: Request, tokenFetchStats: TokenFetchStatistics): Response {
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
