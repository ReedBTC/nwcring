package com.nwcring.app.lock

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal

enum class AuthAvailability { READY, NO_SCREEN_LOCK, UNAVAILABLE }

enum class AuthOutcome { SUCCESS, CANCELLED, FAILED }

/** What the screens use to ask "is it really you?". The activity provides the real one. */
interface AuthGateway {
    fun availability(): AuthAvailability

    fun request(reason: String, onOutcome: (AuthOutcome) -> Unit)
}

/**
 * Shows Android's own fingerprint / face / PIN prompt. The app never sees the fingerprint
 * or the PIN; it is only told whether the phone accepted them.
 */
class Authenticator(
    private val activity: Activity,
    private val onPromptClosedWithoutSuccess: () -> Unit = {},
) : AuthGateway {
    /** True while the phone's prompt is on top of the app. */
    var inProgress = false
        private set

    override fun availability(): AuthAvailability {
        val manager = activity.getSystemService(BiometricManager::class.java)
            ?: return AuthAvailability.UNAVAILABLE
        return when (manager.canAuthenticate(ALLOWED)) {
            BiometricManager.BIOMETRIC_SUCCESS -> AuthAvailability.READY
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AuthAvailability.NO_SCREEN_LOCK
            else -> AuthAvailability.UNAVAILABLE
        }
    }

    override fun request(reason: String, onOutcome: (AuthOutcome) -> Unit) {
        if (inProgress) return
        inProgress = true
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle("NWC Ring")
            .setSubtitle(reason)
            .setAllowedAuthenticators(ALLOWED)
            .setConfirmationRequired(false)
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
        private const val ALLOWED =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}
