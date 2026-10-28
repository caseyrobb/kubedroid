package com.neozeon.kubedroid.data.kubernetes

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.neozeon.kubedroid.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec 08 stage 1 Testing: every static catalog row carries its official
 * kind icon, CRD-derived entries resolve through the same helper, and
 * unknown kinds fall back to the official crd.svg — never 0/blank.
 */
class ResourceCatalogIconTest {

    @Test fun `every static entry has a non-zero icon`() {
        for (e in ResourceCatalog.entries) {
            assertTrue("${e.kind} has no icon", e.iconVector != 0)
        }
    }

    @Test fun `static kinds get distinct icons`() {
        val icons = ResourceCatalog.entries.map { it.iconVector }
        assertEquals("two kinds share one icon", icons.size, icons.distinct().size)
    }

    @Test fun `crd list entry uses the crd icon`() {
        assertEquals(R.drawable.ic_k8s_crd, CrdDiscovery.crdListEntry.iconVector)
    }

    @Test fun `unknown kind falls back to the crd icon`() {
        assertEquals(R.drawable.ic_k8s_crd, catalogIcon("example.com/v1", "Frobulator"))
        assertEquals(R.drawable.ic_k8s_crd, catalogIcon("v1", ""))
    }

    @Test fun `node uses the material server glyph, not the fallback`() {
        assertEquals(R.drawable.ic_k8s_node, catalogIcon("v1", "Node"))
    }

    @Test fun `dynamic crd entry resolves kind icon with crd fallback`() {
        val json = com.google.gson.Gson().fromJson(
            """{"apiVersion":"apiextensions.k8s.io/v1","kind":"CustomResourceDefinition",
               "metadata":{"name":"widgets.example.com"},
               "spec":{"group":"example.com","scope":"Namespaced",
                 "names":{"plural":"widgets","kind":"Widget","listKind":"WidgetList"},
                 "versions":[{"name":"v1","served":true,"storage":true}]}}""".trimIndent(),
            Map::class.java,
        ) as Map<String, Any?>
        val crd = CrdDiscovery.parse(json)!!
        val entry = CrdDiscovery.entry(crd, "v1")
        // "Widget" is not a built-in kind -> official crd icon.
        assertEquals(R.drawable.ic_k8s_crd, entry.iconVector)
        // A CRD whose kind collides with a built-in reuses the official icon.
        val podCrd = CrdDiscovery.parse(
            com.google.gson.Gson().fromJson(
                """{"apiVersion":"apiextensions.k8s.io/v1","kind":"CustomResourceDefinition",
                   "metadata":{"name":"pods.example.com"},
                   "spec":{"group":"example.com","scope":"Namespaced",
                     "names":{"plural":"pods","kind":"Pod","listKind":"PodList"},
                     "versions":[{"name":"v1","served":true,"storage":true}]}}""".trimIndent(),
                Map::class.java,
            ) as Map<String, Any?>,
        )!!
        assertEquals(R.drawable.ic_k8s_pod, CrdDiscovery.entry(podCrd, "v1").iconVector)
    }
}

/** Spec 08 Testing: the committed vectors actually inflate (robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResourceCatalogIconInflateTest {

    @Test fun `every static icon inflates`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        for (e in ResourceCatalog.entries) {
            assertNotNull("${e.kind} icon failed to inflate", app.getDrawable(e.iconVector))
        }
        assertNotNull(app.getDrawable(catalogIcon("x/v1", "Unknown")))
    }
}
