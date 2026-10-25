package com.neozeon.kubedroid.qrexport

import com.neozeon.kubedroid.data.kubernetes.KubeconfigParser
import com.neozeon.kubedroid.data.kubernetes.ParseResult
import com.neozeon.kubedroid.data.qr.QrChunkCodec
import com.neozeon.kubedroid.data.qr.QrEnvelopeCodec
import java.io.File

/**
 * `kubedroid-qr <kubeconfig|-> [--chunks] [--png DIR] [--max-chars N]`
 * (spec 03 dev plan stage 7). Reads a kubeconfig, strips unsupported auth
 * blocks, and emits the chunked QR sequence the app scanner consumes.
 * Human-facing diagnostics go to stderr; stdout carries only chunk payloads
 * (--chunks) or terminal QR blocks, so the tool composes in pipelines.
 */
private const val USAGE =
    "usage: kubedroid-qr <kubeconfig-file|-> [--chunks] [--png DIR] [--max-chars N]\n" +
    "  - reads stdin when the file is '-'\n" +
    "  --chunks     print raw 'kubedroid:1:i/n:<b64>' payloads (scripting/CI)\n" +
    "  --png DIR    also write chunk-NN.png files\n" +
    "  --max-chars  max chars per QR payload (default ${QrChunkCodec.DEFAULT_MAX_CHARS})"

fun main(args: Array<String>) {
    // stdin is only drained for the '-' input — never when a file is given,
    // otherwise the CLI hangs on a TTY.
    val stdinLazy = lazy { System.`in`.readBytes().toString(Charsets.UTF_8) }
    kotlin.system.exitProcess(run(args) { stdinLazy.value })
}

/** @return process exit code. [stdin] supplier is injected for testability. */
internal fun run(args: Array<String>, stdin: () -> String = { "" }): Int {
    var input: String? = null
    var chunks = false
    var pngDir: String? = null
    var maxChars = QrChunkCodec.DEFAULT_MAX_CHARS
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--chunks" -> chunks = true
            "--png" -> { pngDir = args.getOrNull(++i) ?: return usage("--png needs DIR") }
            "--max-chars" -> {
                maxChars = args.getOrNull(++i)?.toIntOrNull()
                    ?: return usage("--max-chars needs a number")
            }
            "-h", "--help" -> { System.err.println(USAGE); return 0 }
            else -> if (a.startsWith("--")) return usage("unknown option '$a'")
                else if (input != null) return usage("more than one input given")
                else input = a
        }
        i++
    }
    val raw = when (input) {
        null -> { System.err.println(USAGE); return 2 }
        "-" -> stdin()
        else -> runCatching { File(input).readText() }
            .getOrElse { System.err.println("error: cannot read '$input': ${it.message}"); return 2 }
    }

    // 1. Strip exec/auth-provider (spec 03 human decision 2026-10-24).
    val stripped = try {
        KubeconfigStripper.strip(raw)
    } catch (e: IllegalArgumentException) {
        System.err.println("error: ${e.message}")
        return 2
    }
    stripped.removals.forEach { System.err.println("note: $it") }

    // 2. Refuse to emit QRs the app would reject: validate with the REAL
    //    app parser (shared source), not a lookalike.
    when (val parsed = KubeconfigParser().parse(stripped.yaml)) {
        is ParseResult.Invalid -> {
            System.err.println("error: stripped kubeconfig is not importable: " +
                parsed.error.userMessage())
            return 2
        }
        is ParseResult.Valid -> parsed.warnings.forEach { System.err.println("warning: $it") }
    }

    // 3. Envelope + chunk with the app codecs.
    val chunksOut = QrChunkCodec.encode(QrEnvelopeCodec.encode(stripped.yaml), maxChars)
    System.err.println("emitting ${chunksOut.size} QR chunk(s), scan in order 1/${chunksOut.size}..${chunksOut.size}/${chunksOut.size}")

    if (chunks) chunksOut.forEach(::println)

    pngDir?.let { dir ->
        chunksOut.forEachIndexed { idx, payload ->
            val file = File(dir, "chunk-%02d.png".format(idx + 1))
            QrRenderer.png(payload, file)
            System.err.println("wrote ${file.path}")
        }
    }

    if (!chunks && pngDir == null) {
        chunksOut.forEachIndexed { idx, payload ->
            System.err.println("=== chunk ${idx + 1}/${chunksOut.size} — hold steady, then next ===")
            println(QrRenderer.terminal(payload))
            println()
        }
    }
    return 0
}

private fun usage(msg: String): Int {
    System.err.println("error: $msg\n$USAGE")
    return 2
}
