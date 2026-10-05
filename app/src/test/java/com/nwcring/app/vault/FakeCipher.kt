package com.nwcring.app.vault

import java.util.Base64

/**
 * A stand-in for the phone's secure hardware, for tests that run off the phone. It is NOT
 * encryption (it only reverses the bytes); the real cipher is tested on a device.
 */
class FakeCipher : SecretCipher {
    var authenticated = true
    var keyLost = false
    var hasScreenLock = true
    var corruptOnOpen = false
    var keyGeneration = 1
    var seals = 0

    override fun seal(plain: ByteArray, context: String): SealedBlob {
        if (!hasScreenLock) throw NoScreenLockException()
        if (keyLost) throw KeyLostException()
        if (!authenticated) throw AuthRequiredException()
        seals++
        return SealedBlob(
            iv = Base64.getEncoder().encodeToString("$keyGeneration/$context".toByteArray()),
            ct = Base64.getEncoder().encodeToString(plain.reversedArray()),
        )
    }

    override fun open(blob: SealedBlob, context: String): ByteArray {
        if (keyLost) throw KeyLostException()
        if (!authenticated) throw AuthRequiredException()
        if (String(Base64.getDecoder().decode(blob.iv)) != "$keyGeneration/$context") throw UnreadableSecretException()
        val plain = Base64.getDecoder().decode(blob.ct).reversedArray()
        return if (corruptOnOpen) plain.copyOf(plain.size - 1) else plain
    }

    override fun keyStorage(): KeyStorage = KeyStorage.SOFTWARE

    override fun destroyKey() {
        keyLost = false
        keyGeneration++
    }
}
