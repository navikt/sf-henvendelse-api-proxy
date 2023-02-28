package no.nav.sf.henvendelse.api.proxy

import io.prometheus.client.exporter.common.TextFormat
import java.io.File
import java.io.StringWriter
import java.lang.Integer.max
import kotlin.system.measureTimeMillis
import mu.KotlinLogging
import net.minidev.json.JSONArray
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
import org.http4k.routing.path
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
    private var callTime = 0L

    val restrictedHeaders = listOf("host", "content-length", "authorization")

    private val client: Lazy<HttpHandler> = lazy { ApacheClient.supportProxy(System.getenv("HTTPS_PROXY")) }

    fun start() { log.info { "Starting" }
        apiServer(NAIS_DEFAULT_PORT).start()
        AccessTokenHandler.refreshLoop() // Refresh access token outside of calls
    }

    fun apiServer(port: Int): Http4kServer = api().asServer(Netty(port))

    fun api(): HttpHandler = routes(
        "/static" bind static(Classpath("/static")),
        "/api/{rest:.*}" bind { req: Request ->
            callTime++
            log.info { "Incoming call ($callTime) ${req.uri}" }
            FetchStats.resetFetchVars(callTime)
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
                val navConsumerId = req.header("nav-consumer-id")?.first() ?: ""
                val xProxyRef = req.header("X-Proxy-Ref")?.first() ?: ""

                val rolesClaim = token.jwtTokenClaims.get(claim_roles)
                if (rolesClaim != null) {
                    log.info("Got roles claim class ${rolesClaim.javaClass.name}")
                    val firstRolesClaim = (rolesClaim as JSONArray)[0]
                    log.info("Got roles claim entry $firstRolesClaim")
                    if (firstRolesClaim.toString() == "access_as_application") {
                        File("/tmp/machinetoken").writeText(token.tokenAsString)
                    }
                }

                if (NAVident.isNotEmpty()) {
                    log.info { "Ident from obo ($callTime)" }
                    oboToken = OboTokenExchangeHandler.fetchAzureTokenOBO(token).tokenAsString
                    File("/tmp/message-obo").writeText("($callTime)" + req.toMessage())
                } else if (navIdentHeader != null) {
                    log.info { "Ident from header ($callTime) - from $navConsumerId $xProxyRef - token with azpname $azpName, azp $azp, sub $sub" }
                    NAVident = navIdentHeader
                    File("/tmp/message-header").writeText("($callTime)" + req.toMessage())
                } else if (azpName.isNotEmpty()) {
                    log.info { "Ident as machine source ($callTime) - from $navConsumerId $xProxyRef - token with azpname $azpName, azp $azp, sub $sub" }
                    NAVident = azpName
                    File("/tmp/message-m2m").writeText("($callTime)" + req.toMessage())
                }

                if (NAVident.isEmpty()) {
                    File("/tmp/message-missing").writeText("($callTime)" + req.toMessage())
                    Response(Status.BAD_REQUEST).body("Missing Nav identifier ($callTime)")
                } else {
                    // token.logStatsInTmp()
                    val dstUrl = "${AccessTokenHandler.instanceUrl}/services/apexrest${req.uri.toString().substring(4)}"
                    val oboHeader = if (oboToken.isNotEmpty()) { listOf(Pair("X-Nav-Token", oboToken)) } else { listOf() }
                    val headers: Headers =
                        req.headers.filter { !restrictedHeaders.contains(it.first.toLowerCase()) } +
                                listOf(
                                    Pair("Authorization", "Bearer ${AccessTokenHandler.accessToken}"),
                                    Pair("X-ACTING-NAV-IDENT", NAVident)
                                ) + oboHeader
                    val request = Request(req.method, dstUrl).headers(headers).body(req.body)

                    File("/tmp/forwardmessage").writeText(request.toMessage())
                    lateinit var response: Response
                    val pathStump = req.path("rest")?.let { rest -> rest.substring(0, max(20, rest.length - 1)) } ?: "null"
                    FetchStats.latestCallElapsedTime =
                        measureTimeMillis {
                            response = client.value(request)
                        }
                    FetchStats.callElapsedTime[pathStump] = FetchStats.latestCallElapsedTime
                    FetchStats.logStats(callTime)
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
        }
    )
}
