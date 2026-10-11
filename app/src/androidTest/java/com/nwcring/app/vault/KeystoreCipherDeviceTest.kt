package com.nwcring.app.vault

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64

/**
 * Runs on a phone or emulator, against the real secure key store, using throwaway keys
 * (never the app's own). The text encrypted here is made up.
 *
 * - [theDeviceKeyWorksWithoutAnyUnlock]: the key that ties the vault to the phone.
 * - [theWholeKeyRingWorksOnThisPhone]: PIN set-up and unlock with real hardware and real
 *   PIN stretching; reports how long the stretching takes.
 * - [theFingerprintKeyRefusesWithoutAFingerprint]: needs a fingerprint enrolled, skipped
 *   otherwise (pass `-e fingerprintEnrolled true`).
 */
@RunWith(AndroidJUnit4::class)
class KeystoreCipherDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val stamp = System.nanoTime()
    private val made = mutableListOf<KeystoreCipher>()
    private val text = "nostr+walletconnect://made-up-for-a-test?relay=wss://relay.example.com&secret=not-a-real-one"
        .toByteArray()

    private fun cipher(name: String, auth: KeyAuth) = KeystoreCipher("nwcring.test.$name.$stamp", auth).also { made += it }

    private fun report(key: String, value: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString(key, value) })
    }

    @After fun removeTheThrowawayKeys() {
        made.forEach { it.destroyKey() }
        File(context.noBackupFilesDir, "keyring-test-$stamp.json").delete()
    }

    @Test fun theDeviceKeyWorksWithoutAnyUnlock() {
        val cipher = cipher("device", KeyAuth.None)
        val sealed = cipher.seal(text, "entry-1")
        assertArrayEquals(text, cipher.open(sealed, "entry-1"))
        val storage = cipher.keyStorage()
        report("nwcring.deviceKeyStorage", storage.name)
        assertNotEquals(KeyStorage.NOT_CREATED, storage)

        // The stored form does not contain the text.
        val stored = Base64.getDecoder().decode(sealed.ct)
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("walletconnect"))

        // Encrypting the same text twice gives different results (a fresh nonce each time).
        val again = cipher.seal(text, "entry-1")
        assertNotEquals(sealed.iv, again.iv)
        assertNotEquals(sealed.ct, again.ct)

        // A value sealed for one use cannot be opened as another.
        assertThrows(UnreadableSecretException::class.java) { cipher.open(sealed, "entry-2") }

        // A single changed bit is detected.
        stored[stored.size / 2] = (stored[stored.size / 2].toInt() xor 1).toByte()
        val tampered = SealedBlob(sealed.iv, Base64.getEncoder().encodeToString(stored))
        assertThrows(UnreadableSecretException::class.java) { cipher.open(tampered, "entry-1") }

        // Garbage where a stored value should be is rejected, not crashed on.
        assertThrows(UnreadableSecretException::class.java) { cipher.open(SealedBlob("***", "***"), "entry-1") }

        // Destroying the key makes everything sealed with it unreadable.
        cipher.destroyKey()
        assertThrows(KeyLostException::class.java) { cipher.open(sealed, "entry-1") }
    }

    @Test fun theWholeKeyRingWorksOnThisPhone() = kotlinx.coroutines.runBlocking {
        val file = File(context.noBackupFilesDir, "keyring-test-$stamp.json")
        val ring = KeyRing(
            store = KeyRingStore(file),
            device = cipher("ringdevice", KeyAuth.None),
            biometric = cipher("ringbio", KeyAuth.Biometric(5)),
            elapsedMs = { SystemClock.elapsedRealtime() },
        )
        ring.load()
        assertEquals(KeyRingState.NOT_SET_UP, ring.state.value)
        val started = SystemClock.elapsedRealtime()
        assertEquals(SetUpResult.Done, ring.setUp("135790".toCharArray()))
        val setUpMs = SystemClock.elapsedRealtime() - started
        report("nwcring.setUpMs", setUpMs.toString())
        report("nwcring.rounds", Regex("\"iterations\":(\\d+)").find(file.readText())?.groupValues?.get(1) ?: "?")

        val sealed = ring.cipher.seal(text, "entry")
        ring.lock()
        val unlockStart = SystemClock.elapsedRealtime()
        assertEquals(PinResult.Wrong(1, 0), ring.unlockWithPin("135791".toCharArray()))
        assertEquals(PinResult.Accepted, ring.unlockWithPin("135790".toCharArray()))
        report("nwcring.twoPinChecksMs", (SystemClock.elapsedRealtime() - unlockStart).toString())
        assertArrayEquals(text, ring.cipher.open(sealed, "entry"))
        assertTrue(file.readText().let { !it.contains("135790") && !it.contains("walletconnect") })
        ring.wipe()
        assertFalse(file.exists())
    }

    @Test fun theFingerprintKeyRefusesWithoutAFingerprint() {
        assumeTrue(
            "needs a fingerprint enrolled on this device",
            InstrumentationRegistry.getArguments().getString("fingerprintEnrolled") == "true",
        )
        val cipher = cipher("bio", KeyAuth.Biometric(5))
        // Nobody has touched the sensor: the chip must say no, in both directions.
        assertThrows(AuthRequiredException::class.java) { cipher.seal(text, "entry-1") }
        report("nwcring.biometricKeyStorage", cipher.keyStorage().name)
    }
}
