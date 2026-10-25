package com.neozeon.kubedroid.data.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 03 Testing: CipherVault contract (AAD binding, IV uniqueness,
 * empty/large payloads) exercised against the fake. KeystoreCipherVault
 * itself is covered by the stage 6 instrumented checklist.
 */
abstract class CipherVaultContractTest(private val vaultFactory: () -> CipherVault) {

    @Test fun roundTrip() {
        val vault = vaultFactory()
        val plaintext = "apiVersion: v1\nkind: Config\nusers: []".toByteArray()
        val blob = vault.encrypt(plaintext, "conn-1")
        assertArrayEquals(plaintext, vault.decrypt(blob, "conn-1"))
    }

    @Test fun wrongAadFails() {
        val vault = vaultFactory()
        val plaintext = "top-secret-kubeconfig".toByteArray()
        val blob = vault.encrypt(plaintext, "conn-1")
        val outcome = runCatching { vault.decrypt(blob, "conn-2") }
        assertTrue(
            "decrypt with wrong aad must fail or produce garbage",
            outcome.isFailure || !outcome.getOrThrow().contentEquals(plaintext),
        )
    }

    @Test fun ivUniquenessAcrossEncrypts() {
        val vault = vaultFactory()
        val plaintext = ByteArray(64) { 'a'.code.toByte() }
        val a = vault.encrypt(plaintext, "conn")
        val b = vault.encrypt(plaintext, "conn")
        assertNotEquals("same plaintext must not produce identical blobs", a.toList(), b.toList())
    }

    @Test fun emptyPayload() {
        val vault = vaultFactory()
        val blob = vault.encrypt(ByteArray(0), "conn")
        assertArrayEquals(ByteArray(0), vault.decrypt(blob, "conn"))
    }

    @Test fun largePayload() {
        val vault = vaultFactory()
        val plaintext = ByteArray(1_048_576) { (it % 251).toByte() }
        val blob = vault.encrypt(plaintext, "conn")
        assertArrayEquals(plaintext, vault.decrypt(blob, "conn"))
    }

    @Test fun tamperedBlobDetectedOrGarbage() {
        val vault = vaultFactory()
        val plaintext = "sensitive".toByteArray()
        val blob = vault.encrypt(plaintext, "conn").toMutableList()
        blob[blob.lastIndex] = (blob.last() + 1).toByte()
        val outcome = runCatching { vault.decrypt(blob.toByteArray(), "conn") }
        assertTrue(
            "tampering must fail or produce garbage",
            outcome.isFailure || !outcome.getOrThrow().contentEquals(plaintext),
        )
    }

    @Test fun ciphertextIsNotPlaintext() {
        val vault = vaultFactory()
        val secret = "SUPER-SECRET-KUBECONFIG-MATERIAL-12345".toByteArray()
        val blob = vault.encrypt(secret, "conn")
        assertFalse(
            "plaintext must not appear verbatim in the blob",
            String(blob).contains("SUPER-SECRET"),
        )
    }
}

class FakeCipherVaultTest : CipherVaultContractTest({ FakeCipherVault() })
