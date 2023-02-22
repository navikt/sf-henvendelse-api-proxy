package no.nav.sf.henvendelse.api.proxy

import io.prometheus.client.exporter.common.TextFormat
import java.io.File
import java.io.StringWriter
import mu.KotlinLogging
import no.nav.sf.henvendelse.api.proxy.token.AccessTokenHandler
import no.nav.sf.henvendelse.api.proxy.token.OboTokenExchangeHandler
import no.nav.sf.henvendelse.api.proxy.token.TokenValidator
import org.http4k.client.ApacheClient
import org.http4k.core.Headers
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Status.Companion.OK
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.server.Http4kServer
import org.http4k.server.Netty
import org.http4k.server.asServer

const val NAIS_DEFAULT_PORT = 8080
const val NAIS_ISALIVE = "/internal/isAlive"
const val NAIS_ISREADY = "/internal/isReady"
const val NAIS_METRICS = "/internal/metrics"

val restrictedHeaders = listOf("host", "content-length", "authorization")

class Application {
    private val log = KotlinLogging.logger { }

    private val client: Lazy<HttpHandler> = lazy { ApacheClient.supportProxy(System.getenv("HTTPS_PROXY")) }

    fun start() = apiServer(NAIS_DEFAULT_PORT).start()

    fun apiServer(port: Int): Http4kServer = api().asServer(Netty(port))

    fun api(): HttpHandler = routes(
        "/api/{rest:.*}" bind { req: Request ->
            log.info { "Incoming call ${req.uri}" }
            val firstValidToken = TokenValidator.firstValidToken(req)
            if (!firstValidToken.isPresent) {
                Response(Status.UNAUTHORIZED).body("Not authorized")
            } else {
                File("/tmp/message").writeText(req.toMessage())
                val token = firstValidToken.get()
                var oboToken = ""
                var NAVidentclaim: String = ""
                try {
                    NAVidentclaim = token.jwtTokenClaims.getStringClaim(claim_NAVident)
                    oboToken = OboTokenExchangeHandler.fetchAzureTokenOBO(token).tokenAsString
                } catch (e: Exception) {
                    NAVidentclaim = req.header("Nav-Ident") ?: ""
                }
                if (oboToken.isEmpty() && NAVidentclaim.isEmpty()) {
                    Response(Status.BAD_REQUEST).body("Missing Nav-Ident header or obo token")
                } else {
                    token.logStatsInTmp()
                    val dstUrl = "${AccessTokenHandler.instanceUrl}/services/apexrest${req.uri.toString().substring(4)}"
                    val oboHeader = if (oboToken.isNotEmpty()) { listOf(Pair("X-Nav-Token", oboToken)) } else { listOf() }
                    val headers: Headers =
                        req.headers.filter { !restrictedHeaders.contains(it.first.toLowerCase()) } +
                                listOf(Pair("Authorization", "Bearer ${AccessTokenHandler.accessToken}"),
                                    Pair("X-ACTING-NAV-IDENT", NAVidentclaim)) + oboHeader
                    val request = Request(req.method, dstUrl).headers(headers).body(req.body)

                    File("/tmp/forwardmessage").writeText(request.toMessage())
                    val response = client.value(request)
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
