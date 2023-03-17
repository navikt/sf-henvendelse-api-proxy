package no.nav.sf.henvendelse.api.proxy

import io.prometheus.client.CollectorRegistry
import io.prometheus.client.Counter
import io.prometheus.client.Gauge
import io.prometheus.client.hotspot.DefaultExports

object Metrics {

    val cRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry

    val successCalls: Counter = registerLabelCounter("calls_success", "path")
    val failedCalls: Counter = registerLabelCounter("calls_failed", "status_path")
    val callSource: Counter = registerLabelCounter("call_source", "key")

    val elapsedTimeAccessTokenRequest: Gauge = registerGauge("elapsed_time_access_token_request")
    val elapsedTimeOboExchangeRequest: Gauge = registerGauge("elapsed_time_obo_exchange_request")
    val elapsedTimeTokenValidation: Gauge = registerGauge("elapsed_time_token_validation")
    val elapsedTimeCall: Gauge = registerGauge("elapsed_time_call")
    val elapsedTimeTokenHandling: Gauge = registerGauge("elapsed_time_token_handling")
    val elapsedTimeTotal: Gauge = registerGauge("elapsed_time_total")

    val cachedOboTokenProcent: Gauge = registerGauge("cached_obo_token_procent")

    fun registerCounter(name: String): Counter {
        return Counter.build().name(name).help(name).register()
    }

    fun registerLabelCounter(name: String, label: String): Counter {
        return Counter.build().name(name).help(name).labelNames(label).register()
    }

    fun registerGauge(name: String): Gauge {
        return Gauge.build().name(name).help(name).register()
    }

    fun registerLabelGauge(name: String, label: String): Gauge {
        return Gauge.build().name(name).help(name).labelNames(label).register()
    }

    init {
        DefaultExports.initialize()
    }
}
