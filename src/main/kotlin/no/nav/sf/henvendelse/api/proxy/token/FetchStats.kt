package no.nav.sf.henvendelse.api.proxy.token

import java.io.File
import mu.KotlinLogging

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
            OBOcached.toFloat() / OBOfetches
        } else {
            0f
        }

    fun resetFetchVars(callTime: Long) {
        log.info { "($callTime) stats reset" }
        elapsedTimeAccessTokenRequest = 0L
        elapsedTimeOboExchangeRequest = 0L
        elapsedTimeTokenValidation = 0L
        latestCallElapsedTime = 0L
    }
    var callElapsedTime: MutableMap<String, Long> = mutableMapOf()

    fun logStats(callTime: Long) {
        log.info { "($callTime) : Validation $elapsedTimeTokenValidation, Accesstoken: $elapsedTimeAccessTokenRequest," +
                " OboExchange $elapsedTimeOboExchangeRequest, Call $latestCallElapsedTime," +
                " Sum ${elapsedTimeTokenValidation + elapsedTimeAccessTokenRequest + elapsedTimeOboExchangeRequest + latestCallElapsedTime}." +
                " Obo cache percentage: ${String.format("%.2f", cacheProcent)}" }
        File("/tmp/callperpath").writeText("Call times per path stub: $callElapsedTime")
    }
}