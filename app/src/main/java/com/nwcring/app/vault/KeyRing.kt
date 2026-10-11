package com.nwcring.app.vault

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

enum class KeyRingState { LOADING, NOT_SET_UP, LOCKED, UNLOCKED, FILE_UNREADABLE, FILE_FROM_NEWER_VERSION }

sealed interface SetUpResult {
    data object Done : SetUpResult

    data object BadPin : SetUpResult

    data object AlreadySetUp : SetUpResult

    data object Failed : SetUpResult
}

sealed interface PinResult {
    data object Accepted : PinResult

    /** Wrong PIN. [waitMs] is how long until the next try is allowed; 0 while tries are free. */
    data class Wrong(val failures: Int, val waitMs: Long) : PinResult

    /** Too many wrong PINs recently; try again in [remainingMs]. */
    data class Wait(val remainingMs: Long) : PinResult

    data object NotSetUp : PinResult

    /** The phone destroyed the device key, so the vault can never be opened again. */
    data object KeyLost : PinResult

    data object Failed : PinResult
}

sealed interface BiometricResult {
    data object Accepted : BiometricResult

    /** The chip wants a fingerprint first: show the prompt, then call again. */
    data object NeedsFingerprint : BiometricResult

    data object NotEnabled : BiometricResult

    /** The fingerprints on the phone changed, so this copy is gone. Use the PIN, then re-enable. */
    data object FingerprintsChanged : BiometricResult

    data object Failed : BiometricResult
}

/**
 * Holds the vault key in memory while the app is unlocked, and keeps it on disk in two
 * locked copies: one under the user's PIN (stretched with PBKDF2, then wrapped by a
 * hardware key that never leaves this phone) and, when switched on, one under a hardware
 * key that only works moments after a fingerprint. Wrong PINs are counted and slowed
 * down. The PIN itself is never stored, logged or compared as text: it either opens the
 * copy or it does not.
 */
