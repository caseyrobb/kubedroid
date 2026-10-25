package com.neozeon.kubedroid.qrexport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 03 Testing (stage 7): strip semantics of tools/qr-export. */
class KubeconfigStripperTest {

    private val yaml = """
        apiVersion: v1
        kind: Config
        clusters:
        - name: c1
          cluster:
            server: https://10.0.2.2:6443
            certificate-authority-data: Q0FEQVRB
        users:
        - name: token-user
          user:
            token: abc123
        - name: exec-user
          user:
            exec:
              apiVersion: client.authentication.k8s.io/v1beta1
              command: kubelogin
              args:
              - get-token
        - name: gcp-user
          user:
            auth-provider:
              name: gcp
              config:
                cmd-path: gcloud
        contexts:
        - name: ctx
          context:
            cluster: c1
            user: token-user
        current-context: ctx
    """.trimIndent()

    @Test fun stripsExecAndAuthProviderOnly() {
        val out = KubeconfigStripper.strip(yaml)
        // Structural check: 'exec' also appears in the user NAME 'exec-user',
        // so substring tests on the whole document are meaningless — inspect
        // the parsed users[].user maps the way the app will.
        @Suppress("UNCHECKED_CAST")
        val root = org.yaml.snakeyaml.Yaml(
            org.yaml.snakeyaml.constructor.SafeConstructor(org.yaml.snakeyaml.LoaderOptions()))
            .load<Any?>(out.yaml) as Map<String, Any?>
        val users = root["users"] as List<Map<String, Any?>>
        for (u in users) {
            val body = u["user"] as Map<String, Any?>
            assertFalse("user ${u["name"]} still has exec", body.containsKey("exec"))
            assertFalse("user ${u["name"]} still has auth-provider", body.containsKey("auth-provider"))
        }
        assertFalse("kubelogin must be gone", out.yaml.contains("kubelogin"))
        assertFalse("gcloud must be gone", out.yaml.contains("gcloud"))
        // Everything the app CAN use survives verbatim in spirit.
        assertTrue(out.yaml.contains("token: abc123"))
        assertTrue(out.yaml.contains("certificate-authority-data: Q0FEQVRB"))
        assertTrue(out.yaml.contains("server: https://10.0.2.2:6443"))
        assertTrue(out.yaml.contains("current-context: ctx"))
    }

    @Test fun reportsEachRemoval() {
        val out = KubeconfigStripper.strip(yaml)
        assertTrue("expected exec removal note, got ${out.removals}",
            out.removals.any { it.contains("exec-user") && it.contains("exec") })
        assertTrue("expected auth-provider removal note, got ${out.removals}",
            out.removals.any { it.contains("gcp-user") && it.contains("auth-provider") })
        assertTrue("expected anonymous warning, got ${out.removals}",
            out.removals.any { it.contains("exec-user") && it.contains("anonymous") })
        assertTrue("expected anonymous warning for gcp, got ${out.removals}",
            out.removals.any { it.contains("gcp-user") && it.contains("anonymous") })
    }

    @Test fun noStrippingWhenNothingUnsupported() {
        val minimal = """
            apiVersion: v1
            kind: Config
            clusters: [{name: c1, cluster: {server: "https://x:6443"}}]
            users: [{name: u, user: {token: t}}]
            contexts: [{name: ctx, context: {cluster: c1, user: u}}]
        """.trimIndent()
        val out = KubeconfigStripper.strip(minimal)
        assertEquals(emptyList<String>(), out.removals)
        assertTrue(out.yaml.contains("token: t"))
    }

    @Test fun outputReparsesAsYaml() {
        val out = KubeconfigStripper.strip(yaml)
        // Must survive SafeConstructor like the app will load it.
        val reparsed = org.yaml.snakeyaml.Yaml(
            org.yaml.snakeyaml.constructor.SafeConstructor(org.yaml.snakeyaml.LoaderOptions()))
            .load<Any?>(out.yaml)
        assertTrue(reparsed is Map<*, *>)
    }
}
