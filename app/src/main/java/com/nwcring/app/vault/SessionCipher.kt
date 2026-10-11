package com.nwcring.app.vault

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts the stored connections under the vault key while it is in memory. The key is
 * handed in by [KeyRing] after a PIN or fingerprint and taken away again on lock; while it
 * is absent every call fails with [VaultLockedException]. AES-256-GCM from the platform's
 * own provider, with a fresh random nonce per encryption. Nothing cryptographic is
 * implemented here.
 */
class SessionCipher(
    private val key: () -> ByteArray?,
    private val random: SecureRandom = SecureRandom(),
) : SecretCipher {

    override fun seal(plain: ByteArray, context: String): SealedBlob {
        val keyBytes = key() ?: throw VaultLockedException()
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(associatedData(context))
        return SealedBlob(
            iv = Base64.getEncoder().encodeToString(iv),
            ct = Base64.getEncoder().encodeToString(cipher.doFinal(plain)),
        )
    }

    override fun open(blob: SealedBlob, context: String): ByteArray {
        val keyBytes = key() ?: throw VaultLockedException()
        val iv = decode(blob.iv)
        val encrypted = decode(blob.ct)
        if (iv.size != IV_BYTES) throw UnreadableSecretException()
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(associatedData(context))
            cipher.doFinal(encrypted)
        } catch (e: AEADBadTagException) {
            throw UnreadableSecretException()
        } catch (e: GeneralSecurityException) {
            throw UnreadableSecretException()
        }
    }

    override fun keyStorage(): KeyStorage = if (key() == null) KeyStorage.NOT_CREATED else KeyStorage.SOFTWARE

    override fun destroyKey() = Unit

    private fun decode(text: String): ByteArray = try {
        Base64.getDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
        throw UnreadableSecretException()
    }

    private fun associatedData(context: String): ByteArray = "nwcring/v2/$context".toByteArray(Charsets.UTF_8)

    companion object {
        const val IV_BYTES = 12
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
