package no.nav.sf.henvendelse.api.proxy

import io.prometheus.client.exporter.common.TextFormat
import java.io.File
import java.io.StringWriter
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import mu.withLoggingContext
import no.nav.sf.henvendelse.api.proxy.token.AccessTokenHandler
import no.nav.sf.henvendelse.api.proxy.token.FetchStats
import no.nav.sf.henvendelse.api.proxy.token.OboTokenExchangeHandler
import no.nav.sf.henvendelse.api.proxy.token.TokenValidator
import org.http4k.client.ApacheClient
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

const val NAIS_DEFAULT_PORT = 8080
const val NAIS_ISALIVE = "/internal/isAlive"
const val NAIS_ISREADY = "/internal/isReady"
const val NAIS_METRICS = "/internal/metrics"

class Application {
    private val log = KotlinLogging.logger { }
    private var callIndex = 0L

    private val restrictedHeaders = listOf("host", "content-length", "user-agent", "authorization", "x-correlation-id")

    private val client: Lazy<HttpHandler> = lazy { ApacheClient.supportProxy(System.getenv("HTTPS_PROXY")) }

    private val clientWOProxy: Lazy<HttpHandler> = lazy { ApacheClient.withoutProxy() }

    val devContext = System.getenv("CONTEXT") == "DEV"

    fun start() { log.info { "Starting ${if (devContext) "DEV" else "PROD"}" }
        apiServer(NAIS_DEFAULT_PORT).start()
        refreshLoop() // Refresh access token and cache outside of calls
    }

    tailrec fun refreshLoop() {
        runBlocking { delay(60000) } // 1 min
        if (devContext) try { performTestCalls() } catch (e: Exception) { log.warn { "Exception at test call, ${e.message}" } }
        AccessTokenHandler.refreshToken()
        OboTokenExchangeHandler.refreshCache()
        runBlocking { delay(900000) } // 15 min
        refreshLoop()
    }

    fun performTestCalls() {
        val fetchStats = FetchStats()

        val dstUrl = "${AccessTokenHandler.instanceUrl}/services/apexrest/api/henvendelseinfo/henvendelseliste?aktorid=2755132512806"
        val headers: Headers =
                    listOf(
                        Pair("Authorization", "Bearer ${AccessTokenHandler.accessToken}"),
                        Pair("X-ACTING-NAV-IDENT", "H159337"),
                        Pair("X-Correlation-ID", "testcall")
                    )
        val request = Request(Method.GET, dstUrl).headers(headers)
        lateinit var response: Response
        fetchStats.latestCallElapsedTime =
            measureTimeMillis {
                response = client.value(request)
            }
        log.info { "Testcall performed, call_ms = ${fetchStats.latestCallElapsedTime}" }
        File("/tmp/latesttestcall").writeText("call_ms = ${fetchStats.latestCallElapsedTime}\nResponse:\n${response.toMessage()}")

        val request2 = Request(Method.GET, dstUrl).headers(headers)
        lateinit var response2: Response
        fetchStats.latestCallElapsedTime =
            measureTimeMillis {
                response2 = clientWOProxy.value(request2)
            }
        log.info { "Testcall wo p, performed, call_ms = ${fetchStats.latestCallElapsedTime}" }
        File("/tmp/latesttestcallwoproxy").writeText("call_ms = ${fetchStats.latestCallElapsedTime}\nResponse:\n${response2.toMessage()}")
    }

    fun apiServer(port: Int): Http4kServer = api().asServer(Netty(port))

