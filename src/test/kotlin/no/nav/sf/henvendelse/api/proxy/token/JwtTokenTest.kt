package no.nav.sf.henvendelse.api.proxy.token

import no.nav.security.token.support.core.jwt.JwtToken
import no.nav.sf.henvendelse.api.proxy.readResourceFile
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class JwtTokenTest {

    @Test
    fun `Jwt token with role access_as_application should be considered a machine token`() {
        val simulatedMachineToken = JwtToken(readResourceFile("/simulatedmachinetoken"))
        Assertions.assertTrue(simulatedMachineToken.isMachineToken())
    }

    /*
    @Test
    fun `Make compare`() {
        val cache = readResourceFile("/Scratch_0_CACHE")
        val sf = readResourceFile("/Scratch_0_SF")
        val cacheEl = JsonParser.parseString(cache)
        val cacheSf = JsonParser.parseString(sf)
        println(JsonComparator.jsonEquals(cacheEl, cacheSf))

    }
     */
}
