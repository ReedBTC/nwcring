package com.nwcring.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nwcring.app.vault.StoredConnection
import com.nwcring.app.vault.VaultState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    connections: List<StoredConnection>,
    vaultState: VaultState,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onLock: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NWC Ring") },
                actions = {
                    TextButton(onClick = onSettings) { Text("Settings") }
                    TextButton(onClick = onLock) { Text("Lock") }
                },
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

            vaultState == VaultState.FILE_FROM_OLDER_VERSION -> Notice(
                padding,
                "The saved list of connections is from an older test version. Start over from Settings.",
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
                    ConnectionCard(connection, onOpen = { onOpen(connection.id) })
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(connection: StoredConnection, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
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
            if (connection.usedIn.isNotEmpty()) {
                Text(
                    "Used in: " + connection.usedIn.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "Added " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(connection.addedAtMs)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun relayHost(relayUrl: String): String =
    relayUrl.removePrefix("wss://").substringBefore('/').substringBefore('?')
