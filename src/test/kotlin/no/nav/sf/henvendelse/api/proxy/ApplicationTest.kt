package no.nav.sf.henvendelse.api.proxy

import com.nimbusds.jwt.JWTClaimsSet
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import mu.KotlinLogging
import net.minidev.json.JSONArray
import no.nav.security.token.support.core.jwt.JwtToken
import no.nav.security.token.support.core.jwt.JwtTokenClaims
import no.nav.sf.henvendelse.api.proxy.token.AccessTokenHandler
import no.nav.sf.henvendelse.api.proxy.token.TokenValidator
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Uri
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional

class ApplicationTest {
    private val log = KotlinLogging.logger { }

    val mockTokenValidator = mockk<TokenValidator>()
    val mockTokenOptional = mockk<Optional<JwtToken>>()
    val mockToken = mockk<JwtToken>()

    val mockAccessTokenHandler = mockk<AccessTokenHandler>()
    val mockHttpHandler = mockk<HttpHandler>()

    val application = Application(mockTokenValidator, mockAccessTokenHandler, lazy { mockHttpHandler })

    val INSTANCE_URL = "https://localhost:8080"
    val ACCESS_TOKEN = "accesstoken"

    // Configure claim content of simulated accepted token for each test case:
    var jwtTokenClaims: JwtTokenClaims = JwtTokenClaims(JWTClaimsSet.Builder().build())

    @BeforeEach
    fun setup() {
        every { mockTokenValidator.firstValidToken(any(), any()) } returns mockTokenOptional

        every { mockTokenOptional.isPresent } returns true
        every { mockTokenOptional.get() } returns mockToken

        every { mockToken.tokenAsString } returns "mockToken"
        every { mockToken.jwtTokenClaims } returns jwtTokenClaims

        every { mockAccessTokenHandler.instanceUrl } returns INSTANCE_URL
        every { mockAccessTokenHandler.accessToken } returns ACCESS_TOKEN
    }

    @Test
    fun `If no nav identifier to be found anywhere, consider it a bad request`() {
        jwtTokenClaims = JwtTokenClaims(
            JWTClaimsSet.Builder()
                .claim(claim_azp_name, "azp-name")
                .build()
        )
        val request = Request(Method.GET, "/api/some-endpoint")

        val response = application.handleApiRequest(request)
        val expectedResponse = Response(Status.BAD_REQUEST).body(msg_Missing_Nav_identifier)

        assertEquals(expectedResponse, response)
    }

    @Test
    fun `A call with valid azure obo token containing NAVident claim should be successfully redirected`() {
        jwtTokenClaims = JwtTokenClaims(
            JWTClaimsSet.Builder()
                .claim(claim_NAVident, "A123456")
                .claim(claim_azp_name, "azp-name")
                .build()
        )

        every { mockToken.jwtTokenClaims } returns jwtTokenClaims

        every { mockHttpHandler.invoke(capture(slot())) } returns Response(Status.OK)

        val request = Request(Method.GET, "/api/some-endpoint").headers(
            listOf(
                Pair("X-Correlation-ID", "X-Correlation-ID")
            )
        )

        application.handleApiRequest(request)

        val capturedRequestSlot: CapturingSlot<Request> = slot()
        verify { mockHttpHandler.invoke(capture(capturedRequestSlot)) }
        val capturedRequest = capturedRequestSlot.captured

        println(capturedRequest)

        assertEquals(Uri.of("$INSTANCE_URL/services/apexrest/some-endpoint"), capturedRequest.uri)
        assertEquals("Bearer $ACCESS_TOKEN", capturedRequest.header("Authorization"))
        assertEquals("A123456", capturedRequest.header("X-ACTING-NAV-IDENT"))
        assertEquals("X-Correlation-ID", capturedRequest.header("X-Correlation-ID"))
    }

    @Test
    fun `A call with valid token other then azure obo token and Nav-Ident header set should be successfully redirected`() {
        jwtTokenClaims = JwtTokenClaims(
            JWTClaimsSet.Builder()
                .claim(claim_azp_name, "azp-name")
                .build()
        )

        every { mockToken.jwtTokenClaims } returns jwtTokenClaims

        every { mockHttpHandler.invoke(capture(slot())) } returns Response(Status.OK)

        val request = Request(Method.GET, "/api/some-endpoint")
            .headers(
                listOf(
                    Pair("Nav-Ident", "Nav-Ident"),
                    Pair("X-Correlation-ID", "X-Correlation-ID")
                )
            )

        application.handleApiRequest(request)

        val capturedRequestSlot: CapturingSlot<Request> = slot()
        verify { mockHttpHandler.invoke(capture(capturedRequestSlot)) }
        val capturedRequest = capturedRequestSlot.captured

        println(capturedRequest)

        assertEquals(Uri.of("$INSTANCE_URL/services/apexrest/some-endpoint"), capturedRequest.uri)
        assertEquals("Bearer $ACCESS_TOKEN", capturedRequest.header("Authorization"))
        assertEquals("Nav-Ident", capturedRequest.header("X-ACTING-NAV-IDENT"))
        assertEquals("X-Correlation-ID", capturedRequest.header("X-Correlation-ID"))
    }

    @Test
    fun `A call with an approved machine token (and no ident header) should use azp_name claim as ident and be successfully redirected`() {
        var array = JSONArray()
        array.add("access_as_application")
        jwtTokenClaims = JwtTokenClaims(
            JWTClaimsSet.Builder()
                .claim(claim_azp_name, "azp-name")
                .claim(claim_roles, array)
                .build()
        )

        every { mockToken.jwtTokenClaims } returns jwtTokenClaims

        every { mockHttpHandler.invoke(capture(slot())) } returns Response(Status.OK)

        val request = Request(Method.GET, "/api/some-endpoint")
            .headers(
                listOf(
                    Pair("X-Correlation-ID", "X-Correlation-ID")
                )
            )

        application.handleApiRequest(request)

        val capturedRequestSlot: CapturingSlot<Request> = slot()
        verify { mockHttpHandler.invoke(capture(capturedRequestSlot)) }
        val capturedRequest = capturedRequestSlot.captured

        println(capturedRequest)

        assertEquals(Uri.of("$INSTANCE_URL/services/apexrest/some-endpoint"), capturedRequest.uri)
        assertEquals("Bearer $ACCESS_TOKEN", capturedRequest.header("Authorization"))
        assertEquals("azp-name", capturedRequest.header("X-ACTING-NAV-IDENT"))
        assertEquals("X-Correlation-ID", capturedRequest.header("X-Correlation-ID"))
    }
}
