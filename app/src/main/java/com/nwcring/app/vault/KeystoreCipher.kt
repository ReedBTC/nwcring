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

/**
 * Encrypts with an AES-256-GCM key that is created inside the phone's secure hardware and
 * never leaves it. The hardware only uses the key within [authWindowSeconds] of the user
 * authenticating with fingerprint, face or the phone's PIN, and it picks the random nonce
 * for every encryption itself. No cryptography is implemented here; this only asks the
 * platform to do it.
 */
class KeystoreCipher(
    private val isDeviceSecure: () -> Boolean,
    private val alias: String = DEFAULT_ALIAS,
    private val authWindowSeconds: Int = AUTH_WINDOW_SECONDS,
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
        val iv = try {
            Base64.getDecoder().decode(blob.iv)
        } catch (e: IllegalArgumentException) {
            throw UnreadableSecretException()
        }
        val encrypted = try {
            Base64.getDecoder().decode(blob.ct)
        } catch (e: IllegalArgumentException) {
            throw UnreadableSecretException()
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(associatedData(context))
        cipher.doFinal(encrypted)
    }

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

    private fun existingKey(): SecretKey? = synchronized(lock) {
        keyStore.getKey(alias, null) as? SecretKey
    }

    private fun createKey(): SecretKey = synchronized(lock) {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!isDeviceSecure()) throw NoScreenLockException()
        try {
            generate(strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(
                authWindowSeconds,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
            // Adding a fingerprint must not destroy the vault. Removing the screen lock still does.
            .setInvalidatedByBiometricEnrollment(false)
            .setIsStrongBoxBacked(strongBox)
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(spec)
        return generator.generateKey()
    }

    private fun associatedData(context: String): ByteArray = "nwcring/v1/$context".toByteArray(Charsets.UTF_8)

    /** Turns the platform's assorted errors into the four outcomes the rest of the app handles. */
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
        const val DEFAULT_ALIAS = "nwcring.vault.v1"
        const val AUTH_WINDOW_SECONDS = 30
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
