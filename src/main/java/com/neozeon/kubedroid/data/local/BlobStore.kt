package com.neozeon.kubedroid.data.local

import java.io.File
import java.util.UUID

/**
 * Encrypted blob persistence: `filesDir/connections/<id>.enc`, atomic
 * write via temp+rename (spec 03 "Secure storage"). Plaintext kubeconfig
 * never touches disk; only ciphertext crosses this boundary.
 */
class BlobStore(
    private val dir: File,
    private val vault: CipherVault,
) {
    private fun blobFile(id: UUID) = File(dir, "$id.enc")

    fun write(id: UUID, plaintext: ByteArray) {
        dir.mkdirs()
        val blob = vault.encrypt(plaintext, id.toString())
        val target = blobFile(id)
        val tmp = File(dir, "$id.enc.tmp")
        tmp.writeBytes(blob)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw java.io.IOException("atomic rename failed for $id")
        }
    }

    /** @return decrypted bytes or null when no blob exists for [id]. */
    fun read(id: UUID): ByteArray? {
        val f = blobFile(id)
        if (!f.exists()) return null
        return vault.decrypt(f.readBytes(), id.toString())
    }

    fun delete(id: UUID) {
        blobFile(id).delete()
    }

    fun exists(id: UUID) = blobFile(id).exists()
}