class KeyRing(
    private val store: KeyRingStore,
    /** Hardware key, no fingerprint needed. Ties the PIN copy to this phone. */
    private val device: SecretCipher,
    /** Hardware key that needs a fingerprint moments before each use. */
    private val biometric: SecretCipher,
    private val random: SecureRandom = SecureRandom(),
    /** PBKDF2 rounds for a new PIN. Null means: measure this phone and pick a count that takes about [TARGET_STRETCH_MS]. */
    private val iterations: Int? = null,
    /** Wall clock, for the wait that must survive a restart. */
    private val wallMs: () -> Long = System::currentTimeMillis,
    /** Time since boot, which the user cannot move. */
    private val elapsedMs: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Receives plain facts for the log (outcomes), never a PIN or a key. */
    private val note: (event: String, facts: List<Pair<String, Any?>>) -> Unit = { _, _ -> },
) {
    private val mutex = Mutex()

    @Volatile
    private var key: ByteArray? = null
    private var file: KeyRingFile? = null
    private var waitUntilElapsed = 0L

    private val _state = MutableStateFlow(KeyRingState.LOADING)
    private val _biometricEnabled = MutableStateFlow(false)
    private val _biometricNeedsRepair = MutableStateFlow(false)
    private val _waitUntilElapsedMs = MutableStateFlow(0L)

    val state: StateFlow<KeyRingState> = _state
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled

    /** True after the phone's fingerprints changed: the PIN works, and fingerprint needs re-enabling. */
    val biometricNeedsRepair: StateFlow<Boolean> = _biometricNeedsRepair

    /** Time-since-boot before which no PIN is accepted, or 0. */
    val waitUntilElapsedMs: StateFlow<Long> = _waitUntilElapsedMs

    /** What the vault encrypts with. Works only while unlocked. */
    val cipher: SecretCipher = SessionCipher(key = { key }, random = random)

    fun deviceKeyStorage(): KeyStorage = device.keyStorage()

    suspend fun load() = withContext(io) {
        mutex.withLock {
            try {
                val loaded = store.load()
                file = loaded
                if (loaded == null) {
                    _state.value = KeyRingState.NOT_SET_UP
                    return@withLock
                }
                _biometricEnabled.value = loaded.biometricCopy != null
                val remaining = loaded.lockedUntilWallMs - wallMs()
                if (remaining > 0) setWait(remaining)
                _state.value = if (key == null) KeyRingState.LOCKED else KeyRingState.UNLOCKED
                note("key_ring_loaded", listOf("biometric" to _biometricEnabled.value, "failures" to loaded.failures))
            } catch (e: UnreadableVaultFileException) {
                _state.value = KeyRingState.FILE_UNREADABLE
            } catch (e: NewerVaultFileException) {
                _state.value = KeyRingState.FILE_FROM_NEWER_VERSION
            }
        }
    }

    /** Chooses the PIN, makes a fresh vault key, and leaves the app unlocked. */
    suspend fun setUp(pin: CharArray): SetUpResult = withContext(io) {
        mutex.withLock {
            try {
                if (file != null || _state.value == KeyRingState.FILE_UNREADABLE) return@withLock SetUpResult.AlreadySetUp
                if (!isValidPin(pin)) return@withLock SetUpResult.BadPin
                val newKey = ByteArray(KEY_BYTES).also(random::nextBytes)
                val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
                val rounds = iterations ?: calibrateIterations { sample -> measureStretchMs(sample, salt) }
                val pinCopy = sealPinCopy(newKey, pin, salt, rounds)
                // Prove the copy opens before trusting it with anything.
                val back = openPinCopy(pinCopy, pin, salt, rounds) ?: return@withLock SetUpResult.Failed
                if (!back.contentEquals(newKey)) return@withLock SetUpResult.Failed
                back.fill(0)
                val created = KeyRingFile(
                    salt = Base64.getEncoder().encodeToString(salt),
                    iterations = rounds,
                    pinCopy = pinCopy,
                )
                store.save(created)
                file = created
                key = newKey
                waitUntilElapsed = 0
                _waitUntilElapsedMs.value = 0
                _biometricEnabled.value = false
                _state.value = KeyRingState.UNLOCKED
                note("key_ring_set_up", listOf("device_key" to device.keyStorage(), "rounds" to rounds))
                SetUpResult.Done
            } catch (e: IOException) {
                SetUpResult.Failed
            } catch (e: GeneralSecurityException) {
                SetUpResult.Failed
            } finally {
                pin.fill('\u0000')
            }
        }
    }

    suspend fun unlockWithPin(pin: CharArray): PinResult = withContext(io) {
        mutex.withLock { checkPin(pin, unlock = true) }
    }

    /** Re-checks the PIN while unlocked, before a reveal or copy. Same counting and waiting. */
    suspend fun confirmPin(pin: CharArray): PinResult = withContext(io) {
        mutex.withLock { checkPin(pin, unlock = false) }
    }

    suspend fun unlockWithBiometric(): BiometricResult = withContext(io) {
        mutex.withLock { checkBiometric(unlock = true) }
    }

    suspend fun confirmBiometric(): BiometricResult = withContext(io) {
        mutex.withLock { checkBiometric(unlock = false) }
    }

    /**
     * Makes (or remakes) the fingerprint copy. Needs the app unlocked and a fingerprint
     * checked by the phone's prompt moments before, or it answers [BiometricResult.NeedsFingerprint].
     */
    suspend fun enableBiometric(): BiometricResult = withContext(io) {
        mutex.withLock {
            val current = file ?: return@withLock BiometricResult.NotEnabled
            val vaultKey = key ?: return@withLock BiometricResult.Failed
            try {
                // Start from a clean key, which also clears one the phone invalidated.
                biometric.destroyKey()
                val copy = biometric.seal(vaultKey, BIOMETRIC_CONTEXT)
                val back = biometric.open(copy, BIOMETRIC_CONTEXT)
                if (!back.contentEquals(vaultKey)) return@withLock BiometricResult.Failed
                back.fill(0)
                val updated = current.copy(biometricCopy = copy)
                store.save(updated)
                file = updated
                _biometricEnabled.value = true
                _biometricNeedsRepair.value = false
                note("biometric_enabled", listOf("key" to biometric.keyStorage()))
                BiometricResult.Accepted
            } catch (e: AuthRequiredException) {
                BiometricResult.NeedsFingerprint
            } catch (e: KeyLostException) {
                BiometricResult.Failed
            } catch (e: UnreadableSecretException) {
                BiometricResult.Failed
            } catch (e: GeneralSecurityException) {
                BiometricResult.Failed
            } catch (e: IOException) {
                BiometricResult.Failed
            }
        }
    }

    suspend fun disableBiometric(): Boolean = withContext(io) {
        mutex.withLock {
            val current = file ?: return@withLock false
            try {
                val updated = current.copy(biometricCopy = null)
                store.save(updated)
                file = updated
                biometric.destroyKey()
                _biometricEnabled.value = false
                _biometricNeedsRepair.value = false
                note("biometric_disabled", emptyList())
                true
            } catch (e: IOException) {
                false
            } catch (e: GeneralSecurityException) {
                false
            }
        }
    }

    /** Forgets the vault key. Nothing on disk changes. */
    fun lock() {
        key?.fill(0)
        key = null
        if (_state.value == KeyRingState.UNLOCKED) _state.value = KeyRingState.LOCKED
    }

    /** Deletes the key ring and both hardware keys. Every stored secret becomes unreadable. */
    suspend fun wipe() = withContext(io) {
        mutex.withLock {
            key?.fill(0)
            key = null
            store.delete()
            file = null
            try {
                device.destroyKey()
                biometric.destroyKey()
            } catch (e: GeneralSecurityException) {
                // The file is gone, which is what makes the vault unreadable; a leftover key is harmless.
            }
            waitUntilElapsed = 0
            _waitUntilElapsedMs.value = 0
            _biometricEnabled.value = false
            _biometricNeedsRepair.value = false
            _state.value = KeyRingState.NOT_SET_UP
            note("key_ring_wiped", emptyList())
        }
    }

    private fun checkPin(pin: CharArray, unlock: Boolean): PinResult {
        try {
            val current = file ?: return PinResult.NotSetUp
            val now = elapsedMs()
            if (waitUntilElapsed > now) return PinResult.Wait(waitUntilElapsed - now)
            val salt = try {
                Base64.getDecoder().decode(current.salt)
            } catch (e: IllegalArgumentException) {
                return PinResult.Failed
            }
            val opened = try {
                openPinCopy(current.pinCopy, pin, salt, current.iterations)
            } catch (e: KeyLostException) {
                note("pin_check_device_key_lost", emptyList())
                return PinResult.KeyLost
            } catch (e: UnreadableSecretException) {
                return PinResult.Failed
            } catch (e: GeneralSecurityException) {
                return PinResult.Failed
            }
            if (opened == null) return wrongPin(current)
            if (current.failures != 0 || current.lockedUntilWallMs != 0L) {
                persist(current.copy(failures = 0, lockedUntilWallMs = 0))
            }
            waitUntilElapsed = 0
            _waitUntilElapsedMs.value = 0
            if (unlock) {
                key?.fill(0)
                key = opened
                _state.value = KeyRingState.UNLOCKED
                note("unlocked", listOf("with" to "pin"))
            } else {
                val matches = opened.contentEquals(key)
                opened.fill(0)
                if (!matches) return PinResult.Failed
                note("confirmed", listOf("with" to "pin"))
            }
            return PinResult.Accepted
        } finally {
            pin.fill('\u0000')
        }
    }

    private fun wrongPin(current: KeyRingFile): PinResult {
        val failures = current.failures + 1
        val waitMs = waitAfter(failures)
        if (waitMs > 0) setWait(waitMs)
        persist(current.copy(failures = failures, lockedUntilWallMs = if (waitMs > 0) wallMs() + waitMs else 0))
        note("wrong_pin", listOf("failures" to failures, "wait_s" to waitMs / 1000))
        return PinResult.Wrong(failures, waitMs)
    }

    private fun checkBiometric(unlock: Boolean): BiometricResult {
        val current = file ?: return BiometricResult.NotEnabled
        val copy = current.biometricCopy ?: return BiometricResult.NotEnabled
        return try {
            val opened = biometric.open(copy, BIOMETRIC_CONTEXT)
            if (unlock) {
                key?.fill(0)
                key = opened
                _state.value = KeyRingState.UNLOCKED
                note("unlocked", listOf("with" to "fingerprint"))
            } else {
                val matches = opened.contentEquals(key)
                opened.fill(0)
                if (!matches) return BiometricResult.Failed
                note("confirmed", listOf("with" to "fingerprint"))
            }
            BiometricResult.Accepted
        } catch (e: AuthRequiredException) {
            BiometricResult.NeedsFingerprint
        } catch (e: KeyLostException) {
            _biometricNeedsRepair.value = true
            note("fingerprint_copy_lost", emptyList())
            BiometricResult.FingerprintsChanged
        } catch (e: UnreadableSecretException) {
            BiometricResult.Failed
        } catch (e: GeneralSecurityException) {
            BiometricResult.Failed
        }
    }

    private fun persist(updated: KeyRingFile) {
        try {
            store.save(updated)
            file = updated
        } catch (e: IOException) {
            // The in-memory count still applies for this run.
            file = updated
        }
    }

    private fun setWait(ms: Long) {
        waitUntilElapsed = elapsedMs() + ms
        _waitUntilElapsedMs.value = waitUntilElapsed
    }

    /** The vault key under the stretched PIN, then under the device key. */
    private fun sealPinCopy(vaultKey: ByteArray, pin: CharArray, salt: ByteArray, iterations: Int): SealedBlob {
        val stretched = stretch(pin, salt, iterations)
        try {
            val iv = ByteArray(SessionCipher.IV_BYTES).also(random::nextBytes)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(stretched, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(PIN_AAD)
            val inner = iv + cipher.doFinal(vaultKey)
            return device.seal(inner, PIN_CONTEXT)
        } finally {
            stretched.fill(0)
        }
    }

    /** Null means the PIN is wrong. Throws when the device key or the file is the problem. */
    private fun openPinCopy(copy: SealedBlob, pin: CharArray, salt: ByteArray, iterations: Int): ByteArray? {
        val inner = device.open(copy, PIN_CONTEXT)
        if (inner.size <= SessionCipher.IV_BYTES) throw UnreadableSecretException()
        val stretched = stretch(pin, salt, iterations)
        try {
            val iv = inner.copyOfRange(0, SessionCipher.IV_BYTES)
            val encrypted = inner.copyOfRange(SessionCipher.IV_BYTES, inner.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(stretched, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(PIN_AAD)
            return try {
                cipher.doFinal(encrypted)
            } catch (e: AEADBadTagException) {
                null
            }
        } finally {
            stretched.fill(0)
            inner.fill(0)
        }
    }

    private fun measureStretchMs(rounds: Int, salt: ByteArray): Long {
        val probe = CharArray(MIN_PIN) { '7' }
        val started = elapsedMs()
        stretch(probe, salt, rounds).fill(0)
        return elapsedMs() - started
    }

    private fun stretch(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin, salt, iterations, KEY_BYTES * 8)
        try {
            return SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val MIN_PIN = 6
        const val MAX_PIN = 16
        const val FREE_TRIES = 5
        const val FIRST_WAIT_MS = 30_000L
        const val MAX_WAIT_MS = 3_600_000L

        /** How long one PIN check should take on the phone it was set up on. */
        const val TARGET_STRETCH_MS = 400L
        const val MIN_ITERATIONS = 60_000
        const val MAX_ITERATIONS = 2_000_000
        private const val SAMPLE_ITERATIONS = 10_000
        private const val KEY_BYTES = 32
        private const val SALT_BYTES = 16
        private const val KDF = "PBKDF2WithHmacSHA256"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val PIN_CONTEXT = "pin-copy"
        private const val BIOMETRIC_CONTEXT = "biometric-copy"
        private val PIN_AAD = "nwcring/pin-copy/v1".toByteArray(Charsets.UTF_8)

        fun isValidPin(pin: CharArray): Boolean =
            pin.size in MIN_PIN..MAX_PIN && pin.all { it in '0'..'9' }

        /**
         * Picks a PBKDF2 round count that takes about [TARGET_STRETCH_MS] here, by timing a
         * sample. Never fewer than [MIN_ITERATIONS], so a slow or mis-measured phone still
         * gets real stretching; never more than [MAX_ITERATIONS].
         */
        fun calibrateIterations(measureMs: (rounds: Int) -> Long): Int {
            val sampleMs = measureMs(SAMPLE_ITERATIONS).coerceAtLeast(1)
            val wanted = SAMPLE_ITERATIONS.toLong() * TARGET_STRETCH_MS / sampleMs
            return wanted.coerceIn(MIN_ITERATIONS.toLong(), MAX_ITERATIONS.toLong()).toInt()
        }

        /** How long to wait after this many wrong PINs in a row. */
        fun waitAfter(failures: Int): Long {
            if (failures < FREE_TRIES) return 0
            val doublings = (failures - FREE_TRIES).coerceAtMost(20)
            return (FIRST_WAIT_MS shl doublings).coerceAtMost(MAX_WAIT_MS)
        }
    }
}
