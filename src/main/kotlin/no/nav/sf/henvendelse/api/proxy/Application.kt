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
import no.nav.sf.henvendelse.api.proxy.token.TokenFetchStats
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

// Required by salesforce:
const val HEADER_AUTHORIZATION = "Authorization"
const val HEADER_X_CORRELATION_ID = "X-Correlation-ID"
const val HEADER_X_ACTING_NAV_IDENT = "X-ACTING-NAV-IDENT"

class Application(
    private val tokenValidator: TokenValidator = DefaultTokenValidator(),
    private val accessTokenHandler: AccessTokenHandler = DefaultAccessTokenHandler(),
    val client: HttpHandler = supportProxy(env(env_HTTPS_PROXY)),
    private val devContext: Boolean = env(config_DEPLOY_CLUSTER) == "dev-fss",
    private val twincallsEnabled: Boolean = env(config_TWINCALL) == "ON",
    private val twincallHandler: TwincallHandler = TwincallHandler(accessTokenHandler, client, devContext)
) {
    private val log = KotlinLogging.logger { }
    private var lifeTimeCallIndex = 0L

    // Headers (lowercase of) that won't be forwarded. Fix: In case of received x-correlation-id it need to be sent as X-Correlation-ID (case sensitive)
    private val restrictedHeaders = listOf("host", "content-length", "user-agent", "authorization", "x-correlation-id")

    fun start() {
        log.info { "Starting ${if (devContext) "DEV" else "PROD"} - twincalls enabled: $twincallsEnabled" }
        apiServer(8080).start()
        refreshLoop() // Refresh access token and cache outside of calls
    }

    fun apiServer(port: Int): Http4kServer = api().asServer(ApacheServer(port))

    fun api(): HttpHandler = routes(
        "/api/{rest:.*}" bind ::handleApiRequest,
        "/static" bind static(Classpath("/static")),
        "/authping" bind ::authPing,
        "/internal/isAlive" bind Method.GET to { Response(Status.OK) },
        "/internal/isReady" bind Method.GET to { Response(Status.OK) },
        "/internal/metrics" bind Method.GET to Metrics.metricsHandler
    )

    tailrec fun refreshLoop() {
        runBlocking { delay(60000) } // 1 min
        if (twincallsEnabled) try { twincallHandler.performTestCalls() } catch (e: Exception) { log.warn { "Exception at test call, ${e.message}" } }
        accessTokenHandler.refreshToken()
        runBlocking { delay(900000) } // 15 min

        refreshLoop()
    }

    fun authPing(req: Request): Response {
        log.info { "Incoming call authping ${req.uri}" }
        val firstValidToken = tokenValidator.firstValidToken(req, TokenFetchStats(req, lifeTimeCallIndex, devContext))
        return Response(Status.OK).body("Auth: ${firstValidToken.isPresent}")
    }

    fun handleApiRequest(request: Request): Response {
        val callIndex = lifeTimeCallIndex++
        val tokenFetchStats = TokenFetchStats(request, callIndex, devContext)

        // Note from fix: X-Correlation-ID (all headers) is read case-insensitive from request
        // dialogv1-proxy sends header as X-Correlation-Id which needs to be translated to X-Correlation-ID in call to salesforce
        val xCorrelationId = request.header(HEADER_X_CORRELATION_ID) ?: ""
        val xRequestId = request.header(HEADER_X_REQUEST_ID) ?: ""
        val navCallId = request.header(HEADER_NAV_CALL_ID) ?: ""

        withLoggingContext(mapOf("Request-Id" to xRequestId, "Call-Id" to navCallId, "correlationId" to xCorrelationId, "callIndex" to callIndex.toString())) {
            log.info { "Incoming call ${request.uri}" }
            val firstValidToken = tokenValidator.firstValidToken(request, tokenFetchStats)
            if (!firstValidToken.isPresent) {
                return Response(Status.UNAUTHORIZED).body("Not authorized")
            } else {
                if (devContext) File("/tmp/message").writeText(request.toMessage())
                val token = firstValidToken.get()

                val navIdent = fetchNavIdent(request, token, tokenFetchStats)

                if (navIdent.isEmpty()) {
                    File("/tmp/message-missing").writeText("($callIndex)" + request.toMessage())
                    return Response(Status.BAD_REQUEST).body("Missing Nav identifier")
                } else {
                    val dstUrl = "${accessTokenHandler.instanceUrl}/services/apexrest${request.uri.toString().substring(4)}" // Remove "/api" from start of url
                    val headers: Headers =
                        request.headers.filter { !restrictedHeaders.contains(it.first.lowercase()) } +
                            listOf(
                                Pair(HEADER_AUTHORIZATION, "Bearer ${accessTokenHandler.accessToken}"),
                                Pair(HEADER_X_ACTING_NAV_IDENT, navIdent),
                                Pair(HEADER_X_CORRELATION_ID, xCorrelationId)
                            )

                    val forwardRequest = Request(request.method, dstUrl).headers(headers).body(request.body)

                    if (devContext) File("/tmp/forwardmessage").writeText(forwardRequest.toMessage())
                    lateinit var response: Response
                    tokenFetchStats.latestCallElapsedTime = measureTimeMillis {
                        response =
                            if (twincallsEnabled && forwardRequest.method == Method.GET) {
                                twincallHandler.performTwinCall(forwardRequest)
                            } else client(forwardRequest)
                    }

                    tokenFetchStats.logStats(response.status.code, forwardRequest.uri)

                    withLoggingContext(
                        mapOf(
                            "status" to response.status.code.toString(),
                            "processing_time" to tokenFetchStats.latestCallElapsedTime.toString(),
                            "src" to tokenFetchStats.srcLabel,
                            "uri" to forwardRequest.uri.toString()
                        )
                    ) {
                        log.info { "Summary : status=${response.status.code}, call_ms=${tokenFetchStats.latestCallElapsedTime}, method=${forwardRequest.method.name}, uri=${forwardRequest.uri}, src=${tokenFetchStats.srcLabel}" }
                    }
                    return response
                }
            }
        }
    }

    fun fetchNavIdent(request: Request, token: JwtToken, tokenFetchStats: TokenFetchStats): String =
        if (token.isNavOBOToken()) {
            tokenFetchStats.registerSourceLabel(token.getAzpName(), "Ident from obo", "obo")
            token.getNAVIdent()
        } else if (token.isMachineToken()) {
            val navConsumerId = request.header("nav-consumer-id") ?: "Unidentified"
            tokenFetchStats.registerSourceLabel(navConsumerId, "Ident as machine source", "m2m")
            token.getAzpName()
        } else if (request.header(HEADER_NAV_IDENT) != null) {
            val navConsumerId = request.header("nav-consumer-id") ?: "Unidentified"
            val xProxyRef = request.header("X-Proxy-Ref") ?: ""
            tokenFetchStats.registerSourceLabel("$navConsumerId.$xProxyRef", "Ident from header", "header")
            request.header(HEADER_NAV_IDENT) ?: ""
        } else {
            log.warn { "Not able do deduce navIdent from request" }
            ""
        }
}
