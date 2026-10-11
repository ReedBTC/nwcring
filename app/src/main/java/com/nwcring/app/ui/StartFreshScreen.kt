package com.nwcring.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.launch

/** Shown when the only way forward is to delete what is stored and begin again. */
@Composable
fun StartFreshScreen(title: String, message: String, onStartOver: suspend () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(message, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { confirming = true }, enabled = !busy) { Text("Start over") }
    }

    if (confirming) {
        StartOverDialog(
            onConfirm = {
                confirming = false
                busy = true
                scope.launch {
                    onStartOver()
                    busy = false
                }
            },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
fun StartOverDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete everything in NWC Ring?") },
        text = {
            Text(
                "This deletes every connection saved in NWC Ring on this phone, along with its PIN " +
                    "and keys. It does not revoke anything at your wallets: revoke the old " +
                    "connections there, then add new ones here.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete everything") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
