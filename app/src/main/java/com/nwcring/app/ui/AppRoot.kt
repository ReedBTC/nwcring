package com.nwcring.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nwcring.app.lock.AuthGateway
import com.nwcring.app.lock.LockController
import com.nwcring.app.vault.Vault
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Screen { LIST, ADD }

/**
 * Everything on screen. While the app is locked only the lock screen exists; the unlocked
 * screens and whatever they were holding are thrown away, not hidden.
 */
@Composable
fun AppRoot(
    vault: Vault,
    lock: LockController,
    auth: AuthGateway,
    visits: Int,
    onOpenSecuritySettings: () -> Unit,
) {
    val locked by lock.locked.collectAsState()

    LaunchedEffect(locked) {
        while (!locked) {
            delay(1_000)
            lock.tick()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (locked) {
            LockScreen(
                auth = auth,
                visits = visits,
                onUnlocked = lock::unlocked,
                onOpenSecuritySettings = onOpenSecuritySettings,
            )
        } else {
            UnlockedApp(vault = vault, auth = auth, onLock = lock::lock, onAuthenticated = lock::unlocked)
        }
    }
}

@Composable
private fun UnlockedApp(vault: Vault, auth: AuthGateway, onLock: () -> Unit, onAuthenticated: () -> Unit) {
    var screen by remember { mutableStateOf(Screen.LIST) }
    val scope = rememberCoroutineScope()
    val connections by vault.connections.collectAsState()
    val vaultState by vault.state.collectAsState()

    when (screen) {
        Screen.LIST -> ListScreen(
            connections = connections,
            vaultState = vaultState,
            keyStorage = remember(connections) { vault.keyStorage() },
            onAdd = { screen = Screen.ADD },
            onDelete = { id -> scope.launch { vault.delete(id) } },
            onLock = onLock,
        )

        Screen.ADD -> {
            BackHandler { screen = Screen.LIST }
            AddScreen(
                vault = vault,
                auth = auth,
                onAuthenticated = onAuthenticated,
                onDone = { screen = Screen.LIST },
            )
        }
    }
}
