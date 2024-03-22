package no.nav.sf.henvendelse.api.proxy.handler

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import mu.KotlinLogging
import no.nav.sf.henvendelse.api.proxy.HEADER_AUTHORIZATION
import no.nav.sf.henvendelse.api.proxy.HEADER_X_ACTING_NAV_IDENT
import no.nav.sf.henvendelse.api.proxy.HEADER_X_CORRELATION_ID
import no.nav.sf.henvendelse.api.proxy.Metrics
import no.nav.sf.henvendelse.api.proxy.token.AccessTokenHandler
import org.http4k.core.Headers
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import kotlin.system.measureTimeMillis

class TwincallHandler(private val accessTokenHandler: AccessTokenHandler, private val client: HttpHandler, private val devContext: Boolean) {

    private val log = KotlinLogging.logger { }

    private fun threadCall(request: Request): Deferred<Response> = GlobalScope.async { client(request) }

    fun performTwinCall(request: Request): Response {
        val first = threadCall(request)
        val second = threadCall(request)
        while (!first.isCompleted && !second.isCompleted) { Thread.sleep(25) }
        return if (first.isCompleted) {
            second.cancel()
            first.getCompleted()
        } else {
            first.cancel()
            second.getCompleted()
        }
    }

    fun performTestCalls() {
        try {
            val dstUrl =
                "${accessTokenHandler.instanceUrl}/services/apexrest/henvendelseinfo/henvendelseliste?aktorid=${if (devContext) "2755132512806" else "1000097498966"}"
            val headers: Headers =
                listOf(
                    HEADER_AUTHORIZATION to "Bearer ${accessTokenHandler.accessToken}",
                    HEADER_X_ACTING_NAV_IDENT to "H159337",
                    HEADER_X_CORRELATION_ID to "testcall"
                )
            val request = Request(Method.GET, dstUrl).headers(headers)
            lateinit var response: Response
            val ref = measureTimeMillis {
                response = client(request)
            }
            val twincall = measureTimeMillis {
                response = performTwinCall(request)
            }
            log.info { "Testcalls performed - ref $ref - twin $twincall. Diff ${twincall - ref}" }
            Metrics.summaryTestRef.observe(ref.toDouble())
            Metrics.summaryTestTwinCall.observe(twincall.toDouble())
        } catch (e: Exception) {
            log.warn { "Exception at test call, ${e.message}" }
        }
    }
}
