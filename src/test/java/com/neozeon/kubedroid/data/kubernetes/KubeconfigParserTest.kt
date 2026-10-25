package com.neozeon.kubedroid.data.kubernetes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Spec 03 Testing: parser coverage per auth method, exec rejection,
 * malformed input, PKCS#1 rejection, impersonation stripping,
 * current-context selection, and redaction-safe toString.
 */
class KubeconfigParserTest {

    private val parser = KubeconfigParser()

    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    private val pkcs8Key = "-----BEGIN PRIVATE KEY-----\nZm9va2V5a2V5\n-----END PRIVATE KEY-----"
    private val cert = "-----BEGIN CERTIFICATE-----\nZm9va2VjZXJ0\n-----END CERTIFICATE-----"

    private fun kubeconfig(usersBlock: String, currentContext: String = "ctx1") = """
        apiVersion: v1
        kind: Config
        current-context: $currentContext
        clusters:
        - name: c1
          cluster:
            server: https://api.example.com:6443
            certificate-authority-data: ${b64("FAKE-CA")}
        contexts:
        - name: ctx1
          context:
            cluster: c1
            user: u1
            namespace: default
        users:
    """.trimIndent() + "\n" + usersBlock

    private fun valid(result: ParseResult): ParsedKubeconfig {
        assertTrue("expected Valid, got $result", result is ParseResult.Valid)
        return (result as ParseResult.Valid).config
    }

    private fun invalidOf(result: ParseResult): KubeconfigError {
        assertTrue("expected Invalid, got $result", result is ParseResult.Invalid)
        return (result as ParseResult.Invalid).error
    }

    @Test fun tokenAuthClassified() {
        val cfg = valid(
            parser.parse(kubeconfig("- name: u1\n  user:\n    token: secret-tok-n3v3r-seen")),
        )
        val auth = cfg.users.single().auth
        assertTrue(auth is AuthMethod.BearerToken)
        assertEquals("secret-tok-n3v3r-seen", (auth as AuthMethod.BearerToken).token.expose())
        assertEquals("ctx1", cfg.currentContextName)
        assertEquals("https://api.example.com:6443", cfg.clusters.single().server)
    }

    @Test fun clientCertAuthClassified() {
        val cfg = valid(
            parser.parse(
                kubeconfig(
                    "- name: u1\n  user:\n    client-certificate-data: ${b64(cert)}\n" +
                        "    client-key-data: ${b64(pkcs8Key)}",
                ),
            ),
        )
        assertTrue(cfg.users.single().auth is AuthMethod.ClientCert)
    }

    @Test fun basicAuthClassified() {
        val cfg = valid(
            parser.parse(kubeconfig("- name: u1\n  user:\n    username: admin\n    password: s3cret")),
        )
        val auth = cfg.users.single().auth
        assertTrue(auth is AuthMethod.Basic)
        assertEquals("admin", (auth as AuthMethod.Basic).username)
    }

    @Test fun staticOidcAuthClassified() {
        val cfg = valid(
            parser.parse(
                kubeconfig(
                    "- name: u1\n  user:\n    auth-provider:\n      name: oidc\n" +
                        "      config:\n        id-token: eyJhbGciOiJIUzI1NiJ9.e30.abc\n" +
                        "        refresh-token: rt-ignored\n        client-id: kubedroid",
                ),
            ),
        )
        assertTrue(cfg.users.single().auth is AuthMethod.OidcIdToken)
    }

    @Test fun execRejectedWithActionableMessage() {
        val err = invalidOf(
            parser.parse(
                kubeconfig(
                    "- name: u1\n  user:\n    exec:\n      command: kubelogin\n" +
                        "      args: [get-token, --login=aad]",
                ),
            ),
        )
        assertTrue(err is KubeconfigError.Unsupported)
        val msg = err.userMessage()
        assertTrue("message names exec: $msg", msg.contains("exec"))
        assertTrue("message names kubelogin: $msg", msg.contains("kubelogin"))
    }

    @Test fun nonOidcAuthProviderRejected() {
        val err = invalidOf(
            parser.parse(
                kubeconfig("- name: u1\n  user:\n    auth-provider:\n      name: gcp\n      config:\n        access-token: x"),
            ),
        )
        assertTrue(err is KubeconfigError.Unsupported)
        assertTrue(err.userMessage().contains("gcp"))
    }

