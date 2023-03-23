package no.nav.sf.henvendelse.api.proxy

import mu.KotlinLogging
import org.junit.jupiter.api.Test

class ApplicationTest {
    private val log = KotlinLogging.logger { }

    @Test
    fun instansiateMetrics() {
        Metrics.elapsedTimeTotal.inc()
        Metrics.elapsedTimeCall.set(2.0)
        Metrics.elapsedTimeCallPerPath.labels("path").set(2.0)
    }
}
