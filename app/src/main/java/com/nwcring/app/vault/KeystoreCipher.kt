package com.nwcring.app.vault

import android.os.Build
import androidx.annotation.RequiresApi
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/** When the secure hardware may use a key. */
sealed interface KeyAuth {
    /** Any time the phone is on. Used for the key that ties the vault to this phone. */
    data object None : KeyAuth

    /**
     * Only within [windowSeconds] of a fingerprint (or other strong biometric) checked by
     * the phone's own prompt. The phone's PIN is never accepted instead. Enrolling a new
     * fingerprint destroys the key.
     */
    data class Biometric(val windowSeconds: Int) : KeyAuth
}

/**
 * Encrypts with an AES-256-GCM key that is created inside the phone's secure hardware and
 * never leaves it. The hardware picks the random nonce for every encryption itself. No
 * cryptography is implemented here; this only asks the platform to do it.
 */
class KeystoreCipher(
    private val alias: String,
    private val auth: KeyAuth,
) : SecretCipher {

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }
    private val lock = Any()

    override fun seal(plain: ByteArray, context: String): SealedBlob = translatingErrors {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
        cipher.updateAAD(associatedData(context))
        val encrypted = cipher.doFinal(plain)
        SealedBlob(
            iv = Base64.getEncoder().encodeToString(cipher.iv),
            ct = Base64.getEncoder().encodeToString(encrypted),
        )
    }

    override fun open(blob: SealedBlob, context: String): ByteArray = translatingErrors {
        val key = existingKey() ?: throw KeyLostException()
        val iv = decode(blob.iv)
        val encrypted = decode(blob.ct)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(associatedData(context))
        cipher.doFinal(encrypted)
    }

    fun exists(): Boolean = synchronized(lock) { keyStore.containsAlias(alias) }

    override fun keyStorage(): KeyStorage {
        val key = try {
            existingKey()
        } catch (e: GeneralSecurityException) {
            null
        } ?: return KeyStorage.NOT_CREATED
        return try {
            val info = SecretKeyFactory.getInstance(key.algorithm, PROVIDER)
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyStorage.STRONGBOX
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyStorage.TRUSTED_ENVIRONMENT
                    KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeyStorage.SOFTWARE
                    else -> KeyStorage.UNKNOWN
                }
            } else {
                @Suppress("DEPRECATION")
                if (info.isInsideSecureHardware) KeyStorage.TRUSTED_ENVIRONMENT else KeyStorage.SOFTWARE
            }
        } catch (e: GeneralSecurityException) {
            KeyStorage.UNKNOWN
        }
    }

    override fun destroyKey() {
        synchronized(lock) {
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }

    private fun decode(text: String): ByteArray = try {
        Base64.getDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
        throw UnreadableSecretException()
    }

    private fun existingKey(): SecretKey? = synchronized(lock) {
        keyStore.getKey(alias, null) as? SecretKey
    }

    private fun createKey(): SecretKey = synchronized(lock) {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        try {
            generate(strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setIsStrongBoxBacked(strongBox)
        when (auth) {
            KeyAuth.None -> builder.setUserAuthenticationRequired(false)
            is KeyAuth.Biometric -> builder
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(auth.windowSeconds, KeyProperties.AUTH_BIOMETRIC_STRONG)
                // A changed set of fingerprints must not keep unlocking this copy of the vault
                // key. The PIN copy is unaffected, and the app makes a fresh copy after a PIN.
                .setInvalidatedByBiometricEnrollment(true)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun associatedData(context: String): ByteArray = "nwcring/$alias/$context".toByteArray(Charsets.UTF_8)

    /** Turns the platform's assorted errors into the outcomes the rest of the app handles. */
    private inline fun <T> translatingErrors(block: () -> T): T = try {
        block()
    } catch (e: UserNotAuthenticatedException) {
        throw AuthRequiredException()
    } catch (e: KeyPermanentlyInvalidatedException) {
        throw KeyLostException()
    } catch (e: AEADBadTagException) {
        throw UnreadableSecretException()
    } catch (e: GeneralSecurityException) {
        if (needsAuthentication(e)) throw AuthRequiredException() else throw e
    } catch (e: ProviderException) {
        if (needsAuthentication(e)) throw AuthRequiredException() else throw e
    }

    /** Some secure chips report "authenticate first" only when the operation finishes. */
    private fun needsAuthentication(error: Throwable): Boolean {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < 6) {
            if (cause is UserNotAuthenticatedException) return true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && saysAuthenticationRequired(cause)) return true
            // Before Android 13 the platform's key store error is not a public type, so it
            // can only be recognised by name and message.
            if (cause.javaClass.name == "android.security.KeyStoreException" &&
                cause.message?.contains("not authenticated", ignoreCase = true) == true
            ) {
                return true
            }
            cause = cause.cause
            depth++
        }
        return false
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun saysAuthenticationRequired(cause: Throwable): Boolean =
        cause is android.security.KeyStoreException &&
            cause.numericErrorCode == android.security.KeyStoreException.ERROR_USER_AUTHENTICATION_REQUIRED

    companion object {
        /** Ties the PIN copy of the vault key to this phone. Usable without a fingerprint. */
        const val DEVICE_ALIAS = "nwcring.device.v2"

        /** Locks the fingerprint copy of the vault key. */
        const val BIOMETRIC_ALIAS = "nwcring.biometric.v2"

        /** The Milestone 1 key. Its presence means a test install from before the PIN. */
        const val OLD_ALIAS = "nwcring.vault.v1"

        /** How long after a fingerprint the chip will still open the fingerprint copy. */
        const val BIOMETRIC_WINDOW_SECONDS = 10

        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
