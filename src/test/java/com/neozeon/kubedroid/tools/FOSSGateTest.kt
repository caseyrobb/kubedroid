package com.neozeon.kubedroid.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Spec 11 stage 1 Testing: the FOSS gate (tools/fdroid/foss-gate.sh) must
 * pass on the real tree and must FAIL when a denied (non-FOSS) coordinate
 * or the CAMERA permission is reintroduced — a fixture test proving the
 * gate actually blocks regressions, per the spec's Testing section.
 *
 * Gradle unit-test workers run with the module directory as cwd, which is
 * the project root in this flat layout.
 */
class FOSSGateTest {

    private val gate = File("tools/fdroid/foss-gate.sh")

    private fun run(vararg args: String): Pair<Int, String> {
        val proc = ProcessBuilder("bash", gate.path, *args)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        return proc.waitFor() to out
    }

    @Test fun `gate passes on the real tree`() {
        assertTrue("gate script missing", gate.isFile)
        val (code, out) = run()
        assertEquals("gate must pass:\n$out", 0, code)
        assertTrue(out.contains("FOSS_GATE_PASS"))
    }

    @Test fun `gate fails on a denylisted dependency fixture`() {
        val toml = File.createTempFile("foss", ".toml").apply {
            writeText(
                """
                [libraries]
                mlkit-barcode-scanning = { group = "com.google.mlkit", name = "barcode-scanning", version = "17.3.0" }
                """.trimIndent(),
            )
            deleteOnExit()
        }
        val gradle = File.createTempFile("foss", ".kts").apply {
            writeText("// no coordinates here\n")
            deleteOnExit()
        }
        val (code, out) = run("--toml", toml.path, "--gradle", gradle.path)
        assertEquals("gate must fail on mlkit", 1, code)
        assertTrue(out.contains("FOSS_VIOLATION"))
        assertTrue(out.contains("com.google.mlkit"))
    }

    @Test fun `gate fails when CAMERA permission returns`() {
        val manifest = File.createTempFile("foss", ".xml").apply {
            writeText(
                """
                <manifest>
                    <uses-permission android:name="android.permission.CAMERA" />
                </manifest>
                """.trimIndent(),
            )
            deleteOnExit()
        }
        val (code, out) = run("--manifest", manifest.path)
        assertEquals("gate must fail on CAMERA", 1, code)
        assertTrue(out.contains("CAMERA"))
    }
}
