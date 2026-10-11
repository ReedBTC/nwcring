package com.nwcring.app.vault

/** The hardware wants a fresh fingerprint before it will use the key. */
class AuthRequiredException : Exception()

/** The key was destroyed (for example a fingerprint was added or removed). Its copy is gone. */
class KeyLostException : Exception()

/** The stored value could not be decrypted with the current key. */
class UnreadableSecretException : Exception()

/** The vault key is not in memory: the app is locked. */
class VaultLockedException : Exception()

enum class KeyStorage { NOT_CREATED, STRONGBOX, TRUSTED_ENVIRONMENT, SOFTWARE, UNKNOWN }

/** Encrypts and decrypts. The hardware-backed ones wrap the vault key; the session one holds it. */
interface SecretCipher {
    /** [context] ties the result to one use, so a blob cannot be swapped into another. */
    fun seal(plain: ByteArray, context: String): SealedBlob

    fun open(blob: SealedBlob, context: String): ByteArray

    fun keyStorage(): KeyStorage

    /** Deletes the key. Everything sealed with it becomes unreadable for good. */
    fun destroyKey()
}
