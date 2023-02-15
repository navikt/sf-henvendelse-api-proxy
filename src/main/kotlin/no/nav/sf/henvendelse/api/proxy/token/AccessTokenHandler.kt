package no.nav.sf.henvendelse.api.proxy.token

import com.google.gson.Gson
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.security.PrivateKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.apache.http.HttpHost
import org.apache.http.client.config.CookieSpecs
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.HttpClients
import org.http4k.client.ApacheClient
import org.http4k.core.HttpHandler
import org.http4k.core.Method

object AccessTokenHandler {
    private val log = KotlinLogging.logger { }

    val accessToken get() = fetchAccessTokenAndInstanceUrl().first
    val instanceUrl get() = fetchAccessTokenAndInstanceUrl().second

    val SFTokenHost: Lazy<String> = lazy { System.getenv("SF_TOKENHOST") }

    val SFClientID = fetchVaultValue("SFClientID")
    val SFUsername = fetchVaultValue("SFUsername")
    val keystoreB64 = fetchVaultValue("KeystoreJKSB64")
    val keystorePassword = fetchVaultValue("KeystorePassword")
    val privateKeyAlias = fetchVaultValue("PrivateKeyAlias")
    val privateKeyPassword = fetchVaultValue("PrivateKeyPassword")

    val client: Lazy<HttpHandler> = lazy { ApacheClient.supportProxy(System.getenv("HTTPS_PROXY")) }

    val gson = Gson()

    const val expTimeSeconds = 300 // 5 min

    private var lastTokenPair = Pair("", "")

    var expireTime = System.currentTimeMillis()

    val hello = "hello"

    fun fetchVaultValue(vaultKey: String): String {
        val vaultPath = "/var/run/secrets/nais.io/vault"
        return File("$vaultPath/$vaultKey").readText(Charsets.UTF_8)
    }

    fun fetchAccessTokenAndInstanceUrl(): Pair<String, String> {
        if (System.currentTimeMillis() < expireTime) {
            return lastTokenPair
        }
        val expireSeconds = (System.currentTimeMillis() / 1000) + expTimeSeconds
        val claim = JWTClaim(
            iss = SFClientID,
            aud = SFTokenHost.value,
            sub = SFUsername,
            exp = expireSeconds.toString() // seconds (not milliseconds) since Epoch
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

        val accessTokenRequest = org.http4k.core.Request(Method.POST, SFTokenHost.value)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .query("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
            .query("assertion", "$claimWithHeaderJsonUrlSafe.$fullClaimSignature")

        for (retry in 1..4) {
            try {
                val response = client.value(accessTokenRequest)
                if (response.status.code == 200) {
                    val accessTokenResponse = gson.fromJson(response.bodyString(), AccessTokenResponse::class.java)
                    lastTokenPair = Pair(accessTokenResponse.access_token, accessTokenResponse.instance_url)
                    expireTime = expireSeconds * 1000
                    return lastTokenPair
                }
            } catch (e: Exception) {
                log.error("Attempt to fetch access token $retry of 3 failed by ${e.message} stack: ${e.printStackTrace()}}")
                runBlocking { delay(retry * 1000L) }
            }
        }
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

    fun ByteArray.encodeB64(): String = org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(this)
    fun String.decodeB64(): ByteArray = org.apache.commons.codec.binary.Base64.decodeBase64(this)
    fun String.encodeB64UrlSafe(): String =
        org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(this.toByteArray())
}

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

private fun ApacheClient.supportProxy(httpsProxy: String): HttpHandler = httpsProxy.let { p ->
    when {
        p.isEmpty() -> this()
        else -> {
            val up = URI(p)
            this(
                client =
                HttpClients.custom()
                    .setDefaultRequestConfig(
                        RequestConfig.custom()
                            .setProxy(HttpHost(up.host, up.port, up.scheme))
                            .setRedirectsEnabled(false)
                            .setCookieSpec(CookieSpecs.IGNORE_COOKIES)
                            .build()
                    )
                    .build()
            )
        }
    }
}
