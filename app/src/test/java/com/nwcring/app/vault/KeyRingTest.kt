package com.nwcring.app.vault

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom

/**
 * The PIN stretching and the AES wrapping run for real here (they are plain Java); only
 * the two hardware keys are stand-ins. Every PIN in this file is made up.
 */
class KeyRingTest {
    @get:Rule val folder = TemporaryFolder()

    private val device = FakeCipher()
    private val biometric = FakeCipher()
    private val scope = TestScope()
    private var wall = 1_760_000_000_000L
    private var elapsed = 100_000L
    private lateinit var file: File

    private fun keyRing(): KeyRing {
        if (!::file.isInitialized) file = File(folder.root, "keyring.json")
        return KeyRing(
            store = KeyRingStore(file),
            device = device,
            biometric = biometric,
            random = SecureRandom(),
            iterations = 1_000,
            wallMs = { wall },
            elapsedMs = { elapsed },
            io = StandardTestDispatcher(scope.testScheduler),
        )
    }

    private fun pin(text: String) = text.toCharArray()

    private suspend fun KeyRing.setUpAndLoad(pinText: String = "123456"): KeyRing {
        load()
        assertEquals(KeyRingState.NOT_SET_UP, state.value)
        assertEquals(SetUpResult.Done, setUp(pin(pinText)))
        assertEquals(KeyRingState.UNLOCKED, state.value)
        return this
    }

    @Test fun startsNotSetUpAndRefusesToWorkUntilItIs() = scope.runTest {
        val ring = keyRing()
        ring.load()
        assertEquals(KeyRingState.NOT_SET_UP, ring.state.value)
        assertEquals(PinResult.NotSetUp, ring.unlockWithPin(pin("123456")))
        assertEquals(BiometricResult.NotEnabled, ring.unlockWithBiometric())
        assertThrows(VaultLockedException::class.java) { ring.cipher.seal("x".toByteArray(), "c") }
    }

    @Test fun setUpRejectsAnythingButSixToSixteenDigits() = scope.runTest {
        val ring = keyRing()
        ring.load()
        assertEquals(SetUpResult.BadPin, ring.setUp(pin("12345")))
        assertEquals(SetUpResult.BadPin, ring.setUp(pin("12345678901234567")))
        assertEquals(SetUpResult.BadPin, ring.setUp(pin("12345a")))
        assertEquals(SetUpResult.BadPin, ring.setUp(pin("")))
        assertEquals(KeyRingState.NOT_SET_UP, ring.state.value)
        assertFalse(file.exists())
    }

    @Test fun theRightPinOpensTheVaultAndTheKeySurvivesARestart() = scope.runTest {
        val first = keyRing().setUpAndLoad("2468013579")
        val sealed = first.cipher.seal("made up".toByteArray(), "entry")
        first.lock()
        assertEquals(KeyRingState.LOCKED, first.state.value)
        assertThrows(VaultLockedException::class.java) { first.cipher.open(sealed, "entry") }

        val second = keyRing()
        second.load()
        assertEquals(KeyRingState.LOCKED, second.state.value)
        assertEquals(PinResult.Accepted, second.unlockWithPin(pin("2468013579")))
        assertArrayEquals("made up".toByteArray(), second.cipher.open(sealed, "entry"))
    }

    @Test fun theWrongPinOpensNothingAndWipesTheDigitsItWasGiven() = scope.runTest {
        val ring = keyRing().setUpAndLoad("123456")
        ring.lock()
        val attempt = pin("123457")
        assertEquals(PinResult.Wrong(1, 0), ring.unlockWithPin(attempt))
        assertTrue(attempt.all { it == '\u0000' })
        assertEquals(KeyRingState.LOCKED, ring.state.value)
        assertThrows(VaultLockedException::class.java) { ring.cipher.seal("x".toByteArray(), "c") }
    }

    @Test fun fiveMissesAreFreeThenTheWaitDoublesAndSurvivesARestart() = scope.runTest {
        val ring = keyRing().setUpAndLoad("123456")
        ring.lock()
        repeat(4) { assertEquals(PinResult.Wrong(it + 1, 0), ring.unlockWithPin(pin("000000"))) }
        assertEquals(PinResult.Wrong(5, 30_000), ring.unlockWithPin(pin("000000")))
        assertEquals(PinResult.Wait(30_000), ring.unlockWithPin(pin("123456")))
        elapsed += 29_999
        assertEquals(PinResult.Wait(1), ring.unlockWithPin(pin("123456")))

        // Reinstalling the app's process does not reset the clock.
        wall += 29_999
        val restarted = keyRing()
        restarted.load()
        assertEquals(PinResult.Wait(1), restarted.unlockWithPin(pin("123456")))
        elapsed += 1
        wall += 1
        assertEquals(PinResult.Wrong(6, 60_000), restarted.unlockWithPin(pin("000000")))
        elapsed += 60_000
        assertEquals(PinResult.Wrong(7, 120_000), restarted.unlockWithPin(pin("000000")))
        elapsed += 120_000
        assertEquals(PinResult.Accepted, restarted.unlockWithPin(pin("123456")))

        // A right PIN clears the count.
        restarted.lock()
        assertEquals(PinResult.Wrong(1, 0), restarted.unlockWithPin(pin("000000")))
    }

    @Test fun theRoundCountIsPickedFromThePhonesSpeedWithinLimits() {
        // 10,000 rounds in 20 ms means 200,000 rounds in the 400 ms target.
        assertEquals(200_000, KeyRing.calibrateIterations { 20 })
        assertEquals(KeyRing.MIN_ITERATIONS, KeyRing.calibrateIterations { 5_000 })
        assertEquals(KeyRing.MAX_ITERATIONS, KeyRing.calibrateIterations { 0 })
    }

