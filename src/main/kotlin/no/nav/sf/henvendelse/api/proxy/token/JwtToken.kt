package no.nav.sf.henvendelse.api.proxy.token

import mu.KotlinLogging
import no.nav.security.token.support.core.jwt.JwtToken
import java.io.File

const val CLAIM_NAV_IDENT = "NAVident"
const val CLAIM_AZP_NAME = "azp_name"
const val CLAIM_ROLES = "roles"

private val log = KotlinLogging.logger { }

fun JwtToken.isMachineToken(): Boolean {
    val rolesClaim = this.jwtTokenClaims.get(CLAIM_ROLES)
    if (rolesClaim != null && (rolesClaim is ArrayList<*>)) {
        if (rolesClaim.map { it.toString() }.any { it == "access_as_application" }) {
            log.info("Confirmed machine token")
            File("/tmp/machinetoken").writeText(this.tokenAsString)
            return true
        }
    }
    return false
}

fun JwtToken.hasClaim(name: String) = this.jwtTokenClaims.get(name) != null

fun JwtToken.getClaim(name: String) = this.jwtTokenClaims.get(name)?.toString() ?: ""

fun JwtToken.isNavOBOToken() = this.hasClaim(CLAIM_NAV_IDENT)

fun JwtToken.getAzpName() = this.getClaim(CLAIM_AZP_NAME)

fun JwtToken.getNAVIdent() = this.getClaim(CLAIM_NAV_IDENT)
