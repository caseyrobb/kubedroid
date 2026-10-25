package com.neozeon.kubedroid.data.kubernetes

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import javax.inject.Inject
import java.util.Base64

/**
 * Own validation layer over raw kubeconfig YAML (spec 03: detection happens
 * here, not via client-java exceptions, so errors stay actionable).
 * Parses with snakeyaml's SafeConstructor — aliases/objects are rejected.
 */
class KubeconfigParser @Inject constructor() {

    fun parse(raw: String): ParseResult {
        val root = try {
            Yaml(SafeConstructor(LoaderOptions())).load<Any?>(raw)
        } catch (e: Exception) {
            return ParseResult.Invalid(KubeconfigError.Malformed(e.message ?: "yaml load failed"))
        }
        if (root !is Map<*, *>) {
            return ParseResult.Invalid(KubeconfigError.Malformed("top level is not a mapping"))
        }

        val warnings = mutableListOf<String>()

        val clustersRaw = root.listOfMaps("clusters")
        val usersRaw = root.listOfMaps("users")
        val contextsRaw = root.listOfMaps("contexts")

        if (contextsRaw.isEmpty()) return ParseResult.Invalid(KubeconfigError.NoContexts)

        // ---- clusters -------------------------------------------------
        val clusters = mutableListOf<ParsedCluster>()
        for (entry in clustersRaw) {
            val name = entry.str("name")
                ?: return invalid(KubeconfigError.Malformed("a clusters[] entry has no name"))
            val body = entry.mapOf("cluster")
                ?: return invalid(KubeconfigError.Malformed("cluster '$name' has no cluster body"))
            val server = body.str("server")
                ?: return invalid(KubeconfigError.Malformed("cluster '$name' has no server"))
            if (!server.startsWith("https://") && !server.startsWith("http://")) {
                return invalid(
                    KubeconfigError.Malformed("cluster '$name' server '$server' is not an http(s) URL"),
                )
            }
            if (server.startsWith("http://")) {
                warnings += "cluster '$name' uses plaintext http"
            }
            if (body.containsKey("certificate-authority")) {
                return invalid(
                    KubeconfigError.Unsupported(
                        "cluster '$name' uses certificate-authority (a file path). File paths " +
                            "from a kubeconfig do not exist on Android — re-export with embedded " +
                            "certificate-authority-data.",
                    ),
                )
            }
            val caSecret = body.str("certificate-authority-data")
            val caData: SecretString? = if (caSecret != null) {
                if (decodeBase64(caSecret) == null) {
                    return invalid(
                        KubeconfigError.Malformed("cluster '$name' has invalid certificate-authority-data"),
                    )
                }
                SecretString(caSecret)
            } else {
                null
            }
            clusters += ParsedCluster(
                name = name,
                server = server,
                caCertData = caData,
                insecureSkipTlsVerify = body.bool("insecure-skip-tls-verify"),
            )
        }

        // ---- users ----------------------------------------------------
        val users = mutableListOf<ParsedUser>()
        for (entry in usersRaw) {
            val name = entry.str("name")
                ?: return invalid(KubeconfigError.Malformed("a users[] entry has no name"))
            val body = entry.mapOf("user") ?: emptyMap<Any?, Any?>()
            when (val classified = classifyUser(name, body, warnings)) {
                is UserOutcome.Err -> return ParseResult.Invalid(classified.error)
                is UserOutcome.Ok -> users += ParsedUser(name, classified.auth)
            }
        }

        // ---- contexts -------------------------------------------------
        val clusterNames = clusters.map { it.name }.toSet()
        val userNames = users.map { it.name }.toSet()
        val contexts = mutableListOf<ParsedContext>()
        for (entry in contextsRaw) {
            val name = entry.str("name")
                ?: return invalid(KubeconfigError.Malformed("a contexts[] entry has no name"))
            val body = entry.mapOf("context")
                ?: return invalid(KubeconfigError.Malformed("context '$name' has no context body"))
            val clusterRef = body.str("cluster")
                ?: return invalid(
                    KubeconfigError.DanglingReference(name, "cluster", "<missing>"),
                )
            if (clusterRef !in clusterNames) {
                return invalid(KubeconfigError.DanglingReference(name, "cluster", clusterRef))
            }
            val userRef = body.str("user")
            if (userRef != null && userRef !in userNames) {
                return invalid(KubeconfigError.DanglingReference(name, "user", userRef))
            }
            contexts += ParsedContext(
                name = name,
                clusterName = clusterRef,
                userName = userRef,
                namespace = body.str("namespace"),
            )
        }

        val config = ParsedKubeconfig(
            contexts = contexts,
            clusters = clusters,
            users = users,
            currentContextName = root.str("current-context"),
        )
        return ParseResult.Valid(config, warnings)
    }

    // ------------------------------------------------------------------
    // user classification
    // ------------------------------------------------------------------

    private sealed interface UserOutcome {
        data class Ok(val auth: AuthMethod) : UserOutcome
        data class Err(val error: KubeconfigError) : UserOutcome
    }

