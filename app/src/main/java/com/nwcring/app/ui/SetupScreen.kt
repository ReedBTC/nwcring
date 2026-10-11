package com.nwcring.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nwcring.app.lock.AuthGateway
import com.nwcring.app.lock.AuthOutcome
import com.nwcring.app.lock.BiometricAvailability
import com.nwcring.app.vault.BiometricResult
import com.nwcring.app.vault.KeyRing
import com.nwcring.app.vault.SetUpResult
import kotlinx.coroutines.launch

private enum class Stage { CHOOSE, CONFIRM, FINGERPRINT }

/** First open: choose a PIN, type it again, then optionally add the fingerprint shortcut. */
@Composable
fun SetupScreen(keyRing: KeyRing, auth: AuthGateway, onDone: () -> Unit) {
    var stage by remember { mutableStateOf(Stage.CHOOSE) }
    var first by remember { mutableStateOf<CharArray?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val entry = rememberPinEntry()
    val scope = rememberCoroutineScope()

    fun finishPin(second: CharArray) {
        val chosen = first
        if (chosen == null || !chosen.contentEquals(second)) {
            chosen?.fill('\u0000')
            second.fill('\u0000')
            first = null
            message = "The two PINs didn't match. Choose it again."
            stage = Stage.CHOOSE
            return
        }
        chosen.fill('\u0000')
        first = null
        busy = true
        scope.launch {
            when (keyRing.setUp(second)) {
                SetUpResult.Done -> {
                    message = null
                    if (auth.biometrics() == BiometricAvailability.READY) stage = Stage.FINGERPRINT else onDone()
                }

                SetUpResult.BadPin -> {
                    message = "A PIN is ${KeyRing.MIN_PIN} to ${KeyRing.MAX_PIN} digits."
                    stage = Stage.CHOOSE
                }

                SetUpResult.AlreadySetUp -> onDone()
                SetUpResult.Failed -> {
                    message = "Could not set up the vault. Try again."
                    stage = Stage.CHOOSE
                }
            }
            busy = false
        }
    }

    fun turnOnFingerprint(retried: Boolean = false) {
        auth.request("Confirm your fingerprint to turn this on") { outcome ->
            when (outcome) {
                AuthOutcome.SUCCESS -> scope.launch {
                    busy = true
                    when (keyRing.enableBiometric()) {
                        BiometricResult.Accepted -> onDone()
                        BiometricResult.NeedsFingerprint ->
                            if (retried) message = "That took too long. Try again." else turnOnFingerprint(retried = true)
                        else -> message = "Couldn't turn on fingerprint unlock. You can try again later from Settings."
                    }
                    busy = false
                }

                AuthOutcome.FAILED -> message = "The phone didn't accept that fingerprint. You can try again later from Settings."
                AuthOutcome.USE_PIN, AuthOutcome.CANCELLED -> Unit
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("NWC Ring", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))
        when (stage) {
            Stage.CHOOSE -> {
                Text("Choose a PIN", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "${KeyRing.MIN_PIN} to ${KeyRing.MAX_PIN} digits. It unlocks NWC Ring on this phone, and " +
                        "it is separate from the phone's own PIN. There is no way to recover it: if you " +
                        "forget it, you start over and add your connections again.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                message?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                }
                PinPad(
                    entry = entry,
                    enabled = !busy,
                    submitLabel = "Continue",
                    onSubmit = {
                        first = entry.take()
                        message = null
                        stage = Stage.CONFIRM
                    },
                )
            }

            Stage.CONFIRM -> {
                Text("Type it again", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(24.dp))
                PinPad(
                    entry = entry,
                    enabled = !busy,
                    submitLabel = "Set PIN",
                    onSubmit = { finishPin(entry.take()) },
                )
            }

            Stage.FINGERPRINT -> {
                Text("Unlock with fingerprint too?", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your PIN always works. A fingerprint is a shortcut: the phone's secure chip keeps a " +
                        "second copy of the vault key that opens only after a fingerprint, never after the " +
                        "phone's own PIN. You can change this later in Settings.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                message?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDone, enabled = !busy) { Text("Not now") }
                    Button(onClick = { turnOnFingerprint() }, enabled = !busy) { Text("Turn on") }
                }
            }
        }
    }
}
