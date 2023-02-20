package no.nav.sf.henvendelse.api.proxy

import io.prometheus.client.exporter.common.TextFormat
import java.io.File
import java.io.StringWriter
import mu.KotlinLogging
import no.nav.sf.henvendelse.api.proxy.token.AccessTokenHandler
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
import org.http4k.routing.path
import org.http4k.routing.routes
import org.http4k.server.Http4kServer
import org.http4k.server.Netty
import org.http4k.server.asServer

const val NAIS_DEFAULT_PORT = 8080
const val NAIS_ISALIVE = "/internal/isAlive"
const val NAIS_ISREADY = "/internal/isReady"
const val NAIS_METRICS = "/internal/metrics"

const val API_URI_VAR = "rest"
const val API_INTERNAL_TEST_URI = "/internal/test/{$API_URI_VAR:.*}"
const val API_URI = "/{$API_URI_VAR:.*}"

const val TARGET_APP = "target-app"
const val TARGET_CLIENT_ID = "target-client-id"
const val AUTHORIZATION = "Authorization"
const val HOST = "host"
const val X_CLOUD_TRACE_CONTEXT = "x-cloud-trace-context"

const val env_WHITELIST_FILE = "WHITELIST_FILE"

class Application {
    private val log = KotlinLogging.logger { }

    private val client: Lazy<HttpHandler> = lazy { ApacheClient.supportProxy(System.getenv("HTTPS_PROXY")) }

    fun start() { log.info { "Starting" }
        apiServer(NAIS_DEFAULT_PORT).start()
        log.info { "Finished!" }
    }

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
                token.logStatsInTmp()
                val dstUrl = "${AccessTokenHandler.instanceUrl}/services/apexrest/${req.path("rest") ?: ""}"
                val headers: Headers =
                    req.headers.filter { it.first.toLowerCase() != "authorization" } + listOf(Pair("Authorization", "Bearer ${AccessTokenHandler.accessToken}"))

                val request = Request(req.method, dstUrl).headers(headers).body(req.body)

                File("/tmp/q-query").writeText(req.query("q").toString())
                File("/tmp/aktorid-query").writeText(req.query("aktorid").toString())
                File("/tmp/rest").writeText(req.path("rest") ?: "")
                File("/tmp/latestReq").writeText("method: ${request.method}, url: $dstUrl, uri: ${req.uri}, body: ${req.bodyString()}, headers: ${req.headers}")
                File("/tmp/forwardmessage").writeText(request.toMessage())
                val response = client.value(request)

                response
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
        /*
        API_INTERNAL_TEST_URI bind { req: Request ->
            val path = (req.path(API_URI_VAR) ?: "")
            Metrics.testApiCalls.labels(path).inc()
            log.info { "Test url called with path $path" }
            val method = req.method
            val targetApp = req.header(TARGET_APP)
            if (targetApp == null) {
                Response(BAD_REQUEST).body("Proxy: Missing target-app header")
            } else {
                val team = rules.filter { it.value.keys.contains(targetApp) }.map { it.key }.firstOrNull()
                if (team == null) {
                    Response(NON_AUTHORITATIVE_INFORMATION).body("App not found in rules. Not approved")
                } else {
                    var approved = false
                    var report = "Report:\n"
                    Application.rules[team]?.let { it[targetApp] }?.filter {
                        report += "Evaluating $it on method ${req.method}, path /$path "
                        it.evaluateAsRule(method, "/$path").also { report += "$it\n" }
                    }?.firstOrNull()?.let {
                        approved = true
                    }
                    report += if (approved) "Approved" else "Not approved"
                    Response(OK).body(report)
                }
            }
        },
        API_URI bind { req: Request ->
            val path = req.path(API_URI_VAR) ?: ""
            Metrics.apiCalls.labels(path).inc()

            val targetApp = req.header(TARGET_APP)
            val targetClientId = req.header(TARGET_CLIENT_ID)

            if (targetApp == null || targetClientId == null) {
                log.info { "Proxy: Bad request - missing header" }
                File("/tmp/missingheader").writeText("Call:\nPath: $path\nMethod: ${req.method}\n Uri: ${req.uri}\nBody: ${req.body}\nHeaders: $${req.headers}")

                Response(BAD_REQUEST).body("Proxy: Bad request - missing header")
            } else {
                File("/tmp/latestcall").writeText("Call:\nPath: $path\nMethod: ${req.method}\n Uri: ${req.uri}\nBody: ${req.body}\nHeaders: $${req.headers}")

                val team = rules.filter { it.value.keys.contains(targetApp) }.map { it.key }.firstOrNull()

                val approvedByRules =
                    if (team == null) {
                        false
                    } else {
                        Application.rules[team]?.let { it[targetApp] }?.filter {
                            it.evaluateAsRule(req.method, "/$path")
                        }?.firstOrNull()?.let {
                            true
                        } ?: false
                    }

                if (!approvedByRules) {
                    log.info { "Proxy: Bad request - not whitelisted" }
                    Response(BAD_REQUEST).body("Proxy: Bad request")
                } else if (!TokenValidation.containsValidToken(req, targetClientId)) {
                    log.info { "Proxy: Not authorized" }
                    Response(UNAUTHORIZED).body("Proxy: Not authorized")
                } else {
                    val blockFromForwarding = listOf(TARGET_APP, TARGET_CLIENT_ID, HOST)
                    val forwardHeaders =
                        req.headers.filter { !blockFromForwarding.contains(it.first) &&
                                !it.first.startsWith("x-") || it.first == X_CLOUD_TRACE_CONTEXT }.toList()
                    val internUrl = "http://$targetApp.$team${req.uri}" // svc.cluster.local skipped due to same cluster
                    val redirect = Request(req.method, internUrl).body(req.body).headers(forwardHeaders)
                    log.info { "Forwarded call to $internUrl" }
                    val time = System.currentTimeMillis()
                    val result = client(redirect)
                    if (result.status.code == 504) {
                        log.info { "Status Client Timeout after ${System.currentTimeMillis() - time} millis" }
                    }
                    result
                }
            }
        }

         */
    )
}