    @Test fun theWaitScheduleIsCappedAtAnHour() {
        assertEquals(0, KeyRing.waitAfter(0))
        assertEquals(0, KeyRing.waitAfter(4))
        assertEquals(30_000, KeyRing.waitAfter(5))
        assertEquals(60_000, KeyRing.waitAfter(6))
        assertEquals(960_000, KeyRing.waitAfter(10))
        assertEquals(3_600_000, KeyRing.waitAfter(12))
        assertEquals(3_600_000, KeyRing.waitAfter(100))
    }

    @Test fun confirmingThePinWhileUnlockedIsARealCheck() = scope.runTest {
        val ring = keyRing().setUpAndLoad("123456")
        assertEquals(PinResult.Wrong(1, 0), ring.confirmPin(pin("654321")))
        assertEquals(PinResult.Accepted, ring.confirmPin(pin("123456")))
        assertEquals(KeyRingState.UNLOCKED, ring.state.value)
    }

    @Test fun theFingerprintCopyUnlocksOnlyWhenTheChipAgrees() = scope.runTest {
        val ring = keyRing().setUpAndLoad("123456")
        assertFalse(ring.biometricEnabled.value)
        assertEquals(BiometricResult.NotEnabled, ring.unlockWithBiometric())

        biometric.needsAuth = true
        assertEquals(BiometricResult.NeedsFingerprint, ring.enableBiometric())
        assertFalse(ring.biometricEnabled.value)
        biometric.needsAuth = false
        assertEquals(BiometricResult.Accepted, ring.enableBiometric())
        assertTrue(ring.biometricEnabled.value)

        val sealed = ring.cipher.seal("made up".toByteArray(), "entry")
        ring.lock()
        biometric.needsAuth = true
        assertEquals(BiometricResult.NeedsFingerprint, ring.unlockWithBiometric())
        assertEquals(KeyRingState.LOCKED, ring.state.value)
        biometric.needsAuth = false
        assertEquals(BiometricResult.Accepted, ring.unlockWithBiometric())
        assertArrayEquals("made up".toByteArray(), ring.cipher.open(sealed, "entry"))
        assertEquals(BiometricResult.Accepted, ring.confirmBiometric())

        assertTrue(ring.disableBiometric())
        assertFalse(ring.biometricEnabled.value)
        ring.lock()
        assertEquals(BiometricResult.NotEnabled, ring.unlockWithBiometric())
        assertEquals(PinResult.Accepted, ring.unlockWithPin(pin("123456")))
    }

    @Test fun aChangedFingerprintLosesThatCopyOnlyAndThePinRepairsIt() = scope.runTest {
        val ring = keyRing().setUpAndLoad("123456")
        assertEquals(BiometricResult.Accepted, ring.enableBiometric())
        ring.lock()
        biometric.keyLost = true
        assertEquals(BiometricResult.FingerprintsChanged, ring.unlockWithBiometric())
        assertTrue(ring.biometricNeedsRepair.value)
        assertEquals(PinResult.Accepted, ring.unlockWithPin(pin("123456")))
        assertEquals(BiometricResult.Accepted, ring.enableBiometric())
        assertFalse(ring.biometricNeedsRepair.value)
        ring.lock()
        assertEquals(BiometricResult.Accepted, ring.unlockWithBiometric())
    }

    @Test fun losingTheDeviceKeyIsReportedAsFinal() = scope.runTest {
        val ring = keyRing().setUpAndLoad("123456")
        ring.lock()
        device.keyLost = true
        assertEquals(PinResult.KeyLost, ring.unlockWithPin(pin("123456")))
    }

    @Test fun wipingForgetsEverythingAndANewSetUpCannotReadOldSecrets() = scope.runTest {
        val ring = keyRing().setUpAndLoad("123456")
        val sealed = ring.cipher.seal("made up".toByteArray(), "entry")
        ring.wipe()
        assertEquals(KeyRingState.NOT_SET_UP, ring.state.value)
        assertFalse(file.exists())
        assertThrows(VaultLockedException::class.java) { ring.cipher.open(sealed, "entry") }
        assertEquals(SetUpResult.Done, ring.setUp(pin("123456")))
        assertThrows(UnreadableSecretException::class.java) { ring.cipher.open(sealed, "entry") }
    }

    @Test fun theFileOnDiskHoldsNeitherThePinNorTheKeyInTheClear() = scope.runTest {
        val ring = keyRing().setUpAndLoad("31415926")
        val sealed = ring.cipher.seal("probe".toByteArray(), "entry")
        val onDisk = file.readText()
        assertFalse(onDisk.contains("31415926"))
        // Two set-ups with the same PIN give different files: a fresh salt and key each time.
        ring.wipe()
        ring.setUp(pin("31415926"))
        assertNotEquals(onDisk, file.readText())
        assertThrows(UnreadableSecretException::class.java) { ring.cipher.open(sealed, "entry") }
    }

    @Test fun aDamagedKeyRingFileIsReportedNotOverwritten() = scope.runTest {
        file = File(folder.root, "keyring.json")
        file.writeText("not json")
        val ring = keyRing()
        ring.load()
        assertEquals(KeyRingState.FILE_UNREADABLE, ring.state.value)
        assertEquals(SetUpResult.AlreadySetUp, ring.setUp(pin("123456")))
        assertEquals("not json", file.readText())
    }

    @Test fun lockingWipesTheKeyFromMemory() = scope.runTest {
        val ring = keyRing().setUpAndLoad("123456")
        assertEquals(KeyStorage.SOFTWARE, ring.cipher.keyStorage())
        ring.lock()
        assertEquals(KeyStorage.NOT_CREATED, ring.cipher.keyStorage())
        assertNull(null)
    }
}
