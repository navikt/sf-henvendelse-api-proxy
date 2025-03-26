package no.nav.sf.henvendelse.api.proxy

import io.prometheus.client.CollectorRegistry
import io.prometheus.client.Counter
import io.prometheus.client.Gauge
import io.prometheus.client.Histogram
import io.prometheus.client.Summary
import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client.hotspot.DefaultExports
import mu.KotlinLogging
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import java.io.StringWriter

object Metrics {
    init {
        DefaultExports.initialize()
    }

    private val log = KotlinLogging.logger { }

    val forwardedHost: Counter = registerLabelCounter("forwarded_host", "host")
    val noauth: Counter = registerCounter("noauth_calls")
    val issuer: Counter = registerLabelCounter("issuer", "iss")

    val calls: Counter = registerLabelCounter("calls", "path", "status", "source", "call_time")
    val successCalls: Counter = registerLabelCounter("calls_success", "path")
    val failedCalls: Counter = registerLabelCounter("calls_failed", "status")
    val callSource: Counter = registerLabelCounter("call_source", "key")
    val machineCalls: Counter = registerLabelCounter("calls_machine", "path")

    val elapsedTimeAccessTokenRequest: Gauge = registerGauge("elapsed_time_access_token_request")
    val elapsedTimeTokenValidation: Gauge = registerGauge("elapsed_time_token_validation")
    val elapsedTimeCall: Gauge = registerGauge("elapsed_time_call")
    val elapsedTimeCallPerPath: Gauge = registerLabelGauge("elapsed_time_call_per_path", "path")
    val elapsedTimeTokenHandling: Gauge = registerGauge("elapsed_time_token_handling")
    val elapsedTimeTotal: Gauge = registerGauge("elapsed_time_total")

    var elapsedTimeCallHistogram: Histogram = registerHistogram("elapsed_time_call_ms")
    var elapsedTimeTotalHistogram: Histogram = registerHistogram("elapsed_time_total_ms")

    var summaryTestRef: Summary = registerSummary("reference_test")
    var summaryTestTwinCall: Summary = registerSummary("twincall_test")

    val cacheSize: Gauge = registerGauge("cache_size")

    val henvendelselisteCache = registerLabelCounter("henvendelselistecache", "method", "status", "call_time", "endpoint_label")

    val postgresHenvendelselisteCache = registerLabelCounter("postgreshenvendelselistecache", "method", "status", "call_time", "endpoint_label")

    private val metricsAsText: String get() {
        val str = StringWriter()
        TextFormat.write004(str, CollectorRegistry.defaultRegistry.metricFamilySamples())
        return str.toString()
    }

    val metricsHandler = { _: Request ->
        try {
            val result = metricsAsText
            if (result.isEmpty()) {
                Response(Status.NO_CONTENT)
            } else {
                Response(Status.OK).body(result)
            }
        } catch (e: Exception) {
            log.error { "Failed writing metrics - ${e.message}" }
            Response(Status.INTERNAL_SERVER_ERROR)
        }
    }

    fun registerCounter(name: String): Counter {
        return Counter.build().name(name).help(name).register()
    }

    fun registerLabelCounter(name: String, vararg labels: String): Counter {
        return Counter.build().name(name).help(name).labelNames(*labels).register()
    }

    fun registerGauge(name: String): Gauge {
        return Gauge.build().name(name).help(name).register()
    }

    fun registerLabelGauge(name: String, vararg labels: String): Gauge {
        return Gauge.build().name(name).help(name).labelNames(*labels).register()
    }

    fun registerHistogram(name: String): Histogram {
        return Histogram.build().name(name).help(name).buckets(300.0, 500.0, 750.0, 1000.0, 2000.0, 4000.0, 8000.0).register()
    }

    fun registerSummary(name: String): Summary {
        return Summary.build().name(name).help(name).register()
    }
}
