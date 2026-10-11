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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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
    private val scope: CoroutineScope,
    private val clearClipboard: () -> Unit,
    private val onDone: () -> Unit,
) {
    var parsed by mutableStateOf<ParsedConnection?>(null)
    var problem by mutableStateOf<ParseProblem?>(null)

    /** What has been pasted into the field, shown as dots. Emptied as soon as it is read. */
    var typed by mutableStateOf("")
    var name by mutableStateOf("")
    var purpose by mutableStateOf("")
    var walletLabel by mutableStateOf("")
    var clearAfterSaving by mutableStateOf(true)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun read(text: String?) {
        error = null
        when (val result = ConnectionStringParser.parse(text.orEmpty())) {
            is ParseResult.Ok -> {
                parsed = result.connection
                problem = null
                typed = ""
            }

            is ParseResult.Invalid -> {
                parsed = null
                problem = result.problem
            }
        }
    }

    fun save() {
        val connection = parsed ?: return
        busy = true
        error = null
        scope.launch {
            when (vault.add(connection, name, purpose, walletLabel)) {
                is AddResult.Saved -> {
                    if (clearAfterSaving) clearClipboard()
                    onDone()
                }

                AddResult.Locked -> fail("NWC Ring locked before it could save. Unlock and try again.")
                AddResult.InvalidName -> fail("Give it a name of up to ${Vault.MAX_NAME} characters.")
                AddResult.Failed -> fail("Could not save. Nothing was stored.")
            }
        }
    }

    private fun fail(message: String) {
        busy = false
        error = message
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScreen(vault: Vault, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val flow = remember {
        AddFlow(
            vault = vault,
            scope = scope,
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
            if (flow.parsed == null) {
                Text(
                    "In your wallet, create or open a connection and copy its connection string. " +
                        "Then paste it below, or let NWC Ring read it from the clipboard. It is shown " +
                        "as dots and kept away from the keyboard's suggestions and history.",
                )
                // A password-style field: the keyboard is told not to learn from it, suggest for
                // it, or show it. Nobody types one of these by hand; this is a second way to paste.
                OutlinedTextField(
                    value = flow.typed,
                    onValueChange = { flow.typed = it.take(ConnectionStringParser.MAX_LENGTH + 1) },
                    label = { Text("Paste the connection string here") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { flow.read(flow.typed) }),
                    trailingIcon = {
                        TextButton(onClick = { flow.read(flow.typed) }, enabled = flow.typed.isNotBlank() && !flow.busy) {
                            Text("Use")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { flow.read(readClipboard(context)) }, enabled = !flow.busy) {
                    Text("Read it from the clipboard")
                }
            } else {
                OutlinedButton(
                    onClick = {
                        flow.parsed = null
                        flow.problem = null
                    },
                    enabled = !flow.busy,
                ) { Text("Use a different one") }
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
}

@Composable
private fun ParsedSummary(connection: ParsedConnection) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Connection found", style = MaterialTheme.typography.titleSmall)
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
