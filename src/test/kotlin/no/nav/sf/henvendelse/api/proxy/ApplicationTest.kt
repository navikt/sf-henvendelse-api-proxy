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

class ApplicationTest {
    private val mockTokenValidator = mockk<TokenValidator>()
    private val mockToken = mockk<JwtToken>()

    private val mockAccessTokenHandler = mockk<AccessTokenHandler>()
    private val mockClient = mockk<HttpHandler>()

    private val application =
        Application(
            tokenValidator = mockTokenValidator,
            accessTokenHandler = mockAccessTokenHandler,
            devContext = false,
            client = mockClient,
        )

    private val instanceUrl = "https://localhost:8080"
    private val accessToken = "accesstoken"

    // Configure claim content of simulated accepted token for each test case:
    private var jwtTokenClaims: JwtTokenClaims = JwtTokenClaims(JWTClaimsSet.Builder().build())

    @BeforeEach
    fun setup() {
        every { mockTokenValidator.firstValidToken(any(), any()) } returns mockToken

        every { mockToken.encodedToken } returns "mockToken"
        every { mockToken.jwtTokenClaims } returns jwtTokenClaims

        every { mockAccessTokenHandler.instanceUrl } returns instanceUrl
        every { mockAccessTokenHandler.accessToken } returns accessToken
    }

    @Test
    fun `If no nav identifier to be found anywhere, consider it a bad request`() {
        jwtTokenClaims =
            JwtTokenClaims(
                JWTClaimsSet
                    .Builder()
                    .claim(CLAIM_AZP_NAME, "azp-name")
                    .build(),
            )
        val request = Request(Method.GET, "$API_BASE_PATH/some-endpoint")

        val response = application.handleApiRequest(request)
        val expectedResponse = Response(Status.BAD_REQUEST)

        assertEquals(expectedResponse.status, response.status)
    }

    @Test
    fun `A call with valid azure obo token containing NAVident claim should be successfully redirected`() {
        jwtTokenClaims =
            JwtTokenClaims(
                JWTClaimsSet
                    .Builder()
                    .claim(CLAIM_NAV_IDENT, "A123456")
                    .claim(CLAIM_AZP_NAME, "azp-name")
                    .build(),
            )

        every { mockToken.jwtTokenClaims } returns jwtTokenClaims

        every { mockClient.invoke(capture(slot())) } returns Response(Status.OK)

        val request =
            Request(Method.GET, "$API_BASE_PATH/some-endpoint").headers(
                listOf(
                    "X-Correlation-ID" to "X-Correlation-ID",
                ),
            )

        application.handleApiRequest(request)

        val capturedRequestSlot: CapturingSlot<Request> = slot()
        verify { mockClient.invoke(capture(capturedRequestSlot)) }
        val capturedRequest = capturedRequestSlot.captured

        assertEquals(Uri.of("$instanceUrl$APEX_REST_BASE_PATH/some-endpoint"), capturedRequest.uri)
        assertEquals("Bearer $accessToken", capturedRequest.header("Authorization"))
        assertEquals("A123456", capturedRequest.header("X-ACTING-NAV-IDENT"))
        assertEquals("X-Correlation-ID", capturedRequest.header("X-Correlation-ID"))
    }

    @Test
    fun `A call with an approved machine token to kodeverk path should use azp_name claim as ident and be successfully redirected`() {
        val array = JSONArray()
        array.add("access_as_application")
        jwtTokenClaims =
            JwtTokenClaims(
                JWTClaimsSet
                    .Builder()
                    .claim(CLAIM_AZP_NAME, "azp-name")
                    .claim(CLAIM_ROLES, array)
                    .build(),
            )

        every { mockToken.jwtTokenClaims } returns jwtTokenClaims

        every { mockClient.invoke(capture(slot())) } returns Response(Status.OK)

        val request =
            Request(Method.GET, "$API_BASE_PATH/kodeverk/some-endpoint")
                .headers(
                    listOf(
                        "X-Correlation-ID" to "X-Correlation-ID",
                    ),
                )

        application.handleApiRequest(request)

        val capturedRequestSlot: CapturingSlot<Request> = slot()
        verify { mockClient.invoke(capture(capturedRequestSlot)) }
        val capturedRequest = capturedRequestSlot.captured

        assertEquals(Uri.of("$instanceUrl$APEX_REST_BASE_PATH/kodeverk/some-endpoint"), capturedRequest.uri)
        assertEquals("Bearer $accessToken", capturedRequest.header("Authorization"))
        assertEquals("azp-name", capturedRequest.header("X-ACTING-NAV-IDENT"))
        assertEquals("X-Correlation-ID", capturedRequest.header("X-Correlation-ID"))
    }

    @Test
    fun `A call with an approved machine token to path kodeverk path should use azp_name claim as ident and be successfully redirected`() {
        val array = JSONArray()
        array.add("access_as_application")
        jwtTokenClaims =
            JwtTokenClaims(
                JWTClaimsSet
                    .Builder()
                    .claim(CLAIM_AZP_NAME, "azp-name")
                    .claim(CLAIM_ROLES, array)
                    .build(),
            )

        every { mockToken.jwtTokenClaims } returns jwtTokenClaims

        every { mockClient.invoke(capture(slot())) } returns Response(Status.OK)

        val request =
            Request(Method.GET, "$API_BASE_PATH/kodeverk/some-endpoint")
                .headers(
                    listOf(
                        "X-Correlation-ID" to "X-Correlation-ID",
                    ),
                )

        application.handleApiRequest(request)

        val capturedRequestSlot: CapturingSlot<Request> = slot()
        verify { mockClient.invoke(capture(capturedRequestSlot)) }
        val capturedRequest = capturedRequestSlot.captured

        assertEquals(Uri.of("$instanceUrl$APEX_REST_BASE_PATH/kodeverk/some-endpoint"), capturedRequest.uri)
        assertEquals("Bearer $accessToken", capturedRequest.header("Authorization"))
        assertEquals("azp-name", capturedRequest.header("X-ACTING-NAV-IDENT"))
        assertEquals("X-Correlation-ID", capturedRequest.header("X-Correlation-ID"))
    }
}
