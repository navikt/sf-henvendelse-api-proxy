package no.nav.sf.henvendelse.api.proxy.token

import mu.KotlinLogging

object FetchStats {
    private val log = KotlinLogging.logger { }

    var elapsedTimeAccessTokenRequest: Long = -1L
    var elapsedTimeOboExchangeRequest: Long = -1L
    var elapsedTimeTokenValidation: Long = -1L

    fun resetFetchVars() = {
        elapsedTimeAccessTokenRequest = 0L
        elapsedTimeOboExchangeRequest = 0L
        elapsedTimeTokenValidation = 0L
    }
    var callElapsedTime: MutableMap<String, Long> = mutableMapOf()

    fun logStats(callTime: Long) {
        log.info { "($callTime) Validation ${elapsedTimeTokenValidation }Accesstoken: $elapsedTimeAccessTokenRequest, OboExchange $elapsedTimeOboExchangeRequest" }
        log.info { "Call times $callElapsedTime" }
    }
}
