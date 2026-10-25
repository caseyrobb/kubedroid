package com.neozeon.kubedroid.data.local

import java.security.MessageDigest

/** Hex SHA-256 of the raw kubeconfig bytes; dedup key in [ClusterConnection]. */
object PayloadHash {
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
