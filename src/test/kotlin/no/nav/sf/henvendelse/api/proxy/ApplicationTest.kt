package no.nav.sf.henvendelse.api.proxy

import com.nimbusds.jwt.JWTClaimsSet
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.minidev.json.JSONArray
import no.nav.security.token.support.core.jwt.JwtToken
import no.nav.security.token.support.core.jwt.JwtTokenClaims
import no.nav.sf.henvendelse.api.proxy.token.AccessTokenHandler
import no.nav.sf.henvendelse.api.proxy.token.CLAIM_AZP_NAME
import no.nav.sf.henvendelse.api.proxy.token.CLAIM_NAV_IDENT
import no.nav.sf.henvendelse.api.proxy.token.CLAIM_ROLES
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

    val mockTokenValidator = mockk<TokenValidator>()
    val mockTokenOptional = mockk<Optional<JwtToken>>()
    val mockToken = mockk<JwtToken>()

    val mockAccessTokenHandler = mockk<AccessTokenHandler>()
    val mockHttpHandler = mockk<HttpHandler>()

    val application = Application(
        tokenValidator = mockTokenValidator,
        accessTokenHandler = mockAccessTokenHandler,
        client = mockHttpHandler,
        devContext = false,
        twincallsEnabled = false
    )

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
                .claim(CLAIM_AZP_NAME, "azp-name")
                .build()
        )
        val request = Request(Method.GET, "/api/some-endpoint")

        val response = application.handleApiRequest(request)
        val expectedResponse = Response(Status.BAD_REQUEST)

        assertEquals(expectedResponse.status, response.status)
    }

    @Test
    fun `A call with valid azure obo token containing NAVident claim should be successfully redirected`() {
        jwtTokenClaims = JwtTokenClaims(
            JWTClaimsSet.Builder()
                .claim(CLAIM_NAV_IDENT, "A123456")
                .claim(CLAIM_AZP_NAME, "azp-name")
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

        assertEquals(Uri.of("$INSTANCE_URL/services/apexrest/some-endpoint"), capturedRequest.uri)
        assertEquals("Bearer $ACCESS_TOKEN", capturedRequest.header("Authorization"))
        assertEquals("A123456", capturedRequest.header("X-ACTING-NAV-IDENT"))
        assertEquals("X-Correlation-ID", capturedRequest.header("X-Correlation-ID"))
    }

    @Test
    fun `A call with an approved machine token should use azp_name claim as ident and be successfully redirected`() {
        val array = JSONArray()
        array.add("access_as_application")
        jwtTokenClaims = JwtTokenClaims(
            JWTClaimsSet.Builder()
                .claim(CLAIM_AZP_NAME, "azp-name")
                .claim(CLAIM_ROLES, array)
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

        assertEquals(Uri.of("$INSTANCE_URL/services/apexrest/some-endpoint"), capturedRequest.uri)
        assertEquals("Bearer $ACCESS_TOKEN", capturedRequest.header("Authorization"))
        assertEquals("azp-name", capturedRequest.header("X-ACTING-NAV-IDENT"))
        assertEquals("X-Correlation-ID", capturedRequest.header("X-Correlation-ID"))
    }

    @Test
    fun `A call with valid token other then azure obo token or machine token and Nav-Ident header set should be successfully redirected`() {
        jwtTokenClaims = JwtTokenClaims(
            JWTClaimsSet.Builder()
                .claim(CLAIM_AZP_NAME, "azp-name")
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

        assertEquals(Uri.of("$INSTANCE_URL/services/apexrest/some-endpoint"), capturedRequest.uri)
        assertEquals("Bearer $ACCESS_TOKEN", capturedRequest.header("Authorization"))
        assertEquals("Nav-Ident", capturedRequest.header("X-ACTING-NAV-IDENT"))
        assertEquals("X-Correlation-ID", capturedRequest.header("X-Correlation-ID"))
    }
}
