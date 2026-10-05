package com.nwcring.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nwcring.app.vault.KeyStorage
import com.nwcring.app.vault.StoredConnection
import com.nwcring.app.vault.VaultState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    connections: List<StoredConnection>,
    vaultState: VaultState,
    keyStorage: KeyStorage,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
    onLock: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<StoredConnection?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NWC Ring") },
                actions = { TextButton(onClick = onLock) { Text("Lock") } },
            )
        },
        floatingActionButton = {
            if (vaultState == VaultState.READY) {
                ExtendedFloatingActionButton(onClick = onAdd) { Text("Add connection") }
            }
        },
    ) { padding ->
        when {
            vaultState == VaultState.LOADING -> Unit

            vaultState == VaultState.FILE_UNREADABLE -> Notice(
                padding,
                "The saved list of connections can't be read. Nothing has been changed or deleted.",
            )

            vaultState == VaultState.FILE_FROM_NEWER_VERSION -> Notice(
                padding,
                "The saved list of connections was written by a newer version of NWC Ring. " +
                    "Update the app to open it. Nothing has been changed or deleted.",
            )

            connections.isEmpty() -> Notice(
                padding,
                "No connections yet.\n\nCopy a connection string from your wallet, then tap " +
                    "“Add connection”.",
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(connections, key = { it.id }) { connection ->
                    ConnectionCard(connection, onDelete = { pendingDelete = connection })
                }
                item {
                    Text(
                        keyStorageLine(keyStorage),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }

    pendingDelete?.let { connection ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
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
                        onDelete(connection.id)
                        pendingDelete = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Notice(padding: PaddingValues, message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectionCard(connection: StoredConnection, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
            Text(
                connection.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val labels = listOf(connection.walletLabel, connection.purpose).filter { it.isNotBlank() }
            if (labels.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    labels.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Relay: " + connection.relays.joinToString(", ") { relayHost(it) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Added " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(connection.addedAtMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

internal fun relayHost(relayUrl: String): String =
    relayUrl.removePrefix("wss://").substringBefore('/').substringBefore('?')

private fun keyStorageLine(storage: KeyStorage): String = when (storage) {
    KeyStorage.STRONGBOX -> "Secrets are encrypted by this phone's dedicated security chip."
    KeyStorage.TRUSTED_ENVIRONMENT -> "Secrets are encrypted by this phone's secure hardware."
    KeyStorage.SOFTWARE -> "Warning: this phone has no secure hardware, so the encryption key is held in software."
    KeyStorage.UNKNOWN, KeyStorage.NOT_CREATED -> ""
}
