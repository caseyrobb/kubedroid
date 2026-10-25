package com.neozeon.kubedroid.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AndroidKeyStore AES-256-GCM vault (spec 03 "Secure storage").
 * Deliberately avoids the deprecated/frozen
 * `androidx.security:security-crypto` EncryptedSharedPreferences path.
 *
 * Blob layout: 12-byte IV prefix || GCM ciphertext+tag. The connection id
 * is fed to [javax.crypto.Cipher.updateAAD] so a blob can only be
 * decrypted with the aad it was bound to.
 *
 * Verified by the stage 2 instrumented checklist (Keystore is unavailable
 * under Robolectric); its logic contract is tested against FakeCipherVault.
 */
class KeystoreCipherVault(
    private val keyAlias: String = KEY_ALIAS,
) : CipherVault {

    override fun encrypt(plaintext: ByteArray, aad: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, obtainKey())
            updateAAD(aad.toByteArray(Charsets.UTF_8))
        }
        val iv = cipher.iv
        check(iv.size == IV_LENGTH) { "unexpected GCM IV length ${iv.size}" }
        return iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(blob: ByteArray, aad: String): ByteArray {
        if (blob.size <= IV_LENGTH) throw VaultDecryptException("blob truncated")
        val iv = blob.copyOfRange(0, IV_LENGTH)
        val body = blob.copyOfRange(IV_LENGTH, blob.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            cipher.doFinal(body)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw VaultDecryptException("aad mismatch or corrupt blob", e)
        } catch (e: GeneralSecurityException) {
            throw VaultDecryptException("decrypt failed", e)
        }
    }

    private fun obtainKey(): SecretKey {
        val keyStore = keyStore()
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            // Non-exportable is inherent for AndroidKeyStore-generated keys
            // (setNonExportable() needs API 31; minSdk is 26).
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        const val KEY_ALIAS = "kubedroid-master" // pinned in spec 03 Configuration
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        private const val TAG_BITS = 128
    }
}