    private fun classifyUser(name: String, body: Map<*, *>, warnings: MutableList<String>): UserOutcome {
        if (body.containsKey("exec")) {
            return UserOutcome.Err(
                KubeconfigError.Unsupported(
                    "user '$name' uses an exec credential plugin (e.g. kubelogin), which " +
                        "kubedroid cannot run on Android — re-issue this kubeconfig with a " +
                        "static token or client certificate.",
                ),
            )
        }
        // impersonation fields: warn and strip (spec 03 auth table)
        for (impersonationKey in listOf("act", "user-id", "groups", "extra")) {
            if (body.containsKey(impersonationKey)) {
                warnings += "user '$name' requested impersonation ($impersonationKey); stripped"
            }
        }

        val authProvider = body.mapOf("auth-provider")
        if (authProvider != null) {
            val provider = authProvider.str("name") ?: "<missing>"
            if (provider != "oidc") {
                return UserOutcome.Err(
                    KubeconfigError.Unsupported(
                        "user '$name' uses auth-provider '$provider', which kubedroid does not " +
                            "support — re-issue with a static token or client certificate.",
                    ),
                )
            }
            val config = authProvider.mapOf("config")
            val idToken = config?.str("id-token")
            if (idToken.isNullOrBlank()) {
                return UserOutcome.Err(
                    KubeconfigError.Unsupported(
                        "user '$name' uses the oidc auth-provider without a static id-token; " +
                            "kubedroid cannot refresh tokens — re-issue with a current id-token.",
                    ),
                )
            }
            if (config.containsKey("refresh-token") || config.containsKey("client-id")) {
                warnings += "user '$name' oidc refresh config ignored (no refresh on Android)"
            }
            return UserOutcome.Ok(AuthMethod.OidcIdToken(SecretString(idToken)))
        }

        val token = body.str("token")
        val certData = body.str("client-certificate-data")
        val keyData = body.str("client-key-data")
        val username = body.str("username")
        val password = body.str("password")

        for (pathKey in listOf("client-certificate", "client-key")) {
            if (body.containsKey(pathKey)) {
                return UserOutcome.Err(
                    KubeconfigError.Unsupported(
                        "user '$name' uses $pathKey (a file path). File paths from a kubeconfig " +
                            "do not exist on Android — re-export with embedded ${pathKey}-data.",
                    ),
                )
            }
        }

        val hasCert = certData != null || keyData != null
        if (hasCert) {
            if (certData == null || keyData == null) {
                return UserOutcome.Err(
                    KubeconfigError.Malformed(
                        "user '$name' has only one half of a client certificate pair " +
                            "(client-certificate-data and client-key-data are both required)",
                    ),
                )
            }
            val decodedCert = decodeBase64(certData)
                ?: return UserOutcome.Err(
                    KubeconfigError.Malformed("user '$name' has invalid client-certificate-data"),
                )
            val decodedKey = decodeBase64(keyData)
                ?: return UserOutcome.Err(
                    KubeconfigError.Malformed("user '$name' has invalid client-key-data"),
                )
            val keyPem = String(decodedKey, Charsets.US_ASCII)
            if (keyPem.contains("BEGIN RSA PRIVATE KEY") || keyPem.contains("BEGIN EC PRIVATE KEY")) {
                return UserOutcome.Err(
                    KubeconfigError.Unsupported(
                        "user '$name' uses a PKCS#1 private key; kubedroid requires PKCS#8 — " +
                            "convert with: openssl pkcs8 -topk8 -nocrypt -in key.pem",
                    ),
                )
            }
            if (!keyPem.contains("BEGIN PRIVATE KEY")) {
                return UserOutcome.Err(
                    KubeconfigError.Malformed(
                        "user '$name' client-key-data is not a PEM private key",
                    ),
                )
            }
            return UserOutcome.Ok(
                AuthMethod.ClientCert(SecretString(certData), SecretString(keyData)),
            )
        }

        if (token != null) return UserOutcome.Ok(AuthMethod.BearerToken(SecretString(token)))
        if (username != null || password != null) {
            if (username == null || password == null) {
                return UserOutcome.Err(
                    KubeconfigError.Malformed(
                        "user '$name' has only one half of basic auth (username and password " +
                            "are both required)",
                    ),
                )
            }
            return UserOutcome.Ok(AuthMethod.Basic(username, SecretString(password)))
        }

        warnings += "user '$name' has no credentials; connecting anonymously"
        return UserOutcome.Ok(AuthMethod.Anonymous)
    }

    // ------------------------------------------------------------------
    // yaml helpers
    // ------------------------------------------------------------------

    private fun invalid(error: KubeconfigError): ParseResult.Invalid = ParseResult.Invalid(error)

    private fun Map<*, *>.str(key: String): String? = (this[key] as? String)?.takeIf { it.isNotEmpty() }

    private fun Map<*, *>.bool(key: String): Boolean = (this[key] as? Boolean) ?: false

    private fun Map<*, *>.mapOf(key: String): Map<*, *>? = this[key] as? Map<*, *>

    private fun Map<*, *>.listOfMaps(key: String): List<Map<*, *>> =
        (this[key] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()

    private fun decodeBase64(value: String): ByteArray? = try {
        Base64.getMimeDecoder().decode(value)
    } catch (e: IllegalArgumentException) {
        null
    }
}
