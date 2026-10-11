package com.nwcring.app.lock

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal

enum class BiometricAvailability { READY, NONE_ENROLLED, UNAVAILABLE }

enum class AuthOutcome { SUCCESS, USE_PIN, CANCELLED, FAILED }

/** What the screens use to ask the phone for a fingerprint. The activity provides the real one. */
interface AuthGateway {
    fun biometrics(): BiometricAvailability

    fun request(reason: String, onOutcome: (AuthOutcome) -> Unit)
}

/**
 * Shows Android's own fingerprint prompt, with "Use PIN" as the only other way out. The
 * phone's PIN or pattern is never offered: NWC Ring's PIN is its own. The app never sees
 * the fingerprint; it is only told whether the phone accepted it, and the secure chip
 * then allows the fingerprint copy of the vault key to be used for a few seconds.
 */
class Authenticator(
    private val activity: Activity,
    private val onPromptClosedWithoutSuccess: () -> Unit = {},
) : AuthGateway {
    /** True while the phone's prompt is on top of the app. */
    var inProgress = false
        private set

    override fun biometrics(): BiometricAvailability {
        val manager = activity.getSystemService(BiometricManager::class.java)
            ?: return BiometricAvailability.UNAVAILABLE
        return when (manager.canAuthenticate(STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.READY
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NONE_ENROLLED
            else -> BiometricAvailability.UNAVAILABLE
        }
    }

    override fun request(reason: String, onOutcome: (AuthOutcome) -> Unit) {
        if (inProgress) return
        inProgress = true
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle("NWC Ring")
            .setSubtitle(reason)
            .setAllowedAuthenticators(STRONG)
            .setConfirmationRequired(false)
            .setNegativeButton("Use PIN", activity.mainExecutor) { _, _ ->
                inProgress = false
                onPromptClosedWithoutSuccess()
                onOutcome(AuthOutcome.USE_PIN)
            }
            .build()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                inProgress = false
                onOutcome(AuthOutcome.SUCCESS)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                inProgress = false
                onPromptClosedWithoutSuccess()
                val cancelled = errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                onOutcome(if (cancelled) AuthOutcome.CANCELLED else AuthOutcome.FAILED)
            }
        }
        prompt.authenticate(CancellationSignal(), activity.mainExecutor, callback)
    }

    companion object {
        private const val STRONG = BiometricManager.Authenticators.BIOMETRIC_STRONG
    }
}
