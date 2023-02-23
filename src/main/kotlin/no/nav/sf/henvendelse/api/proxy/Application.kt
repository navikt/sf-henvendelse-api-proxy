package no.nav.sf.henvendelse.api.proxy

import io.prometheus.client.exporter.common.TextFormat
import java.io.File
import java.io.StringWriter
import java.lang.Integer.max
import kotlin.system.measureTimeMillis
import mu.KotlinLogging
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
import org.http4k.routing.bind
import org.http4k.routing.path
import org.http4k.routing.routes
import org.http4k.server.Http4kServer
import org.http4k.server.Netty
import org.http4k.server.asServer

const val NAIS_DEFAULT_PORT = 8080
const val NAIS_ISALIVE = "/internal/isAlive"
const val NAIS_ISREADY = "/internal/isReady"
const val NAIS_METRICS = "/internal/metrics"

class Application {
    private val log = KotlinLogging.logger { }

    val restrictedHeaders = listOf("host", "content-length", "authorization")

    private val client: Lazy<HttpHandler> = lazy { ApacheClient.supportProxy(System.getenv("HTTPS_PROXY")) }

    fun start() { log.info { "Starting" }
        apiServer(NAIS_DEFAULT_PORT).start()
    }

    fun apiServer(port: Int): Http4kServer = api().asServer(Netty(port))

    fun api(): HttpHandler = routes(
        "/api/{rest:.*}" bind { req: Request ->
            FetchStats.resetFetchVars()
            log.info { "Incoming call ${req.uri}" }
            val firstValidToken = TokenValidator.firstValidToken(req)
            if (!firstValidToken.isPresent) {
                Response(Status.UNAUTHORIZED).body("Not authorized")
            } else {
                File("/tmp/message").writeText(req.toMessage())
                val token = firstValidToken.get()
                var oboToken = ""
                var NAVident = token.jwtTokenClaims.get(claim_NAVident)?.toString() ?: ""
                val azpName = token.jwtTokenClaims.get(claim_azp_name)?.toString()
                val navIdentHeader = req.header("Nav-Ident")

                if (NAVident.isNotEmpty()) {
                    log.info { "Ident from obo" }
                    oboToken = OboTokenExchangeHandler.fetchAzureTokenOBO(token).tokenAsString
                    File("/tmp/message-obo").writeText(req.toMessage())
                } else if (navIdentHeader != null) {
                    log.info { "Ident from header" }
                    NAVident = navIdentHeader
                    File("/tmp/message-header").writeText(req.toMessage())
                } else if (azpName != null) {
                    log.info { "Ident as machine source" }
                    NAVident = azpName
                    File("/tmp/message-m2m").writeText(req.toMessage())
                }

                if (NAVident.isEmpty()) {
                    File("/tmp/message-missing").writeText(req.toMessage())
                    Response(Status.BAD_REQUEST).body("Missing Nav identifier")
                } else {
                    token.logStatsInTmp()
                    val dstUrl = "${AccessTokenHandler.instanceUrl}/services/apexrest${req.uri.toString().substring(4)}"
                    val oboHeader = if (oboToken.isNotEmpty()) {
                        listOf(Pair("X-Nav-Token", oboToken))
                    } else {
                        listOf()
                    }
                    val headers: Headers =
                        req.headers.filter { !restrictedHeaders.contains(it.first.toLowerCase()) } +
                                listOf(
                                    Pair("Authorization", "Bearer ${AccessTokenHandler.accessToken}"),
                                    Pair("X-ACTING-NAV-IDENT", NAVident)
                                ) + oboHeader
                    val request = Request(req.method, dstUrl).headers(headers).body(req.body)

                    File("/tmp/forwardmessage").writeText(request.toMessage())
                    lateinit var response: Response
                    val pathStump =
                        req.path("rest")?.let { rest -> rest.substring(0, max(20, rest.length - 1)) } ?: "null"
                    FetchStats.callTime[pathStump] =
                        measureTimeMillis {
                            response = client.value(request)
                        }
                    FetchStats.logStats()
                    response
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
        },
        "/itest" bind Method.GET to { Response(Status.OK).body("at: ${AccessTokenHandler.accessToken}") }
    )
}
