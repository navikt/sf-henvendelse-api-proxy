package no.nav.sf.henvendelse.api.proxy.token

import mu.KotlinLogging
import no.nav.sf.henvendelse.api.proxy.Metrics
import org.http4k.core.Request
import org.http4k.core.Uri
import java.io.File

class TokenFetchStatistics(private val request: Request, private val callIndex: Long, private val devContext: Boolean) {
    private val log = KotlinLogging.logger { }

    var elapsedTimeAccessTokenRequest = 0L
    var elapsedTimeOboExchangeRequest = 0L
    var elapsedTimeOboHandling = 0L
    var elapsedTimeTokenValidation = 0L
    var latestCallElapsedTime = 0L

    var oboFetches = 0L
    var oboCached = 0L

    var srcLabel = ""

    fun registerSourceLabel(srcLabelInput: String, logMessage: String, authenticationTypePrefix: String) {
        srcLabel = srcLabelInput
        log.info("$logMessage - src=$srcLabelInput")
        registerCallSource("$authenticationTypePrefix-$srcLabelInput")
        if (devContext || authenticationTypePrefix == "header") File("/tmp/message-$authenticationTypePrefix").writeText("($callIndex)\n" + request.toMessage())
    }

    private fun registerCallSource(key: String) = Metrics.callSource.labels(key).inc()

    private val pathsWithPathVars =
        listOf("/henvendelse/sladding/aarsaker/", "/henvendelse/behandling/", "/henvendelseinfo/henvendelse/")

    fun logStats(status: Int, uri: Uri) {
        try {
            log.info {
                "Timings : Validation $elapsedTimeTokenValidation, Accesstoken: $elapsedTimeAccessTokenRequest," +
                    " OboHandling: $elapsedTimeOboHandling (rq: $elapsedTimeOboExchangeRequest), Call $latestCallElapsedTime," +
                    " Sum ${elapsedTimeTokenValidation + elapsedTimeAccessTokenRequest + elapsedTimeOboExchangeRequest + latestCallElapsedTime}."
            }

            val path = (pathsWithPathVars.firstOrNull { uri.path.contains(it) } ?: uri.path)
                .replace("/services/apexrest", "")

            Metrics.elapsedTimeAccessTokenRequest.set(elapsedTimeAccessTokenRequest.toDouble())
            Metrics.elapsedTimeTokenValidation.set(elapsedTimeTokenValidation.toDouble())
            Metrics.elapsedTimeOboHandling.set(elapsedTimeOboHandling.toDouble())
            Metrics.elapsedTimeOboExchangeRequest.set(elapsedTimeOboExchangeRequest.toDouble())
            Metrics.elapsedTimeCall.set(latestCallElapsedTime.toDouble())
            Metrics.elapsedTimeCallPerPath.labels(path).set(latestCallElapsedTime.toDouble())
            Metrics.elapsedTimeTokenHandling.set(
                elapsedTimeAccessTokenRequest.toDouble() +
                    elapsedTimeTokenValidation.toDouble() +
                    elapsedTimeOboHandling.toDouble()
            )
            Metrics.elapsedTimeTotal.set(Metrics.elapsedTimeTokenHandling.get() + latestCallElapsedTime.toDouble())

            Metrics.elapsedTimeCallHistogram.observe(latestCallElapsedTime.toDouble())
            Metrics.elapsedTimeTotalHistogram.observe(latestCallElapsedTime.toDouble() + Metrics.elapsedTimeTokenHandling.get())
            if (status == 200) {
                Metrics.successCalls.labels(path).inc()
            } else {
                Metrics.failedCalls.labels(status.toString()).inc()
            }
            Metrics.calls.labels(path, status.toString(), srcLabel).inc()

            if (srcLabel.contains("m2m")) {
                Metrics.machineCalls.labels(path).inc()
            }
        } catch (t: Throwable) {
            log.error { "Failed to update metrics:" + t.message }
        }
    }
}
