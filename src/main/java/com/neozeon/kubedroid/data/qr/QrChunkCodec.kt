package com.neozeon.kubedroid.data.qr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.Base64

/**
 * Chunked QR codec (spec 03 "Import pipeline"): kubeconfigs exceed single-QR
 * capacity, so a JSON envelope is split across ordered QR codes shaped
 * `kubedroid:1:<index>/<total>:<base64(bytes)>`, scanned in sequence.
 * Producer side is `tools/qr-export` (dev plan stage 7).
 */
object QrChunkCodec {
    const val PREFIX = "kubedroid:1:"
    const val DEFAULT_MAX_CHARS = 1024

    private val json = Json { ignoreUnknownKeys = true }

    /** Split [jsonPayload] into QR payloads, 1-based indexes, ordered. */
    fun encode(jsonPayload: String, maxChars: Int = DEFAULT_MAX_CHARS): List<String> {
        require(maxChars >= 64) { "maxChars too small for any useful QR" }
        val bytes = jsonPayload.toByteArray(Charsets.UTF_8)
        // base64 expands 3 bytes -> 4 chars; keep each payload <= maxChars
        val rawChunkSize = maxOf(1, (maxChars - PREFIX.length - 8) / 4 * 3)
        val chunks = bytes.asList().chunked(rawChunkSize)
        val total = chunks.size
        val encoder = Base64.getEncoder()
        return chunks.mapIndexed { i, chunk ->
            val b64 = encoder.encodeToString(chunk.toByteArray())
            "$PREFIX${i + 1}/$total:$b64"
        }
    }

    /** Stateful ordered accumulator for the scanner UI. Not thread-safe. */
    class Decoder {
        sealed interface Outcome {
            /** Awaiting chunk [nextIndex] of [total] (or first chunk if total unknown yet). */
            data class Awaiting(val nextIndex: Int, val total: Int?) : Outcome
            data class Complete(val jsonPayload: String) : Outcome
            data class Failed(val reason: String) : Outcome
        }

        private val buffer = mutableListOf<Byte>()
        private var expectedIndex = 1
        private var total: Int? = null
        private var done = false

        fun accept(payload: String): Outcome {
            if (done) return Outcome.Failed("scan already complete")
            if (!payload.startsWith(PREFIX)) {
                return fail("not a kubedroid QR (expected prefix '$PREFIX')")
            }
            val rest = payload.substring(PREFIX.length)
            val sep = rest.indexOf(':')
            if (sep < 0) return fail("malformed chunk: missing payload separator")
            val idxSpec = rest.substring(0, sep)
            val slash = idxSpec.indexOf('/')
            if (slash < 0) return fail("malformed chunk: expected '<index>/<total>'")
            val index = idxSpec.substring(0, slash).toIntOrNull()
                ?: return fail("malformed chunk: bad index '${idxSpec.substring(0, slash)}'")
            val declaredTotal = idxSpec.substring(slash + 1).toIntOrNull()
                ?: return fail("malformed chunk: bad total")
            if (declaredTotal < 1) return fail("malformed chunk: total must be >= 1")
            if (total == null) total = declaredTotal else if (declaredTotal != total) {
                return fail("chunk total $declaredTotal disagrees with $total")
            }
            if (index != expectedIndex) {
                return fail("out of order: expected chunk $expectedIndex, got $index")
            }
            val bytes = try {
                Base64.getMimeDecoder().decode(rest.substring(sep + 1))
            } catch (e: IllegalArgumentException) {
                return fail("chunk $index has corrupt base64")
            }
            bytes.forEach { buffer += it }
            if (index == total) {
                val text = String(buffer.toByteArray(), Charsets.UTF_8)
                val element: JsonElement = try {
                    json.parseToJsonElement(text)
                } catch (e: Exception) {
                    return fail("reassembled payload is not valid JSON")
                }
                // sanity: envelope must be an object
                if (element !is kotlinx.serialization.json.JsonObject) {
                    return fail("reassembled payload is not a JSON object")
                }
                done = true
                return Outcome.Complete(text)
            }
            expectedIndex = index + 1
            return Outcome.Awaiting(expectedIndex, total)
        }

        private fun fail(reason: String): Outcome.Failed {
            // Spec: chunks must arrive in order; any failure requires restarting.
            return Outcome.Failed(reason)
        }
    }
}
