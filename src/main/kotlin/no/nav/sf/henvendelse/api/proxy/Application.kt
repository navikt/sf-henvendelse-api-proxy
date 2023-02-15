package no.nav.sf.henvendelse.api.proxy

import io.prometheus.client.exporter.common.TextFormat
import java.io.StringWriter
import mu.KotlinLogging
import no.nav.sf.henvendelse.api.proxy.token.AccessTokenHandler
import org.http4k.core.HttpHandler
import org.http4k.core.Method
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
    // val rules = Rules.parse(System.getenv(env_WHITELIST_FILE))

    /*
    val client = System.getenv("HTTPS_PROXY").let {
        val uri = URI(it)
        ApacheClient(HttpClients.custom()
        .setDefaultRequestConfig(
            RequestConfig.custom()
                .setConnectTimeout(5000)
                .setSocketTimeout(5000)
                .setConnectionRequestTimeout(5000)
                .setRedirectsEnabled(false)
                .setProxy(HttpHost(uri.host, uri.port, uri.scheme))
                .setCookieSpec(CookieSpecs.IGNORE_COOKIES)
                .build()).build()) }


     */
    fun start() {
        log.info { "Starting ${AccessTokenHandler.hello}" }
        apiServer(NAIS_DEFAULT_PORT).start()
        log.info { "Finished!" }
    }

    fun apiServer(port: Int): Http4kServer = api().asServer(Netty(port))

    fun api(): HttpHandler = routes(
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
