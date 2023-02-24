package no.nav.sf.henvendelse.api.proxy.token

import mu.KotlinLogging

object FetchStats {
    private val log = KotlinLogging.logger { }

    var elapsedTimeAccessTokenRequest: Long = -1L
    var elapsedTimeOboExchangeRequest: Long = -1L
    var elapsedTimeTokenValidation: Long = -1L
    var latestCallElapsedTime: Long = -1L

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
                " Sum ${elapsedTimeTokenValidation + elapsedTimeAccessTokenRequest + elapsedTimeOboExchangeRequest + latestCallElapsedTime}" }
        log.info { "Call times per path stub: $callElapsedTime" }
    }
}
