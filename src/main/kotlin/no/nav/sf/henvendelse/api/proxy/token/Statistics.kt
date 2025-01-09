package no.nav.sf.henvendelse.api.proxy.token

import mu.KotlinLogging
import no.nav.sf.henvendelse.api.proxy.APEX_REST_BASE_PATH
import no.nav.sf.henvendelse.api.proxy.Metrics
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Uri
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Statistics - Stores and handles data for logging and measurements, instantiated per request.
 */
class Statistics {
    private val log = KotlinLogging.logger { }

    var elapsedTimeTokenValidation = 0L // Time spent on validating token on incoming call
    var elapsedTimeAccessTokenRequest = 0L // Time spent on fetching access token to Salesforce
    var latestCallElapsedTime = 0L // Time spent performing the call to Salesforce

    var srcLabel = ""
    var machine = false

    /**
     * pathsWithPathVars - list of known paths in api that includes a path variable.
     *                     Used to exclude path variables from path statistics
     */
    val pathsWithPathVars =
        listOf("/henvendelse/sladding/aarsaker/", "/henvendelse/behandling/", "/henvendelseinfo/henvendelse/")

    fun logAndUpdateMetrics(status: Int, uri: Uri, req: Request, res: Response) {
        try {
            log.info {
                "Timings : Validation $elapsedTimeTokenValidation, Accesstoken: $elapsedTimeAccessTokenRequest," +
                    "Call $latestCallElapsedTime," +
                    " Sum ${elapsedTimeTokenValidation + elapsedTimeAccessTokenRequest + latestCallElapsedTime}."
            }

            /**
             * statsPath is a version of request path that is shortened and with known path variables removed
             * to use as a clean metric
             */
            val statsPath = (pathsWithPathVars.firstOrNull { uri.path.contains(it) } ?: uri.path)
                .replace(APEX_REST_BASE_PATH, "")

            Metrics.elapsedTimeAccessTokenRequest.set(elapsedTimeAccessTokenRequest.toDouble())
            Metrics.elapsedTimeTokenValidation.set(elapsedTimeTokenValidation.toDouble())
            Metrics.elapsedTimeCall.set(latestCallElapsedTime.toDouble())
            Metrics.elapsedTimeCallPerPath.labels(statsPath).set(latestCallElapsedTime.toDouble())
            val totalTimeTokenHandling = elapsedTimeAccessTokenRequest + elapsedTimeTokenValidation
            Metrics.elapsedTimeTokenHandling.set(totalTimeTokenHandling.toDouble())
            val totalTime = totalTimeTokenHandling + latestCallElapsedTime
            Metrics.elapsedTimeTotal.set(totalTime.toDouble())

            Metrics.elapsedTimeCallHistogram.observe(latestCallElapsedTime.toDouble())
            Metrics.elapsedTimeTotalHistogram.observe(totalTime.toDouble())
            if (status in 200..299) {
                Metrics.successCalls.labels(statsPath).inc()
            } else {
                Metrics.failedCalls.labels(status.toString()).inc()
            }
            Metrics.calls.labels(statsPath, status.toString(), srcLabel).inc()
            if (machine) Metrics.machineCalls.labels(statsPath).inc()
            if (status == 403) {
                File("/tmp/" + createSafeFilename(statsPath, 403)).writeText(
                    "$currentDateTime\n\nREQUEST:\n${req.toMessage()}\n\nRESPONSE:\n${res.toMessage()}"
                )
            }
        } catch (t: Throwable) {
            log.error { "Failed to update metrics:" + t.message }
        }
    }

    val currentDateTime: String get() = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME)

    fun createSafeFilename(requestPath: String, statusCode: Int): String {
        // Remove leading/trailing slashes and replace special characters with underscores
        val sanitizedPath = requestPath.trim('/').replace(Regex("[^a-zA-Z0-9]"), "_")
        // Combine sanitized path with status code
        return "${sanitizedPath}_$statusCode"
    }
}