    fun api(): HttpHandler = routes(
        "/static" bind static(Classpath("/static")),
        "/api/{rest:.*}" bind { req: Request ->
            val fetchStats = FetchStats()
            val xCorrelationId = req.header("X-Correlation-ID") ?: ""
            val xRequestId = req.header("X-Request-ID") ?: ""
            val navCallId = req.header("Nav-Call-Id") ?: ""
            withLoggingContext(mapOf("Request-Id" to xRequestId, "Call-Id" to navCallId, "Correlation-Id" to xCorrelationId)) {
                callIndex++
                log.info { "Incoming call ($callIndex) ${req.uri}" }
                val firstValidToken = TokenValidator.firstValidToken(req)
                fetchStats.elapsedTimeTokenValidation = TokenValidator.latestValidationTime
                if (!firstValidToken.isPresent) {
                    Response(Status.UNAUTHORIZED).body("Not authorized")
                } else {
                    // File("/tmp/message").writeText(req.toMessage())
                    val token = firstValidToken.get()

                    var oboToken = ""
                    var NAVident = token.jwtTokenClaims.get(claim_NAVident)?.toString() ?: ""

                    val azpName = token.jwtTokenClaims.get(claim_azp_name)?.toString() ?: ""
                    val navIdentHeader = req.header("Nav-Ident")

                    // Case insensitive fetch - dialogv1-proxy sends header as X-Correlation-Id
                    // Needs to be translated to X-Correlation-ID in call to salesforce

                    val navConsumerId = req.header("nav-consumer-id") ?: ""
                    val xProxyRef = req.header("X-Proxy-Ref") ?: ""
                    val isMachineToken = token.isMachineToken(callIndex)
                    var src = ""

                    if (NAVident.isNotEmpty()) { // Received NAVident from claim in token - we know it is an azure obo-token
                        src = azpName
                        log.info { "Ident from obo ($callIndex) src=$azpName" }
                        fetchStats.elapsedTimeOboHandling = measureTimeMillis {
                            // oboToken = OboTokenExchangeHandler.exchange(token).tokenAsString
                        }
                        fetchStats.registerCallSource("obo-$azpName")
                        // File("/tmp/message-obo").writeText("($callIndex)" + req.toMessage())
                    } else if (navIdentHeader != null) { // Request contains NAVident from header (but not in token) - we know it is a nais serviceuser token
                        src = "$navConsumerId.$xProxyRef"
                        log.info { "Ident from header ($callIndex) - machinetoken $isMachineToken - src=$src" }
                        NAVident = navIdentHeader
                        fetchStats.registerCallSource("header-$navConsumerId.$xProxyRef")
                        // File("/tmp/message-header").writeText("($callIndex)" + req.toMessage())
                    } else if (azpName.isNotEmpty()) { // We know token is azure token but not an obo-token - we know it is an azure m2m-token
                        src = "$navConsumerId.$xProxyRef"
                        log.info { "Ident as machine source ($callIndex) - machinetoken $isMachineToken - src=$src" }
                        NAVident = azpName
                        fetchStats.registerCallSource("m2m-$navConsumerId.$xProxyRef")
                        // File("/tmp/message-m2m").writeText("($callIndex)" + req.toMessage())
                    }

                    if (NAVident.isEmpty()) {
                        File("/tmp/message-missing").writeText("($callIndex)" + req.toMessage())
                        Response(Status.BAD_REQUEST).body("Missing Nav identifier ($callIndex)")
                    } else {
                        val dstUrl = "${AccessTokenHandler.instanceUrl}/services/apexrest${req.uri.toString().substring(4)}"
                        val oboHeader = if (oboToken.isNotEmpty()) { listOf(Pair("X-Nav-Token", oboToken)) } else { listOf() }
                        val headers: Headers =
                            req.headers.filter { !restrictedHeaders.contains(it.first.toLowerCase()) } +
                                    listOf(
                                        Pair("Authorization", "Bearer ${AccessTokenHandler.accessToken}"),
                                        Pair("X-ACTING-NAV-IDENT", NAVident),
                                        Pair("X-Correlation-ID", xCorrelationId)
                                    ) + oboHeader
                        val request = Request(req.method, dstUrl).headers(headers).body(req.body)

                        // File("/tmp/forwardmessage").writeText(request.toMessage())
                        lateinit var response: Response
                        fetchStats.latestCallElapsedTime =
                            measureTimeMillis {
                                response = client.value(request)
                            }
                        try {
                            fetchStats.logStats(response.status.code, req.uri, callIndex)
                        } catch (e: Exception) {
                            log.error { "Failed to update metrics:" + e.message }
                        }
                        withLoggingContext(mapOf("status" to response.status.code.toString(), "processing_time" to fetchStats.latestCallElapsedTime.toString(), "call_over_three" to fetchStats.latestCallTimeSlow().toString(), "src" to src, "uri" to req.uri.toString())) {
                            log.info { "Summary ($callIndex) : status=${response.status.code}, call_ms=${fetchStats.latestCallElapsedTime}, call_warn=${fetchStats.latestCallTimeSlow()}, method=${req.method.name}, uri=${req.uri}, src=$src" }
                        }
                        response
                    }
                }
            }
        },
        NAIS_ISALIVE bind Method.GET to { Response(Status.OK) },
        NAIS_ISREADY bind Method.GET to { Response(Status.OK) },
        NAIS_METRICS bind Method.GET to {
            runCatching {
                StringWriter().let { str ->
                    TextFormat.write004(str, Metrics.cRegistry.metricFamilySamples())
                    str
                }.toString()
            }
                .onFailure {
                    log.error { "/prometheus failed writing metrics - ${it.localizedMessage}" }
                }
                .getOrDefault("").let {
                    if (it.isNotEmpty()) Response(Status.OK).body(it) else Response(Status.NO_CONTENT)
                }
        }
    )
}
