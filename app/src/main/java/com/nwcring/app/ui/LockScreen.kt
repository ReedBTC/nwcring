package com.nwcring.app.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.nwcring.app.vault.PinResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LockScreen(
    keyRing: KeyRing,
    auth: AuthGateway,
    visits: Int,
    onUnlocked: () -> Unit,
    onStartOver: suspend () -> Unit,
) {
    val biometricEnabled by keyRing.biometricEnabled.collectAsState()
    val needsRepair by keyRing.biometricNeedsRepair.collectAsState()
    val waitUntil by keyRing.waitUntilElapsedMs.collectAsState()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var keyLost by remember { mutableStateOf(false) }
    var confirmingStartOver by remember { mutableStateOf(false) }
    val entry = rememberPinEntry()
    val scope = rememberCoroutineScope()

    // Count the wait down on screen.
    LaunchedEffect(waitUntil) {
        now = SystemClock.elapsedRealtime()
        while (waitUntil > now) {
            delay(1_000)
            now = SystemClock.elapsedRealtime()
        }
    }
    val waiting = waitUntil > now
    // Re-checked every time the app comes back to the screen.
    val fingerprintReady = remember(visits, biometricEnabled, needsRepair) {
        biometricEnabled && !needsRepair && auth.biometrics() == BiometricAvailability.READY
    }

    fun promptFingerprint(retried: Boolean = false) {
        auth.request("Unlock your connections") { outcome ->
            when (outcome) {
                AuthOutcome.SUCCESS -> scope.launch {
                    busy = true
                    when (keyRing.unlockWithBiometric()) {
                        BiometricResult.Accepted -> onUnlocked()
                        BiometricResult.NeedsFingerprint ->
                            if (retried) message = "That took too long. Try again, or use your PIN." else promptFingerprint(retried = true)
                        BiometricResult.FingerprintsChanged -> message =
                            "The fingerprints on this phone changed, so enter your PIN this once. " +
                                "Fingerprint unlock can be turned back on in Settings."
                        BiometricResult.NotEnabled, BiometricResult.Failed -> message = "Fingerprint unlock didn't work. Use your PIN."
                    }
                    busy = false
                }

                AuthOutcome.FAILED -> message = "The phone didn't accept that fingerprint. Use your PIN."
                AuthOutcome.USE_PIN, AuthOutcome.CANCELLED -> Unit
            }
        }
    }

    // Coming to the screen with fingerprint on: ask straight away rather than wait for a tap.
    LaunchedEffect(visits) {
        if (fingerprintReady && !waiting) promptFingerprint()
    }

    fun submitPin() {
        busy = true
        message = null
        scope.launch {
            when (val result = keyRing.unlockWithPin(entry.take())) {
                PinResult.Accepted -> onUnlocked()
                PinResult.KeyLost -> keyLost = true
                else -> message = result.message()
            }
            busy = false
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
        Spacer(Modifier.height(4.dp))
        Text("Locked", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        when {
            waiting -> Text(
                "Too many wrong PINs. Try again in ${formatWait(waitUntil - now)}.",
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )

            message != null -> Text(message!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            else -> Text("Enter your PIN", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        PinPad(
            entry = entry,
            enabled = !busy && !waiting,
            submitLabel = "Unlock",
            onSubmit = ::submitPin,
            corner = {
                if (fingerprintReady) {
                    TextButton(onClick = { promptFingerprint() }, enabled = !busy && !waiting) { Text("Finger\nprint", textAlign = TextAlign.Center) }
                }
            },
        )
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = { confirmingStartOver = true }, enabled = !busy) { Text("Forgot your PIN?") }
    }

    if (confirmingStartOver) {
        StartOverDialog(
            onConfirm = {
                confirmingStartOver = false
                busy = true
                scope.launch {
                    onStartOver()
                    busy = false
                }
            },
            onDismiss = { confirmingStartOver = false },
        )
    }

    if (keyLost) {
        AlertDialog(
            onDismissRequest = { keyLost = false },
            title = { Text("The vault can't be opened") },
            text = { Text(PinResult.KeyLost.message()!!) },
            confirmButton = {
                TextButton(
                    onClick = {
                        keyLost = false
                        confirmingStartOver = true
                    },
                ) { Text("Start over") }
            },
            dismissButton = { TextButton(onClick = { keyLost = false }) { Text("Not now") } },
        )
    }
}
