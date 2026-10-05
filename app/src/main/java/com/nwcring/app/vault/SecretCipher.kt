package com.nwcring.app.vault

/** The hardware wants a fresh fingerprint / face / PIN before it will use the key. */
class AuthRequiredException : Exception()

/** The key was destroyed (for example the screen lock was removed). Old secrets are gone. */
class KeyLostException : Exception()

/** The phone has no secure screen lock, so a protected key cannot be created. */
class NoScreenLockException : Exception()

/** The stored value could not be decrypted with the current key. */
class UnreadableSecretException : Exception()

enum class KeyStorage { NOT_CREATED, STRONGBOX, TRUSTED_ENVIRONMENT, SOFTWARE, UNKNOWN }

/** Encrypts and decrypts secrets. The real one is backed by the phone's secure hardware. */
interface SecretCipher {
    /** [context] ties the result to one entry, so a blob cannot be swapped onto another. */
    fun seal(plain: ByteArray, context: String): SealedBlob

    fun open(blob: SealedBlob, context: String): ByteArray

    fun keyStorage(): KeyStorage

    /** Deletes the key. Everything sealed with it becomes unreadable for good. */
    fun destroyKey()
}
