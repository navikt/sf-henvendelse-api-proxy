package no.nav.sf.henvendelse.api.proxy.token

import com.google.gson.Gson
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import no.nav.sf.henvendelse.api.proxy.supportProxy
import org.apache.commons.codec.binary.Base64.decodeBase64
import org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString
import org.http4k.client.ApacheClient
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.body.toBody

object AccessTokenHandler {
    private val log = KotlinLogging.logger { }

    val accessToken get() = fetchAccessTokenAndInstanceUrl().first
    val instanceUrl get() = fetchAccessTokenAndInstanceUrl().second

    val SFTokenHost: Lazy<String> = lazy { System.getenv("SF_TOKENHOST") }
    private val SFClientID = fetchVaultValue("SFClientID")
    private val SFUsername = fetchVaultValue("SFUsername")
    private val keystoreB64 = fetchVaultValue("KeystoreJKSB64")
    private val keystorePassword = fetchVaultValue("KeystorePassword")
    private val privateKeyAlias = fetchVaultValue("PrivateKeyAlias")
    private val privateKeyPassword = fetchVaultValue("PrivateKeyPassword")

    private val client: Lazy<HttpHandler> = lazy { ApacheClient.supportProxy(System.getenv("HTTPS_PROXY")) }

    private val gson = Gson()

    private const val expTimeSecondsClaim = 300 // 5 min - expire time for the access token we ask salesforce for

    private var lastTokenPair = Pair("", "")

    private var expireTime = System.currentTimeMillis()

    fun fetchVaultValue(vaultKey: String): String {
        val vaultPath = "/var/run/secrets/nais.io/vault"
        return File("$vaultPath/$vaultKey").readText(Charsets.UTF_8)
    }

    fun fetchAccessTokenAndInstanceUrl(): Pair<String, String> {
        if (System.currentTimeMillis() < expireTime) {
            return lastTokenPair
        }
        val expireMomentSinceEpochInSeconds = (System.currentTimeMillis() / 1000) + expTimeSecondsClaim
        val claim = JWTClaim(
            iss = SFClientID,
            aud = SFTokenHost.value,
            sub = SFUsername,
            exp = expireMomentSinceEpochInSeconds.toString()
        )
        val privateKey = PrivateKeyFromBase64Store(
            ksB64 = keystoreB64,
            ksPwd = keystorePassword,
            pkAlias = privateKeyAlias,
            pkPwd = privateKeyPassword
        )
        val claimWithHeaderJsonUrlSafe = "${
            gson.toJson(JWTClaimHeader("RS256")).encodeB64UrlSafe()
        }.${gson.toJson(claim).encodeB64UrlSafe()}"
        val fullClaimSignature = privateKey.sign(claimWithHeaderJsonUrlSafe.toByteArray())

        val accessTokenRequest = Request(Method.POST, SFTokenHost.value)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .body(
                listOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:jwt-bearer",
                    "assertion" to "$claimWithHeaderJsonUrlSafe.$fullClaimSignature"
                ).toBody())

        for (retry in 1..4) {
            try {
                lateinit var response: Response
                FetchStats.elapsedTimeAccessTokenRequest = measureTimeMillis {
                    response = client.value(accessTokenRequest)
                    if (response.status.code == 200) {
                        val accessTokenResponse = gson.fromJson(response.bodyString(), AccessTokenResponse::class.java)
                        lastTokenPair = Pair(accessTokenResponse.access_token, accessTokenResponse.instance_url)
                        expireTime = (expireMomentSinceEpochInSeconds - 10) * 1000
                        return lastTokenPair
                    }
                }
            } catch (e: Exception) {
                log.error("Attempt to fetch access token $retry of 3 failed by ${e.message}")
                runBlocking { delay(retry * 1000L) }
            }
        }
        log.error("Attempt to fetch access token given up")
        return Pair("", "")
    }

    fun PrivateKeyFromBase64Store(ksB64: String, ksPwd: String, pkAlias: String, pkPwd: String): PrivateKey {
        return KeyStore.getInstance("JKS").apply { load(ksB64.decodeB64().inputStream(), ksPwd.toCharArray()) }.run {
            getKey(pkAlias, pkPwd.toCharArray()) as PrivateKey
        }
    }

    fun PrivateKey.sign(data: ByteArray): String {
        return this.let {
            java.security.Signature.getInstance("SHA256withRSA").apply {
                initSign(it)
                update(data)
            }.run {
                sign().encodeB64()
            }
        }
    }

    fun ByteArray.encodeB64(): String = encodeBase64URLSafeString(this)
    fun String.decodeB64(): ByteArray = decodeBase64(this)
    fun String.encodeB64UrlSafe(): String = encodeBase64URLSafeString(this.toByteArray())

    data class JWTClaim(
        val iss: String,
        val aud: String,
        val sub: String,
        val exp: String
    )

    data class JWTClaimHeader(val alg: String)

    data class AccessTokenResponse(
        val access_token: String,
        val scope: String,
        val instance_url: String,
        val id: String,
        val token_type: String
    )
}
