package no.nav.sf.henvendelse.api.proxy.token

import mu.KotlinLogging

object FetchStats {
    private val log = KotlinLogging.logger { }

    var elapsedTimeAccessTokenRequest: Long = -1L
    var elapsedTimeOboExchangeRequest: Long = -1L
    fun resetFetchVars() = {
        elapsedTimeAccessTokenRequest = 0L
        elapsedTimeOboExchangeRequest = 0L
    }
    var callTime: MutableMap<String, Long> = mutableMapOf()

    fun logStats() {
        log.info { "Accesstoken: $elapsedTimeAccessTokenRequest, OboExchange $elapsedTimeOboExchangeRequest" }
        log.info { "Call times $callTime" }
    }
}
