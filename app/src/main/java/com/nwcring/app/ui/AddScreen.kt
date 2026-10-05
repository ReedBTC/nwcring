package com.nwcring.app.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.nwcring.app.nwc.ConnectionStringParser
import com.nwcring.app.nwc.ParseProblem
import com.nwcring.app.nwc.ParseResult
import com.nwcring.app.nwc.ParsedConnection
import com.nwcring.app.vault.AddResult
import com.nwcring.app.vault.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Holds what the Add screen is working on. The pasted connection lives only here, in
 * memory, until it is saved; it is discarded when the screen closes or the app locks.
 */
private class AddFlow(
    private val vault: Vault,
    private val auth: AuthGateway,
    private val scope: CoroutineScope,
    private val onAuthenticated: () -> Unit,
    private val clearClipboard: () -> Unit,
    private val onDone: () -> Unit,
) {
    var parsed by mutableStateOf<ParsedConnection?>(null)
    var problem by mutableStateOf<ParseProblem?>(null)
    var name by mutableStateOf("")
    var purpose by mutableStateOf("")
    var walletLabel by mutableStateOf("")
    var clearAfterSaving by mutableStateOf(true)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var keyLost by mutableStateOf(false)

    fun paste(text: String?) {
        error = null
        when (val result = ConnectionStringParser.parse(text.orEmpty())) {
            is ParseResult.Ok -> {
                parsed = result.connection
                problem = null
            }

            is ParseResult.Invalid -> {
                parsed = null
                problem = result.problem
            }
        }
    }

    fun save(alreadyAsked: Boolean = false) {
        val connection = parsed ?: return
        busy = true
        error = null
        scope.launch {
            when (vault.add(connection, name, purpose, walletLabel)) {
                is AddResult.Saved -> {
                    if (clearAfterSaving) clearClipboard()
                    onDone()
                }

                AddResult.NeedsAuth -> if (alreadyAsked) {
                    fail("The phone would not unlock the encryption key. Nothing was saved. Try again.")
                } else {
                    auth.request("Confirm it's you to save this connection") { outcome ->
                        if (outcome == AuthOutcome.SUCCESS) {
                            onAuthenticated()
                            save(alreadyAsked = true)
                        } else {
                            busy = false
                        }
                    }
                }

                AddResult.NoScreenLock ->
                    fail("This phone has no screen lock, so the connection can't be protected. Nothing was saved.")

                AddResult.KeyLost -> {
                    busy = false
                    keyLost = true
                }

                AddResult.InvalidName -> fail("Give it a name of up to ${Vault.MAX_NAME} characters.")

                AddResult.Failed -> fail("Could not save. Nothing was stored.")
            }
        }
    }

    fun replaceLostKeyAndSave() {
        keyLost = false
        busy = true
        scope.launch {
            vault.replaceLostKey()
            save()
        }
    }

    private fun fail(message: String) {
        busy = false
        error = message
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScreen(
    vault: Vault,
    auth: AuthGateway,
    onAuthenticated: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val flow = remember {
        AddFlow(
            vault = vault,
            auth = auth,
            scope = scope,
            onAuthenticated = onAuthenticated,
            clearClipboard = { clipboard(context)?.clearPrimaryClip() },
            onDone = onDone,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add connection") },
                actions = { TextButton(onClick = onDone) { Text("Cancel") } },
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
            Text(
                "In your wallet, create or open a connection and copy its connection string. " +
                    "Then tap the button below. The secret goes straight from the clipboard " +
                    "into the vault; it is never shown here or typed on the keyboard.",
            )

            val pasteLabel = if (flow.parsed == null) "Paste from clipboard" else "Paste a different one"
            if (flow.parsed == null) {
                Button(onClick = { flow.paste(readClipboard(context)) }, enabled = !flow.busy) { Text(pasteLabel) }
            } else {
                OutlinedButton(onClick = { flow.paste(readClipboard(context)) }, enabled = !flow.busy) {
                    Text(pasteLabel)
                }
            }

            flow.problem?.let { problem ->
                Text(problem.message(), color = MaterialTheme.colorScheme.error)
            }

            flow.parsed?.let { connection ->
                ParsedSummary(connection)

                OutlinedTextField(
                    value = flow.name,
                    onValueChange = { flow.name = it.take(Vault.MAX_NAME) },
                    label = { Text("Name") },
                    supportingText = { Text("For example: Nostr zaps") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = flow.walletLabel,
                    onValueChange = { flow.walletLabel = it.take(Vault.MAX_NAME) },
                    label = { Text("Wallet (optional)") },
                    supportingText = { Text("Which wallet issued it, for example: Alby Hub") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = flow.purpose,
                    onValueChange = { flow.purpose = it.take(Vault.MAX_PURPOSE) },
                    label = { Text("Purpose or notes (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = flow.clearAfterSaving, onCheckedChange = { flow.clearAfterSaving = it })
                    Text("Clear the clipboard after saving")
                }

                flow.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                Button(
                    onClick = { flow.save() },
                    enabled = !flow.busy && flow.name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save") }
            }
        }
    }

    if (flow.keyLost) {
        AlertDialog(
            onDismissRequest = { flow.keyLost = false },
            title = { Text("The encryption key is gone") },
            text = {
                Text(
                    "This phone destroyed NWC Ring's encryption key, which happens when the " +
                        "screen lock is removed. Secrets saved before that can't be recovered; " +
                        "their names stay in the list so you know what to re-add. Create a new " +
                        "key and save this connection?",
                )
            },
            confirmButton = { TextButton(onClick = { flow.replaceLostKeyAndSave() }) { Text("Create new key") } },
            dismissButton = { TextButton(onClick = { flow.keyLost = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ParsedSummary(connection: ParsedConnection) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Connection read from the clipboard", style = MaterialTheme.typography.titleSmall)
            Text("Relay: " + connection.relays.joinToString(", ") { relayHost(it) })
            Text("Wallet key: " + connection.walletPubkey.take(8) + "…" + connection.walletPubkey.takeLast(8))
            connection.lud16?.let { Text("Lightning address: $it") }
            Text("Secret: present, hidden")
        }
    }
}

private fun clipboard(context: Context): ClipboardManager? = context.getSystemService(ClipboardManager::class.java)

private fun readClipboard(context: Context): String? {
    val clip = clipboard(context)?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}
