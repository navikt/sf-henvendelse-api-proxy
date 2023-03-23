package no.nav.sf.henvendelse.api.proxy

import mu.KotlinLogging
import org.junit.jupiter.api.Test

class ApplicationTest {
    private val log = KotlinLogging.logger { }

    @Test
    fun instansiateMetrics() {
        Metrics.elapsedTimeTotal.inc()
        Metrics.elapsedTimeCall.labels("path").set(2.0)
    }
}
