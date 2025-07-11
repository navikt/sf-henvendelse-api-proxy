package no.nav.sf.henvendelse.api.proxy.token

import no.nav.security.token.support.core.jwt.JwtToken
import org.http4k.core.Request

interface TokenValidator {
    fun firstValidToken(request: Request, tokenFetchStats: Statistics): JwtToken?
}
