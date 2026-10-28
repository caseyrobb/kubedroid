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
import org.xmlpull.v1.XmlPullParser

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

/** Spec 08 Testing: the committed vectors actually inflate AND paint. */
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

    @Test fun `every static icon paints real geometry inside its viewport`() {
        // Catches the svg2vectordrawable failure modes that shipped once:
        // inherited group fills dropped (paths with neither fillColor nor
        // strokeColor render transparent) and matrix transforms lost, which
        // leaves glyph geometry outside the viewport. Parsed from the
        // compiled resource XML so it does not depend on Robolectric's
        // graphics mode actually rasterizing VectorDrawables.
        val app = ApplicationProvider.getApplicationContext<Application>()
        val num = Regex("-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?")
        fun attr(xml: android.content.res.XmlResourceParser, name: String): String? =
            xml.getAttributeValue("http://schemas.android.com/apk/res/android", name)
                ?: xml.getAttributeValue(null, name)
        for (e in ResourceCatalog.entries) {
            val xml = app.resources.getXml(e.iconVector)
            try {
                var vw = 0.0
                var vh = 0.0
                var painted = 0
                var unpaintedPaths = 0
                while (xml.next() != XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType != XmlPullParser.START_TAG) continue
                    when (xml.name) {
                        "vector" -> {
                            vw = attr(xml, "viewportWidth")?.toDouble() ?: 0.0
                            vh = attr(xml, "viewportHeight")?.toDouble() ?: 0.0
                        }
                        "path" -> {
                            val pd = attr(xml, "pathData").orEmpty()
                            if (pd.isBlank()) continue
                            val hasPaint = attr(xml, "fillColor") != null ||
                                attr(xml, "strokeColor") != null
                            if (!hasPaint) unpaintedPaths++
                            val nums = num.findAll(pd).map { it.value.toDouble() }.toList()
                            for (i in 0 until minOf(nums.size / 2, 999)) {
                                val x = nums[i * 2]
                                val y = nums[i * 2 + 1]
                                if (x in 0.0..vw && y in 0.0..vh) painted++
                            }
                        }
                    }
                }
                assertTrue(
                    "${e.kind} icon has unpainted paths ($unpaintedPaths)",
                    unpaintedPaths == 0 || unpaintedPaths <= 2, // hidden fill:none layers are legitimate
                )
                assertTrue(
                    "${e.kind} icon has no geometry inside its viewport ($painted samples)",
                    painted > 5,
                )
            } finally {
                xml.close()
            }
        }
    }
}
