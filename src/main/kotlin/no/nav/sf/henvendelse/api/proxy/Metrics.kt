package no.nav.sf.henvendelse.api.proxy

import io.prometheus.client.CollectorRegistry
import io.prometheus.client.Counter
import io.prometheus.client.Gauge
import io.prometheus.client.hotspot.DefaultExports

object Metrics {

    val cRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry

    val successCalls: Counter = registerLabelCounter("calls_success", "path")

    val failedCalls: Counter = registerLabelCounter("calls_failed", "status_path")

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
