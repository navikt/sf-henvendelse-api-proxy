package no.nav.sf.henvendelse.api.proxy.token

import java.io.File
import mu.KotlinLogging
import no.nav.sf.henvendelse.api.proxy.Metrics
import no.nav.sf.henvendelse.api.proxy.token.FetchStats.inc
import org.http4k.core.Uri

object FetchStats {
    private val log = KotlinLogging.logger { }

    var elapsedTimeAccessTokenRequest = -1L
    var elapsedTimeOboExchangeRequest = -1L
    var elapsedTimeTokenValidation = -1L
    var latestCallElapsedTime = -1L

    var OBOfetches = 0L
    var OBOcached = 0L

    val cacheProcent: Float get() =
        if (OBOfetches > 0) {
            OBOcached.toFloat() * 100 / (OBOfetches + OBOcached)
        } else {
            0f
        }

    fun resetFetchVars() {
        elapsedTimeAccessTokenRequest = 0L
        elapsedTimeOboExchangeRequest = 0L
        elapsedTimeTokenValidation = 0L
        latestCallElapsedTime = 0L
    }

    fun registerCallSource(key: String) {
        callSourceCount.inc(key)
        File("/tmp/callSourceCount").writeText(callSourceCount.toString())
        Metrics.callSource.labels(key).inc()
    }

    private val callSourceCount: MutableMap<String, Int> = mutableMapOf()

    private fun MutableMap<String, Int>.inc(key: String) {
        if (!this.containsKey(key)) this[key] = 0
        this[key] = this[key]!! + 1
    }

    private val pathsWithPathVars = listOf("/henvendelse/sladding/aarsaker/", "/henvendelse/behandling/", "/henvendelseinfo/henvendelse/")

    fun logStats(status: Int, uri: Uri, callTime: Long) {
        log.info { "Timings ($callTime) : Validation $elapsedTimeTokenValidation, Accesstoken: $elapsedTimeAccessTokenRequest," +
                " OboExchange $elapsedTimeOboExchangeRequest, Call $latestCallElapsedTime," +
                " Sum ${elapsedTimeTokenValidation + elapsedTimeAccessTokenRequest + elapsedTimeOboExchangeRequest + latestCallElapsedTime}." +
                " Obo cache percentage: ${String.format("%.2f", cacheProcent)}" }
        val path = pathsWithPathVars.filter { uri.path.contains(it) }.firstOrNull() ?: uri.path

        Metrics.elapsedTimeAccessTokenRequest.set(elapsedTimeAccessTokenRequest.toDouble())
        Metrics.elapsedTimeTokenValidation.set(elapsedTimeTokenValidation.toDouble())
        Metrics.elapsedTimeOboExchangeRequest.set(elapsedTimeOboExchangeRequest.toDouble())
        Metrics.elapsedTimeCall.labels(path).set(latestCallElapsedTime.toDouble())
        Metrics.elapsedTimeTokenHandling.set(elapsedTimeAccessTokenRequest.toDouble() +
                elapsedTimeTokenValidation.toDouble() +
                elapsedTimeOboExchangeRequest.toDouble()
        )
        Metrics.elapsedTimeTotal.set(Metrics.elapsedTimeTokenHandling.get() +
                latestCallElapsedTime.toDouble()
        )
        Metrics.cachedOboTokenProcent.set(cacheProcent.toDouble())
        Metrics.elapsedTimeCallHistogram.observe(latestCallElapsedTime.toDouble())
        Metrics.elapsedTimeTotalHistogram.observe(latestCallElapsedTime.toDouble() + Metrics.elapsedTimeTokenHandling.get())
        if (status == 200) {
            Metrics.successCalls.labels(path).inc()
        } else {
            Metrics.failedCalls.labels(status.toString()).inc()
        }
    }
}
