package com.neozeon.kubedroid.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Spec 11 stage 2 Testing: the in-repo fastlane metadata (F-Droid store
 * source of truth) must satisfy the fdroiddata MR checklist — short
 * description limits, changelog present for the current versionCode, and
 * real PNG artwork. Gradle unit-test workers run with the project root as
 * cwd (flat layout).
 */
class FastlaneMetadataTest {

    private val root = File("fastlane/metadata/android/en-US")

    private fun text(name: String): String {
        val f = File(root, name)
        assertTrue("missing $name", f.isFile)
        return f.readText().trim()
    }

    @Test fun `title is present and short`() {
        val t = text("title.txt")
        assertTrue(t.isNotEmpty())
        assertTrue("title too long", t.length <= 50)
    }

    @Test fun `short_description fits F-Droid limits`() {
        val s = text("short_description.txt")
        assertTrue("empty", s.isNotEmpty())
        assertTrue("max 80 chars, got ${s.length}", s.length <= 80)
        assertTrue("single line", !s.contains("\n"))
        assertTrue("no trailing dot", !s.endsWith("."))
    }

    @Test fun `full_description is substantive`() {
        val f = text("full_description.txt")
        assertTrue("too short", f.length >= 200)
        assertTrue("privacy posture must be stated",
            f.contains("telemetry", ignoreCase = true) && f.contains("Keystore"))
    }

    @Test fun `changelog exists for current versionCode`() {
        val gradle = File("build.gradle.kts").readText()
        val vc = Regex("""versionCode\s*=\s*(\d+)""").find(gradle)!!
            .groupValues[1].toInt()
        val c = text("changelogs/$vc.txt")
        assertTrue("changelog empty", c.isNotEmpty())
        assertTrue("changelog max 500 chars, got ${c.length}", c.length <= 500)
    }

    @Test fun `icon is a real PNG at store size`() {
        val png = assertPng(File(root, "images/icon.png"))
        val (w, h) = pngDims(png)
        assertTrue("icon must be square", w == h)
        assertTrue("icon must be >= 512px, got $w", w >= 512)
    }

    @Test fun `phone screenshots exist and are real PNGs`() {
        val dir = File(root, "images/phoneScreenshots")
        val shots = dir.listFiles { f -> f.extension == "png" }
            ?.sortedBy { it.name } ?: emptyList()
        assertTrue("need at least 3 phone screenshots, got ${shots.size}", shots.size >= 3)
        for (s in shots) {
            val png = assertPng(s)
            assertTrue("screenshot too small to be real: ${s.name}", s.length() > 5_000)
            val (w, h) = pngDims(png)
            assertTrue("phone screenshots must be portrait: ${s.name} ${w}x$h", h > w)
        }
    }

    private fun assertPng(f: File): ByteArray {
        assertTrue("missing ${f.name}", f.isFile)
        val b = f.readBytes()
        assertTrue("${f.name} is not a PNG",
            b.size > 24 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() &&
                b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte())
        return b
    }

    private fun pngDims(b: ByteArray): Pair<Int, Int> {
        fun be(i: Int) = ((b[i].toInt() and 0xFF) shl 24) or
            ((b[i + 1].toInt() and 0xFF) shl 16) or
            ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
        assertEquals("IHDR", String(b, 12, 4, Charsets.US_ASCII))
        return be(16) to be(20)
    }
}
