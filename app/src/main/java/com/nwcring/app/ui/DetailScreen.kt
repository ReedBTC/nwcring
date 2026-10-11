package com.nwcring.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nwcring.app.core.SecretClipboard
import com.nwcring.app.nwc.SecretText
import com.nwcring.app.vault.EditResult
import com.nwcring.app.vault.RevealResult
import com.nwcring.app.vault.StoredConnection
import com.nwcring.app.vault.Vault
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * One connection: everything about it, the "used in" list, and the two guarded actions,
 * reveal and copy. The revealed string lives only in this screen's memory and is dropped
 * after [REVEAL_SECONDS], when the screen closes, or when the app locks.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    connection: StoredConnection,
    vault: Vault,
    reAuth: ReAuth,
    clipboard: SecretClipboard,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(connection.name) }
    var walletLabel by remember { mutableStateOf(connection.walletLabel) }
    var purpose by remember { mutableStateOf(connection.purpose) }
    var newLabel by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf<SecretText?>(null) }
    var secondsLeft by remember { mutableIntStateOf(0) }

    LaunchedEffect(revealed) {
        if (revealed == null) return@LaunchedEffect
        secondsLeft = REVEAL_SECONDS
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft--
        }
        revealed = null
    }

    fun withSecret(reason: String, use: (SecretText) -> Unit) {
        reAuth.require(reason) {
            scope.launch {
                when (val result = vault.reveal(connection.id)) {
                    is RevealResult.Revealed -> use(result.text)
                    RevealResult.Locked -> error = "NWC Ring locked in the meantime."
                    RevealResult.Unreadable -> error =
                        "This connection can't be decrypted on this phone. Delete it and add it again from your wallet."
                    RevealResult.NotFound -> onBack()
                }
            }
        }
    }

    fun saveEdits() {
        scope.launch {
            when (vault.edit(connection.id, name, purpose, walletLabel)) {
                EditResult.Done -> {
                    editing = false
                    error = null
                }

                EditResult.InvalidName -> error = "Give it a name of up to ${Vault.MAX_NAME} characters."
                else -> error = "Could not save the change."
            }
        }
    }

    fun setUsedIn(labels: List<String>) {
        scope.launch {
            when (vault.setUsedIn(connection.id, labels)) {
                EditResult.Done -> error = null
                EditResult.InvalidLabel -> error =
                    "Up to ${Vault.MAX_USED_IN} places, each up to ${Vault.MAX_LABEL} characters."
                else -> error = "Could not save the change."
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(connection.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = "Back") }
                },
                actions = {
                    if (!editing) {
                        TextButton(
                            onClick = {
                                name = connection.name
                                walletLabel = connection.walletLabel
                                purpose = connection.purpose
                                editing = true
                            },
                        ) { Text("Edit") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Connection string", style = MaterialTheme.typography.titleSmall)
                    val shown = revealed
                    if (shown == null) {
                        Text("Hidden", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        // Deliberately not selectable: the only way to copy it is the Copy button,
                        // which flags the clipboard sensitive and clears it later.
                        Text(shown.reveal(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Hides again in $secondsLeft s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (shown == null) {
                            OutlinedButton(onClick = { withSecret("Confirm it's you to reveal this connection") { revealed = it } }) {
                                Text("Reveal")
                            }
                        } else {
                            OutlinedButton(onClick = { revealed = null }) { Text("Hide") }
                        }
                        Button(
                            onClick = {
                                withSecret("Confirm it's you to copy this connection") { text ->
                                    if (clipboard.copy(text)) {
                                        scope.launch {
                                            snackbar.showSnackbar(
                                                "Copied. The clipboard clears itself in ${SecretClipboard.CLEAR_AFTER_MS / 1000} seconds.",
                                            )
                                        }
                                    } else {
                                        error = "Could not reach the clipboard."
                                    }
                                }
                            },
                        ) { Text("Copy") }
                    }
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (editing) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(Vault.MAX_NAME) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = walletLabel,
                    onValueChange = { walletLabel = it.take(Vault.MAX_NAME) },
                    label = { Text("Wallet (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = purpose,
                    onValueChange = { purpose = it.take(Vault.MAX_PURPOSE) },
                    label = { Text("Purpose or notes (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { editing = false }) { Text("Cancel") }
                    Button(onClick = ::saveEdits, enabled = name.isNotBlank()) { Text("Save") }
                }
            } else {
                Field("Wallet", connection.walletLabel.ifBlank { "Not named" })
                Field("Purpose or notes", connection.purpose.ifBlank { "None" })
            }

            Text("Used in", style = MaterialTheme.typography.titleSmall)
            Text(
                "Every app this connection was pasted into. When the secret is replaced, this is the list of " +
                    "places to paste the new one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (connection.usedIn.isEmpty()) {
                Text("Nowhere yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (label in connection.usedIn) {
                        InputChip(
                            selected = false,
                            onClick = { setUsedIn(connection.usedIn - label) },
                            label = { Text(label) },
                            trailingIcon = { Icon(AppIcons.Close, contentDescription = "Remove $label") },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newLabel,
                    onValueChange = { newLabel = it.take(Vault.MAX_LABEL) },
                    label = { Text("App or place") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        setUsedIn(connection.usedIn + newLabel.trim())
                        newLabel = ""
                    },
                    enabled = newLabel.isNotBlank(),
                ) { Text("Add") }
            }

            Text("Details", style = MaterialTheme.typography.titleSmall)
            Field("Relays", connection.relays.joinToString("\n"))
            Field("Wallet's public key", connection.walletPubkey, monospace = true)
            connection.lud16?.let { Field("Lightning address", it) }
            Field("Added", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(connection.addedAtMs)))

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { confirmingDelete = true }) { Text("Delete this connection") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete “${connection.name}”?") },
            text = {
                Text(
                    "This removes it from NWC Ring only. It does not revoke the connection in " +
                        "your wallet, and any app you pasted it into keeps working.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        scope.launch {
                            if (vault.delete(connection.id)) onDeleted() else error = "Could not delete."
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Field(label: String, value: String, monospace: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(
                value,
                style = if (monospace) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
                fontFamily = if (monospace) FontFamily.Monospace else null,
            )
        }
    }
}

const val REVEAL_SECONDS = 30
