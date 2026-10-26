package com.neozeon.kubedroid.data.kubernetes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 07 stage 1 Testing: CRD parsing, skip rules, version selection. */
class CrdDiscoveryTest {

    private fun crdJson(
        plural: String = "keycloakoidcclients",
        group: String = "k8s.keycloak.org",
        kind: String = "KeycloakOIDCClient",
        scope: String = "Namespaced",
        versions: String = """[{"name":"v2alpha1","served":true,"storage":true}]""",
        shortNames: String? = """["koidc"]""",
        extraNames: String = "",
    ): Map<String, Any?> {
        val sn = if (shortNames == null) "" else ""","shortNames":$shortNames"""
        return com.google.gson.Gson().fromJson(
            """{"apiVersion":"apiextensions.k8s.io/v1","kind":"CustomResourceDefinition",
               "metadata":{"name":"$plural.$group"},
               "spec":{"group":"$group","scope":"$scope",
                 "names":{"plural":"$plural","kind":"$kind","listKind":"${kind}List"$sn$extraNames},
                 "versions":$versions}}""".trimIndent(),
            Map::class.java,
        ) as Map<String, Any?>
    }

    @Test fun `namespaced crd parses fully`() {
        val crd = CrdDiscovery.parse(crdJson())!!
        assertEquals("keycloakoidcclients.k8s.keycloak.org", crd.name)
        assertEquals("k8s.keycloak.org", crd.group)
        assertEquals("KeycloakOIDCClient", crd.kind)
        assertEquals("KeycloakOIDCClientList", crd.listKind)
        assertEquals("keycloakoidcclients", crd.plural)
        assertEquals(listOf("koidc"), crd.shortNames)
        assertTrue(crd.namespaced)
        assertEquals(listOf("v2alpha1"), crd.servedVersions)
        assertEquals("v2alpha1", crd.storageVersion)
        assertEquals("v2alpha1", crd.defaultVersion)
    }

    @Test fun `cluster scoped crd marks namespaced false`() {
        val crd = CrdDiscovery.parse(crdJson(scope = "Cluster", plural = "clusters", kind = "Cluster"))!!
        assertEquals(false, crd.namespaced)
    }

    @Test fun `default version prefers storage when served else first served`() {
        val storageServed = CrdDiscovery.parse(
            crdJson(versions = """[{"name":"v1","served":true},{"name":"v2","served":true,"storage":true}]"""),
        )!!
        assertEquals("v2", storageServed.defaultVersion)
        val storageNotServed = CrdDiscovery.parse(
            crdJson(versions = """[{"name":"v1","served":true},{"name":"v2","served":false,"storage":true}]"""),
        )!!
        assertEquals("v1", storageNotServed.defaultVersion)
    }

    @Test fun `printer columns parse per version with priority default`() {
        val crd = CrdDiscovery.parse(
            crdJson(versions = """[{"name":"v1","served":true,"storage":true,
              "additionalPrinterColumns":[
                {"name":"Ready","type":"string","jsonPath":"{.status.conditions[0].status}"},
                {"name":"Hidden","type":"string","jsonPath":"{.spec.x}","priority":1}]}]"""),
        )!!
        val all = crd.printerColumns["v1"]!!
        assertEquals(2, all.size)
        assertEquals(0, all[0].priority)
        assertEquals(1, all[1].priority)
        assertEquals(listOf("Ready"), crd.columnsFor("v1").map { it.name }) // Q3: priority 0 only
    }

    @Test fun `malformed crds are skipped not fatal`() {
        assertNull(CrdDiscovery.parse(crdJson(group = "")))                       // blank group
        assertNull(CrdDiscovery.parse(crdJson(plural = "")))                      // blank plural
        assertNull(CrdDiscovery.parse(crdJson(kind = "")))                        // blank kind
        assertNull(CrdDiscovery.parse(crdJson(scope = "Weird")))                  // bad scope
        assertNull(CrdDiscovery.parse(crdJson(versions = "[]")))                  // no versions
        assertNull(CrdDiscovery.parse(crdJson(versions = """[{"name":"v1","served":false}]"""))) // none served
        assertNull(CrdDiscovery.parse(emptyMap()))
    }

    @Test fun `parse list skips malformed entries and keeps the good ones`() {
        val json = mapOf("items" to listOf(crdJson(), mapOf("spec" to emptyMap<String, Any>()), crdJson(plural = "widgets", kind = "Widget")))
        val defs = CrdDiscovery.parseList(json)
        assertEquals(2, defs.size)
        assertEquals(listOf("keycloakoidcclients", "widgets"), defs.map { it.plural })
    }

    @Test fun `entry builder maps crd to catalog shape`() {
        val crd = CrdDiscovery.parse(crdJson())!!
        val entry = CrdDiscovery.entry(crd, "v2alpha1")
        assertEquals("KeycloakOIDCClient", entry.kind)
        assertEquals("k8s.keycloak.org/v2alpha1", entry.apiVersion)
        assertEquals("keycloakoidcclients", entry.resource)
        assertEquals(CatalogGroup.CUSTOM, entry.group)
        assertTrue(entry.namespaced)
        assertEquals(crd.kind + "s", entry.displayName) // pluralize()
    }

    @Test fun `pluralize handles regular sibilant and consonant-y endings`() {
        assertEquals("Widgets", CrdDiscovery.pluralize("Widget"))
        assertEquals("IngressRoutes", CrdDiscovery.pluralize("IngressRoute"))
        assertEquals("Boxes", CrdDiscovery.pluralize("Box"))
        assertEquals("Policies", CrdDiscovery.pluralize("Policy"))
        assertEquals("Days", CrdDiscovery.pluralize("Day"))
        assertEquals("Addresses", CrdDiscovery.pluralize("Address"))
    }

