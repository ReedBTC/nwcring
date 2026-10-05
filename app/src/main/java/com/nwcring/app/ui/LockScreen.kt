package com.nwcring.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nwcring.app.lock.AuthAvailability
import com.nwcring.app.lock.AuthGateway
import com.nwcring.app.lock.AuthOutcome

@Composable
fun LockScreen(
    auth: AuthGateway,
    visits: Int,
    onUnlocked: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
) {
    // Re-checked every time the app comes back to the screen, e.g. after setting a screen lock.
    val availability = remember(visits) { auth.availability() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("NWC Ring", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Locked",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        when (availability) {
            AuthAvailability.READY -> Button(
                onClick = {
                    auth.request("Unlock your connections") { outcome ->
                        if (outcome == AuthOutcome.SUCCESS) onUnlocked()
                    }
                },
            ) { Text("Unlock") }

            AuthAvailability.NO_SCREEN_LOCK -> {
                Text(
                    "This phone has no screen lock. NWC Ring protects your connections with the " +
                        "phone's own PIN, pattern, password or fingerprint, so it needs one to be set.",
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onOpenSecuritySettings) { Text("Open security settings") }
            }

            AuthAvailability.UNAVAILABLE -> Text(
                "This phone can't confirm who is using it right now, so NWC Ring can't unlock. " +
                    "Check the phone's screen lock settings and try again.",
                textAlign = TextAlign.Center,
            )
        }
    }
}
