package com.nwcring.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nwcring.app.lock.AuthGateway
import com.nwcring.app.lock.AuthOutcome
import com.nwcring.app.lock.BiometricAvailability
import com.nwcring.app.vault.BiometricResult
import com.nwcring.app.vault.KeyRing
import com.nwcring.app.vault.PinResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The fresh "is it really you?" check before a secret is revealed or copied. Fingerprint
 * first when that is switched on, the PIN otherwise or as the fallback. Either way the
 * check is real: it re-opens a locked copy of the vault key, not just a yes/no flag.
 */
class ReAuth(
    private val keyRing: KeyRing,
    private val auth: AuthGateway,
    private val scope: CoroutineScope,
) {
    class PinRequest(val reason: String, val onPassed: () -> Unit) {
        var message by mutableStateOf<String?>(null)
        var busy by mutableStateOf(false)
    }

    /** Non-null while the PIN dialog is up. */
    var pinRequest by mutableStateOf<PinRequest?>(null)
        private set

    fun require(reason: String, onPassed: () -> Unit) {
        if (keyRing.biometricEnabled.value && !keyRing.biometricNeedsRepair.value &&
            auth.biometrics() == BiometricAvailability.READY
        ) {
            promptFingerprint(reason, onPassed, retried = false)
        } else {
            pinRequest = PinRequest(reason, onPassed)
        }
    }

    private fun promptFingerprint(reason: String, onPassed: () -> Unit, retried: Boolean) {
        auth.request(reason) { outcome ->
            when (outcome) {
                AuthOutcome.SUCCESS -> scope.launch {
                    when (keyRing.confirmBiometric()) {
                        BiometricResult.Accepted -> onPassed()
                        BiometricResult.NeedsFingerprint ->
                            if (retried) pinRequest = PinRequest(reason, onPassed) else promptFingerprint(reason, onPassed, retried = true)
                        else -> pinRequest = PinRequest(reason, onPassed)
                    }
                }

                AuthOutcome.USE_PIN, AuthOutcome.FAILED -> pinRequest = PinRequest(reason, onPassed)
                AuthOutcome.CANCELLED -> Unit
            }
        }
    }

    fun submitPin(entry: PinEntry) {
        val request = pinRequest ?: return
        request.busy = true
        scope.launch {
            when (val result = keyRing.confirmPin(entry.take())) {
                PinResult.Accepted -> {
                    pinRequest = null
                    request.onPassed()
                }

                else -> request.message = result.message()
            }
            request.busy = false
        }
    }

    fun cancel() {
        pinRequest = null
    }
}

@Composable
fun ReAuthDialog(reAuth: ReAuth) {
    val request = reAuth.pinRequest ?: return
    key(request) {
        val entry = rememberPinEntry()
        AlertDialog(
            onDismissRequest = reAuth::cancel,
            title = { Text(request.reason) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Enter your NWC Ring PIN.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    request.message?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(16.dp))
                    PinPad(
                        entry = entry,
                        enabled = !request.busy,
                        submitLabel = "Confirm",
                        onSubmit = { reAuth.submitPin(entry) },
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = reAuth::cancel) { Text("Cancel") } },
        )
    }
}

