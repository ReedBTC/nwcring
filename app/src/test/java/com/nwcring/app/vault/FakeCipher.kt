package com.nwcring.app.vault

import java.util.Base64

/**
 * A stand-in for one of the phone's hardware keys, for tests that run off the phone. It is
 * NOT encryption (it only reverses the bytes); the real cipher is tested on a device.
 */
class FakeCipher : SecretCipher {
    var needsAuth = false
    var keyLost = false
    var corruptOnOpen = false
    var keyGeneration = 1
    var seals = 0
    var opens = 0

    override fun seal(plain: ByteArray, context: String): SealedBlob {
        if (keyLost) throw KeyLostException()
        if (needsAuth) throw AuthRequiredException()
        seals++
        return SealedBlob(
            iv = Base64.getEncoder().encodeToString("$keyGeneration/$context".toByteArray()),
            ct = Base64.getEncoder().encodeToString(plain.reversedArray()),
        )
    }

    override fun open(blob: SealedBlob, context: String): ByteArray {
        if (keyLost) throw KeyLostException()
        if (needsAuth) throw AuthRequiredException()
        opens++
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
