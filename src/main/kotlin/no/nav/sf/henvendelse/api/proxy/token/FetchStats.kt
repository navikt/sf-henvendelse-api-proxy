package no.nav.sf.henvendelse.api.proxy.token

import java.io.File
import mu.KotlinLogging
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

    private val pathsWithPathVars = listOf("/henvendelse/sladding/aarsaker/", "/henvendelse/behandling/", "/henvendelseinfo/henvendelse/")
    private val elapsedTimePerPath: MutableMap<String, Long> = mutableMapOf()

    fun logStats(uri: Uri, callTime: Long) {
        log.info { "Timings ($callTime) : Validation $elapsedTimeTokenValidation, Accesstoken: $elapsedTimeAccessTokenRequest," +
                " OboExchange $elapsedTimeOboExchangeRequest, Call $latestCallElapsedTime," +
                " Sum ${elapsedTimeTokenValidation + elapsedTimeAccessTokenRequest + elapsedTimeOboExchangeRequest + latestCallElapsedTime}." +
                " Obo cache percentage: ${String.format("%.2f", cacheProcent)}" }

        val path = pathsWithPathVars.filter { uri.path.contains(it) }.firstOrNull() ?: uri.path
        FetchStats.elapsedTimePerPath[path] = FetchStats.latestCallElapsedTime
        File("/tmp/callperpath").writeText("Latest call elapsed time per path: $elapsedTimePerPath")
    }
}
