package com.nwcring.app.vault

import android.app.KeyguardManager
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/**
 * Runs on a phone or emulator, against the real secure key store, using a throwaway key
 * (never the app's own). The text encrypted here is made up.
 *
 * - [refusesToUseTheKeyWithoutARecentUnlock] needs nothing: it proves the hardware says no.
 * - [encryptsAndDecryptsThenRefusesOnceTheWindowCloses] needs the phone to have been
 *   unlocked with its PIN or fingerprint a few seconds earlier, and is skipped otherwise
 *   (pass `-e unlockedJustNow true`).
 */
@RunWith(AndroidJUnit4::class)
class KeystoreCipherDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val alias = "nwcring.test.${System.nanoTime()}"
    private val made = mutableListOf<KeystoreCipher>()
    private val text = "nostr+walletconnect://made-up-for-a-test?relay=wss://relay.example.com&secret=not-a-real-one"
        .toByteArray()

    private fun cipher(windowSeconds: Int) = KeystoreCipher(
        isDeviceSecure = { keyguard.isDeviceSecure },
        alias = alias,
        authWindowSeconds = windowSeconds,
    ).also { made += it }

    private fun report(key: String, value: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString(key, value) })
    }

    @After fun removeTheThrowawayKey() {
        made.forEach { it.destroyKey() }
    }

    @Test fun refusesToUseTheKeyWithoutARecentUnlock() {
        assumeTrue("the phone needs a screen lock", keyguard.isDeviceSecure)
        val cipher = cipher(windowSeconds = 5)
        // Let any earlier unlock grow older than the key's five-second window.
        Thread.sleep(7_000)
        assertThrows(AuthRequiredException::class.java) { cipher.seal(text, "entry-1") }
        // The key itself was created, inside the secure hardware, without any unlock.
        val storage = cipher.keyStorage()
        report("nwcring.keyStorage", storage.name)
        assertNotEquals(KeyStorage.NOT_CREATED, storage)
    }

    @Test fun encryptsAndDecryptsThenRefusesOnceTheWindowCloses() {
        assumeTrue("the phone needs a screen lock", keyguard.isDeviceSecure)
        assumeTrue(
            "needs an unlock moments before the test",
            InstrumentationRegistry.getArguments().getString("unlockedJustNow") == "true",
        )
        val cipher = cipher(windowSeconds = 25)

        val sealed = cipher.seal(text, "entry-1")
        assertArrayEquals(text, cipher.open(sealed, "entry-1"))
        report("nwcring.keyStorage", cipher.keyStorage().name)

        // The stored form does not contain the text.
        val stored = Base64.getDecoder().decode(sealed.ct)
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("walletconnect"))

        // Encrypting the same text twice gives different results (a fresh nonce each time).
        val again = cipher.seal(text, "entry-1")
        assertNotEquals(sealed.iv, again.iv)
        assertNotEquals(sealed.ct, again.ct)

        // A value sealed for one entry cannot be opened as another.
        assertThrows(UnreadableSecretException::class.java) { cipher.open(sealed, "entry-2") }

        // A single changed bit is detected.
        stored[stored.size / 2] = (stored[stored.size / 2].toInt() xor 1).toByte()
        val tampered = SealedBlob(sealed.iv, Base64.getEncoder().encodeToString(stored))
        assertThrows(UnreadableSecretException::class.java) { cipher.open(tampered, "entry-1") }

        // Garbage where a stored value should be is rejected, not crashed on.
        assertThrows(UnreadableSecretException::class.java) { cipher.open(SealedBlob("***", "***"), "entry-1") }

        // Once the window has closed, the hardware refuses both directions.
        Thread.sleep(27_000)
        assertThrows(AuthRequiredException::class.java) { cipher.open(sealed, "entry-1") }
        assertThrows(AuthRequiredException::class.java) { cipher.seal(text, "entry-1") }
    }
}
