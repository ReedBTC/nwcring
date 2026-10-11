package com.nwcring.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nwcring.app.lock.AuthGateway
import com.nwcring.app.lock.AuthOutcome
import com.nwcring.app.lock.BiometricAvailability
import com.nwcring.app.vault.BiometricResult
import com.nwcring.app.vault.KeyRing
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    keyRing: KeyRing,
    auth: AuthGateway,
    onBack: () -> Unit,
    onStartOver: suspend () -> Unit,
) {
    val biometricEnabled by keyRing.biometricEnabled.collectAsState()
    val needsRepair by keyRing.biometricNeedsRepair.collectAsState()
    val availability = remember { auth.biometrics() }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmingStartOver by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun turnOn(retried: Boolean = false) {
        auth.request("Confirm your fingerprint to turn this on") { outcome ->
            when (outcome) {
                AuthOutcome.SUCCESS -> scope.launch {
                    busy = true
                    message = when (keyRing.enableBiometric()) {
                        BiometricResult.Accepted -> null
                        BiometricResult.NeedsFingerprint -> if (retried) "That took too long. Try again." else {
                            turnOn(retried = true)
                            null
                        }
                        else -> "Couldn't turn on fingerprint unlock."
                    }
                    busy = false
                }

                AuthOutcome.FAILED -> message = "The phone didn't accept that fingerprint."
                AuthOutcome.USE_PIN, AuthOutcome.CANCELLED -> Unit
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Unlock with fingerprint", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            availability == BiometricAvailability.NONE_ENROLLED ->
                                "No fingerprint is enrolled on this phone. Add one in the phone's settings first."
                            availability == BiometricAvailability.UNAVAILABLE ->
                                "This phone has no fingerprint sensor NWC Ring can use."
                            needsRepair ->
                                "The fingerprints on this phone changed, so this is off until you turn it back on."
                            else ->
                                "A shortcut for your PIN. The phone's own PIN is never accepted."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = biometricEnabled && !needsRepair,
                    enabled = !busy && availability == BiometricAvailability.READY,
                    onCheckedChange = { wanted ->
                        message = null
                        if (wanted) {
                            turnOn()
                        } else {
                            scope.launch {
                                busy = true
                                if (!keyRing.disableBiometric()) message = "Couldn't turn off fingerprint unlock."
                                busy = false
                            }
                        }
                    },
                )
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            HorizontalDivider()

            Text("Where the key lives", style = MaterialTheme.typography.titleMedium)
            Text(
                keyStorageLine(keyRing.deviceKeyStorage()).ifEmpty { "The vault key has not been created yet." } +
                    " Your connections are encrypted under a key that is itself locked by your PIN and by " +
                    "this phone, so copying the app's files to another phone gives nothing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Text("Start over", style = MaterialTheme.typography.titleMedium)
            Text(
                "Deletes every connection, the PIN and the keys on this phone. Nothing at your wallets changes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { confirmingStartOver = true }, enabled = !busy) { Text("Delete everything") }

            HorizontalDivider()

            Text(
                "NWC Ring $version. Stores and checks wallet connections; never spends.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
}