    @Test fun oidcWithoutIdTokenRejected() {
        val err = invalidOf(
            parser.parse(
                kubeconfig("- name: u1\n  user:\n    auth-provider:\n      name: oidc\n      config:\n        client-id: kubedroid"),
            ),
        )
        assertTrue(err is KubeconfigError.Unsupported)
        assertTrue(err.userMessage().contains("id-token"))
    }

    @Test fun malformedYamlRejected() {
        val err = invalidOf(parser.parse("\tthis: is: not: yaml:\n  - ["))
        assertTrue(err is KubeconfigError.Malformed)
    }

    @Test fun pkcs1KeyRejected() {
        val pkcs1 = "-----BEGIN RSA PRIVATE KEY-----\nZm9va2V5\n-----END RSA PRIVATE KEY-----"
        val err = invalidOf(
            parser.parse(
                kubeconfig(
                    "- name: u1\n  user:\n    client-certificate-data: ${b64(cert)}\n" +
                        "    client-key-data: ${b64(pkcs1)}",
                ),
            ),
        )
        assertTrue(err is KubeconfigError.Unsupported)
        assertTrue(err.userMessage().contains("PKCS#8"))
    }

    @Test fun halfCertPairRejected() {
        val err = invalidOf(
            parser.parse(kubeconfig("- name: u1\n  user:\n    client-key-data: ${b64(pkcs8Key)}")),
        )
        assertTrue(err is KubeconfigError.Malformed)
    }

    @Test fun filePathRefsRejected() {
        val err = invalidOf(
            parser.parse(kubeconfig("- name: u1\n  user:\n    client-key: /home/me/.kube/key.pem")),
        )
        assertTrue(err is KubeconfigError.Unsupported)
        assertTrue(err.userMessage().contains("file path", true) || err.userMessage().contains("File paths"))
    }

    @Test fun impersonationStrippedWithWarning() {
        val result = parser.parse(
            kubeconfig("- name: u1\n  user:\n    token: t\n    act:\n    - imp:person"),
        )
        assertTrue(result is ParseResult.Valid)
        val warnings = (result as ParseResult.Valid).warnings
        assertTrue("impersonation warning in $warnings", warnings.any { it.contains("impersonation") })
    }

    @Test fun noContextsRejected() {
        val err = invalidOf(
            parser.parse("apiVersion: v1\nkind: Config\nclusters: []\ncontexts: []\nusers: []"),
        )
        assertTrue(err is KubeconfigError.NoContexts)
    }

    @Test fun danglingClusterRefRejected() {
        val err = invalidOf(
            parser.parse(
                """
                apiVersion: v1
                kind: Config
                clusters:
                - name: c1
                  cluster:
                    server: https://x.example.com
                contexts:
                - name: ctx1
                  context:
                    cluster: missing-cluster
                    user: u1
                users:
                - name: u1
                  user:
                    token: t
                """.trimIndent(),
            ),
        )
        assertTrue(err is KubeconfigError.DanglingReference)
        assertEquals("missing-cluster", (err as KubeconfigError.DanglingReference).ref)
    }

    @Test fun currentContextSelected() {
        val cfg = valid(
            parser.parse(
                """
                apiVersion: v1
                kind: Config
                current-context: two
                clusters:
                - name: c1
                  cluster:
                    server: https://x.example.com
                contexts:
                - name: one
                  context: { cluster: c1 }
                - name: two
                  context: { cluster: c1 }
                users: []
                """.trimIndent(),
            ),
        )
        assertEquals("two", cfg.currentContextName)
        assertEquals(2, cfg.contexts.size)
    }

    @Test fun insecureSkipTlsVerifyHonored() {
        val cfg = valid(
            parser.parse(
                """
                apiVersion: v1
                kind: Config
                clusters:
                - name: c1
                  cluster:
                    server: https://x.example.com
                    insecure-skip-tls-verify: true
                contexts:
                - name: one
                  context: { cluster: c1 }
                users: []
                """.trimIndent(),
            ),
        )
        assertTrue(cfg.clusters.single().insecureSkipTlsVerify)
    }

    @Test fun toStringIsRedactionSafe() {
        val secret = "SUP3R-SECRET-T0KEN"
        val cfg = valid(parser.parse(kubeconfig("- name: u1\n  user:\n    token: $secret")))
        val rendered = cfg.toString() + parser.parse(kubeconfig("- name: u1\n  user:\n    password: $secret\n    username: u"))
        assertFalse("secret leaked into toString(): $rendered", rendered.contains(secret))
    }
}
