package com.neozeon.kubedroid.data.local

/**
 * Encrypts kubeconfig payloads at rest (spec 03 "Secure storage").
 * [aad] is the connection id so blobs cannot be swapped between rows.
 */
interface CipherVault {
    fun encrypt(plaintext: ByteArray, aad: String): ByteArray
    fun decrypt(blob: ByteArray, aad: String): ByteArray
}

/** Thrown when a blob cannot be authenticated/decrypted (wrong aad, corruption). */
class VaultDecryptException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Deterministic fake for JVM tests. Not secure (xor stream + tag) — it
 * exists so the [CipherVault] contract (AAD binding, IV uniqueness,
 * round trips) is testable without a Keystore (unavailable under
 * Robolectric). Never ship this.
 */
class FakeCipherVault : CipherVault {
    private val random = java.security.SecureRandom()

    override fun encrypt(plaintext: ByteArray, aad: String): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val tag = tag(aad, iv)
        val body = ByteArray(plaintext.size)
        for (i in plaintext.indices) {
            body[i] = (plaintext[i].toInt() xor keystream(iv, aad, i)).toByte()
        }
        return iv + tag + body
    }

    override fun decrypt(blob: ByteArray, aad: String): ByteArray {
        if (blob.size < 12 + 8) throw VaultDecryptException("blob truncated")
        val iv = blob.copyOfRange(0, 12)
        val tag = blob.copyOfRange(12, 20)
        if (!tag.contentEquals(tag(aad, iv))) {
            throw VaultDecryptException("aad mismatch")
        }
        val body = blob.copyOfRange(20, blob.size)
        return ByteArray(body.size).also { out ->
            for (i in body.indices) {
                out[i] = (body[i].toInt() xor keystream(iv, aad, i)).toByte()
            }
        }
    }

    private fun tag(aad: String, iv: ByteArray): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(iv)
        digest.update(aad.toByteArray(Charsets.UTF_8))
        return digest.digest().copyOfRange(0, 8)
    }

    private fun keystream(iv: ByteArray, aad: String, index: Int): Int {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(iv)
        digest.update(aad.toByteArray(Charsets.UTF_8))
        digest.update((index / 32).toByte())
        val b = digest.digest()[index % 32].toInt()
        return b and 0xFF
    }
}
