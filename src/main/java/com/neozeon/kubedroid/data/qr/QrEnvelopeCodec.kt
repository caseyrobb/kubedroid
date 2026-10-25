package com.neozeon.kubedroid.data.qr

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JSON envelope carried inside the chunked QR payload (spec 03 "Import
 * pipeline"). Producer is `tools/qr-export`; kept tiny and explicit.
 */
@Serializable
data class QrEnvelope(val version: Int, val kubeconfig: String)

object QrEnvelopeCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(kubeconfig: String): String =
        json.encodeToString(QrEnvelope(version = 1, kubeconfig = kubeconfig))

    /** @return the kubeconfig text or null when [payload] is not our envelope. */
    fun decodeKubeconfig(payload: String): String? =
        runCatching { json.decodeFromString<QrEnvelope>(payload) }
            .getOrNull()
            ?.kubeconfig
            ?.takeIf { it.isNotBlank() }
}
