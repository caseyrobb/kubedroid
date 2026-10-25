package com.neozeon.kubedroid.data.kubernetes

/**
 * A secret string whose [toString] never leaks material (spec 03: parse
 * results must be redaction-safe). Call [expose] at the ApiClient build
 * site — the only place material is legitimately needed.
 */
@JvmInline
value class SecretString(private val value: String) {
    fun expose(): String = value
    override fun toString(): String = "Secret(***)"
}

/** How a user authenticates, as classified by [KubeconfigParser] (spec 03 "Auth methods"). */
sealed interface AuthMethod {
    data class BearerToken(val token: SecretString) : AuthMethod
    data class ClientCert(val certData: SecretString, val keyData: SecretString) : AuthMethod
    data class Basic(val username: String, val password: SecretString) : AuthMethod
    /** Static OIDC id-token, no refresh (spec 03: refresh is a non-goal). */
    data class OidcIdToken(val idToken: SecretString) : AuthMethod
    /** No credentials found for this user; surfaced as a warning. */
    data object Anonymous : AuthMethod
}

data class ParsedCluster(
    val name: String,
    val server: String,
    val caCertData: SecretString?,
    val insecureSkipTlsVerify: Boolean,
)

data class ParsedUser(
    val name: String,
    val auth: AuthMethod,
)

data class ParsedContext(
    val name: String,
    val clusterName: String,
    val userName: String?,
    val namespace: String?,
)

/** Successful parse; safe to log — every secret field is a [SecretString]. */
data class ParsedKubeconfig(
    val contexts: List<ParsedContext>,
    val clusters: List<ParsedCluster>,
    val users: List<ParsedUser>,
    val currentContextName: String?,
)

sealed interface ParseResult {
    data class Valid(val config: ParsedKubeconfig, val warnings: List<String>) : ParseResult
    data class Invalid(val error: KubeconfigError) : ParseResult
}

/** Typed, user-actionable import failures (spec 03 "Auth methods"). */
sealed class KubeconfigError(open val reason: String) {
    data class Malformed(override val reason: String) : KubeconfigError(reason)
    data object NoContexts : KubeconfigError("the kubeconfig defines no contexts")
    data class DanglingReference(
        val contextName: String,
        val kind: String,
        val ref: String,
    ) : KubeconfigError("context '$contextName' references unknown $kind '$ref'")

    data class Unsupported(override val reason: String) : KubeconfigError(reason)

    /** Human-readable text shown verbatim in the add-cluster UI. */
    fun userMessage(): String = when (this) {
        is Malformed -> "This is not a valid kubeconfig: $reason"
        NoContexts -> "This kubeconfig defines no contexts; nothing to connect to."
        is DanglingReference -> reason
        is Unsupported -> reason
    }
}