    @Test fun `crd list entry extractor renders served count and scope`() {
        val line = CrdDiscovery.crdListEntry.statusExtractor(crdJson())
        assertEquals("1 served version", line.primary)
        assertEquals("namespaced", line.secondary)
        val cluster = CrdDiscovery.crdListEntry.statusExtractor(crdJson(scope = "Cluster"))
        assertEquals("cluster", cluster.secondary)
    }
}

/** Spec 07 stage 1 Testing: dotted-path jsonPath subset evaluator. */
class PrinterColumnEvaluatorTest {

    private val obj = mapOf<String, Any?>(
        "spec" to mapOf("replicas" to 3.0, "paused" to false, "nested" to mapOf("x" to "yes")),
        "status" to mapOf("phase" to "Running", "list" to listOf("a"), "cond" to mapOf("type" to "Ready")),
    )

    @Test fun `braces dot-prefix and dotted paths render`() {
        assertEquals("Running", PrinterColumnEvaluator.render("{.status.phase}", obj))
        assertEquals("yes", PrinterColumnEvaluator.render(".spec.nested.x", obj))
        assertEquals("3", PrinterColumnEvaluator.render("{.spec.replicas}", obj))   // Gson Double -> int text
        assertEquals("false", PrinterColumnEvaluator.render("{.spec.paused}", obj))
    }

    @Test fun `absent keys and unsupported expressions degrade to dash`() {
        assertEquals("-", PrinterColumnEvaluator.render("{.status.missing}", obj))
        assertEquals("-", PrinterColumnEvaluator.render("{.spec.replicas.deep}", obj)) // through scalar
        assertEquals("-", PrinterColumnEvaluator.render("{.status.list}", obj))        // array
        assertEquals("-", PrinterColumnEvaluator.render("{.status}", obj))             // object
        assertEquals("-", PrinterColumnEvaluator.render("{.status.list[0]}", obj))     // index syntax
        assertEquals("-", PrinterColumnEvaluator.render(null, obj))
        assertEquals("-", PrinterColumnEvaluator.render("{}", obj))
        assertEquals("-", PrinterColumnEvaluator.render("", obj))
    }
}

/** Spec 07 stage 1 Testing: resolver precedence and cache semantics. */
class CatalogResolverTest {

    private val conn = java.util.UUID.randomUUID()

    private fun crd(plural: String = "widgets", group: String = "example.com", kind: String = "Widget") =
        CrdDiscovery.parse(
            com.google.gson.Gson().fromJson(
                """{"metadata":{"name":"$plural.$group"},
                   "spec":{"group":"$group","scope":"Namespaced",
                     "names":{"plural":"$plural","kind":"$kind"},
                     "versions":[{"name":"v1","served":true,"storage":true}]}}""",
                Map::class.java,
            ) as Map<String, Any?>,
        )!!

    @Test fun `static table wins on key collision and discovered keys coexist`() {
        val resolver = CatalogResolver()
        // A discovered CRD whose plural collides with a built-in resource
        // name lives under its own group key; the core table is untouched.
        resolver.publish(conn, listOf(crd(plural = "pods", kind = "FakePod")))
        assertEquals("Pod", resolver.resolve(conn, "v1", "pods")?.kind)             // static, not the fake
        assertEquals("FakePod", resolver.resolve(conn, "example.com/v1", "pods")?.kind)
    }

    @Test fun `discovered entry resolves after publish and clears after clear`() {
        val resolver = CatalogResolver()
        assertNull(resolver.resolve(conn, "example.com/v1", "widgets"))
        resolver.publish(conn, listOf(crd()))
        assertEquals("Widget", resolver.resolve(conn, "example.com/v1", "widgets")?.kind)
        resolver.clear(conn)
        assertNull(resolver.resolve(conn, "example.com/v1", "widgets"))
    }

    @Test fun `malformed never shadows static and unknown keys miss`() {
        val resolver = CatalogResolver()
        resolver.publish(conn, listOf(crd()))
        assertEquals("Pod", resolver.resolve(conn, "v1", "pods")?.kind)       // static intact
        assertNull(resolver.resolve(conn, "example.com/v1", "nothere"))       // miss
        assertNull(resolver.resolve(java.util.UUID.randomUUID(), "example.com/v1", "widgets")) // other connection
    }

    @Test fun `entryFor selects served versions with default fallback`() {
        val resolver = CatalogResolver()
        val multi = CrdDiscovery.parse(
            com.google.gson.Gson().fromJson(
                """{"metadata":{"name":"widgets.example.com"},
                   "spec":{"group":"example.com","scope":"Namespaced",
                     "names":{"plural":"widgets","kind":"Widget"},
                     "versions":[{"name":"v1","served":true},
                                 {"name":"v2","served":true,"storage":true}]}}""",
                Map::class.java,
            ) as Map<String, Any?>,
        )!!
        resolver.publish(conn, listOf(multi))
        assertEquals("example.com/v2", resolver.entryFor(conn, "example.com/v2", "widgets", null)?.apiVersion) // default = storage
        assertEquals("example.com/v1", resolver.entryFor(conn, "example.com/v2", "widgets", "v1")?.apiVersion) // explicit served
        assertEquals("example.com/v2", resolver.entryFor(conn, "example.com/v2", "widgets", "v9")?.apiVersion) // unserved -> default
        assertEquals("Widget", resolver.definition(conn, "example.com/v2", "widgets")?.kind)
    }
}
