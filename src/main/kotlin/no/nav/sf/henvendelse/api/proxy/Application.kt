package no.nav.sf.henvendelse.api.proxy

import io.prometheus.client.exporter.common.TextFormat
import java.io.File
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.system.measureTimeMillis
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

    fun start() { log.info { "Starting" }
        apiServer(NAIS_DEFAULT_PORT).start()
        AccessTokenHandler.refreshLoop() // Refresh access token outside of calls
    }

    fun apiServer(port: Int): Http4kServer = api().asServer(Netty(port))

    fun api(): HttpHandler = routes(
        "/static" bind static(Classpath("/static")),
        "/api/{rest:.*}" bind { req: Request ->
            val xCorrelationId = req.header("X-Correlation-ID") ?: ""
            val xRequestId = req.header("X-Request-ID") ?: ""
            val navCallId = req.header("Nav-Call-Id") ?: ""
            withLoggingContext(mapOf("Request-Id" to xRequestId, "Call-Id:" to navCallId, "Correlation-Id" to xCorrelationId)) {
                callIndex++
                log.info { "Incoming call ($callIndex) ${req.uri}" }
                FetchStats.resetFetchVars()
                val firstValidToken = TokenValidator.firstValidToken(req)
                if (!firstValidToken.isPresent) {
                    Response(Status.UNAUTHORIZED).body("Not authorized")
                } else {
                    File("/tmp/message").writeText(req.toMessage())
                    val token = firstValidToken.get()

                    var oboToken = ""
                    var NAVident = token.jwtTokenClaims.get(claim_NAVident)?.toString() ?: ""

                    val azpName = token.jwtTokenClaims.get(claim_azp_name)?.toString() ?: ""
                    val azp = token.jwtTokenClaims.get(claim_azp)?.toString() ?: ""
                    val sub = token.jwtTokenClaims.get(claim_sub)?.toString() ?: ""
                    val navIdentHeader = req.header("Nav-Ident")

                    // Case insensitive fetch - dialogv1-proxy sends header as X-Correlation-Id
                    // Needs to be translated to X-Correlation-ID in call to salesforce

                    val navConsumerId = req.header("nav-consumer-id") ?: ""
                    val xProxyRef = req.header("X-Proxy-Ref") ?: ""
                    val isMachineToken = token.isMachineToken(callIndex)
                    var src = ""

                    if (NAVident.isNotEmpty()) { // Received NAVident from claim in token - we know it is an azure obo-token
                        log.info { "Ident from obo ($callIndex)" }
                        oboToken = OboTokenExchangeHandler.exchange(token).tokenAsString
                        FetchStats.registerCallSource("obo-$azpName")
                        src = azpName
                        File("/tmp/message-obo").writeText("($callIndex)" + req.toMessage())
                    } else if (navIdentHeader != null) { // Request contains NAVident from header (but not in token) - we know it is a nais serviceuser token
                        log.info { "Ident from header ($callIndex) - machinetoken $isMachineToken - from $navConsumerId $xProxyRef - token with azpname $azpName, azp $azp, sub $sub" }
                        NAVident = navIdentHeader
                        FetchStats.registerCallSource("header-$navConsumerId.$xProxyRef")
                        src = "$navConsumerId.$xProxyRef"
                        File("/tmp/message-header").writeText("($callIndex)" + req.toMessage())
                    } else if (azpName.isNotEmpty()) { // We know token is azure token but not an obo-token - we know it is an azure m2m-token
                        log.info { "Ident as machine source ($callIndex) - machinetoken $isMachineToken - from $navConsumerId $xProxyRef - token with azpname $azpName, azp $azp, sub $sub" }
                        NAVident = azpName
                        FetchStats.registerCallSource("m2m-$navConsumerId.$xProxyRef")
                        src = "$navConsumerId.$xProxyRef"
                        File("/tmp/message-m2m").writeText("($callIndex)" + req.toMessage())
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

                        File("/tmp/forwardmessage").writeText(request.toMessage())
                        lateinit var response: Response
                        FetchStats.latestCallElapsedTime =
                            measureTimeMillis {
                                response = client.value(request)
                            }
                        FetchStats.logStats(response.status.code, req.uri, callIndex)
                        log.info { "Summary ($callIndex) : status=${response.status.code}, call_ms=${FetchStats.latestCallElapsedTime}, method=${req.method.name}, uri=${req.uri}, src=$src" }

                        if (response.status.code != 200) {
                            File("/tmp/failedresponse").appendText("${DateTimeFormatter
                                .ofPattern("yyyy-MM-dd HH:mm:ss")
                                .withZone(ZoneId.of("CET"))
                                .format(Instant.now())}\n" +
                                    "${response.status.code} ${response.status} ${req.uri}\n" +
                                    "${request.headers.filter{ it.first.toLowerCase() != "authorization" }.map { "${it.first} : ${it.second}"}.joinToString("\n")}\n\n"
                            )
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
