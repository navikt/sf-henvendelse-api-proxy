@file:Suppress("ktlint:standard:filename", "ktlint:standard:property-naming")

package no.nav.sf.henvendelse.api.proxy

/**
 * Naming convention applied to environment variable constants: a lowercase prefix separated from the actual constant, i.e. prefix_ENVIRONMENT_VARIABLE_NAME.
 *
 * Motivation:
 * The prefix provides contextual naming that describes the source and nature of the variables they represent while keeping the names short.
 * A prefix marks a constant representing an environment variable, and also where one can find the value of that variable
 *
 * - env: Denotes an environment variable typically injected into the pod by the Nais platform.
 *
 * - config: Denotes an environment variable explicitly configured in YAML files (see dev.yaml, prod.yaml)
 *
 * - secret: Denotes an environment variable loaded from a Kubernetes secret.
 */

const val config_SF_TOKENHOST = "SF_TOKENHOST"
const val config_DEPLOY_CLUSTER = "DEPLOY_CLUSTER"
const val config_TWINCALL = "TWINCALL"
const val config_AUDIENCE_TOKEN_SERVICE_URL = "AUDIENCE_TOKEN_SERVICE_URL"
const val config_AUDIENCE_TOKEN_SERVICE_ALIAS = "AUDIENCE_TOKEN_SERVICE_ALIAS"
const val config_AUDIENCE_TOKEN_SERVICE = "AUDIENCE_TOKEN_SERVICE"
const val config_SALESFORCE_AZURE_ALIAS = "SALESFORCE_AZURE_ALIAS"

const val env_AZURE_APP_WELL_KNOWN_URL = "AZURE_APP_WELL_KNOWN_URL"
const val env_AZURE_APP_CLIENT_ID = "AZURE_APP_CLIENT_ID"
const val env_AZURE_APP_CLIENT_SECRET = "AZURE_APP_CLIENT_SECRET"
const val env_AZURE_OPENID_CONFIG_TOKEN_ENDPOINT = "AZURE_OPENID_CONFIG_TOKEN_ENDPOINT"
const val env_HTTPS_PROXY = "HTTPS_PROXY"

const val secret_KEYSTORE_JKS_B64 = "KEYSTORE_JKS_B64"
const val secret_KEYSTORE_PASSWORD = "KEYSTORE_PASSWORD"
const val secret_PRIVATE_KEY_ALIAS = "PRIVATE_KEY_ALIAS"
const val secret_PRIVATE_KEY_PASSWORD = "PRIVATE_KEY_PASSWORD"
const val secret_SF_CLIENT_ID = "SF_CLIENT_ID"
const val secret_SF_USERNAME = "SF_USERNAME"
const val secret_USE_CACHE = "USE_CACHE"
const val secret_ENFORCE_HTTP_1_1 = "ENFORCE_HTTP_1_1"

/**
 * Shortcut for fetching environment variables
 */
fun env(name: String): String = System.getenv(name) ?: throw NullPointerException("Missing env $name")
