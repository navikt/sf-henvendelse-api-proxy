package no.nav.sf.henvendelse.api.proxy.token

/**
 * A handler for oauth2 access flow to salesforce.
 * @see [sf.remoteaccess_oauth_jwt_flow](https://help.salesforce.com/s/articleView?id=sf.remoteaccess_oauth_jwt_flow.htm&type=5)
 *
 * Fetches and caches access token, also retrieves instance url
 */
interface AccessTokenHandler {
    val accessToken: String
    val instanceUrl: String

    fun refreshToken()
}
