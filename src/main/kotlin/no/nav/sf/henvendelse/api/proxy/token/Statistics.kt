package no.nav.sf.henvendelse.api.proxy.token

import mu.KotlinLogging
import no.nav.sf.henvendelse.api.proxy.APEX_REST_BASE_PATH
import no.nav.sf.henvendelse.api.proxy.Metrics
import org.http4k.core.Uri

/**
 * TokenFetchStatistics - stores and handles data for logging and measurements, instansiated per request
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

    fun logAndUpdateMetrics(status: Int, uri: Uri) {
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
        } catch (t: Throwable) {
            log.error { "Failed to update metrics:" + t.message }
        }
    }
}
