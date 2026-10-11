package com.nwcring.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
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
import com.nwcring.app.AppGraph
import com.nwcring.app.lock.AuthGateway
import com.nwcring.app.vault.KeyRingState
import com.nwcring.app.vault.VaultState
import kotlinx.coroutines.delay

private sealed interface Screen {
    data object List : Screen

    data object Add : Screen

    data object Settings : Screen

    data class Detail(val id: String) : Screen
}

/**
 * Everything on screen. While the app is locked only the lock screen exists; the unlocked
 * screens and whatever they were holding are thrown away, not hidden.
 */
@Composable
fun AppRoot(graph: AppGraph, auth: AuthGateway, visits: Int) {
    val keyState by graph.keyRing.state.collectAsState()
    val vaultState by graph.vault.state.collectAsState()
    val locked by graph.lock.locked.collectAsState()
    // Setting up unlocks the key ring part-way through (the fingerprint step comes after the
    // PIN), so the set-up screen stays until it says it is done.
    var settingUp by remember { mutableStateOf(false) }
    val startOver: suspend () -> Unit = {
        settingUp = false
        graph.startFresh()
    }

    LaunchedEffect(locked) {
        while (!locked) {
            delay(1_000)
            graph.lock.tick()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            keyState == KeyRingState.LOADING || vaultState == VaultState.LOADING -> Unit

            vaultState == VaultState.FILE_FROM_OLDER_VERSION -> StartFreshScreen(
                title = "An earlier test version's data is here",
                message = "This phone has connections saved by a test version of NWC Ring that this version " +
                    "cannot read. They were test entries. Start over to delete them and set a PIN.",
                onStartOver = startOver,
            )

            keyState == KeyRingState.FILE_UNREADABLE -> StartFreshScreen(
                title = "The key ring can't be read",
                message = "The file that holds NWC Ring's locked keys is damaged, so the saved connections " +
                    "can't be opened. The only way forward is to start over.",
                onStartOver = startOver,
            )

            keyState == KeyRingState.FILE_FROM_NEWER_VERSION -> Notice(
                PaddingValues(),
                "NWC Ring's keys were written by a newer version of the app. Update the app to open it. " +
                    "Nothing has been changed or deleted.",
            )

            keyState == KeyRingState.NOT_SET_UP || (settingUp && keyState == KeyRingState.UNLOCKED) -> {
                settingUp = true
                SetupScreen(
                    keyRing = graph.keyRing,
                    auth = auth,
                    onDone = {
                        settingUp = false
                        graph.lock.unlocked()
                    },
                )
            }

            locked || keyState != KeyRingState.UNLOCKED -> LockScreen(
                keyRing = graph.keyRing,
                auth = auth,
                visits = visits,
                onUnlocked = graph.lock::unlocked,
                onStartOver = startOver,
            )

            else -> UnlockedApp(graph = graph, auth = auth, onStartOver = startOver)
        }
    }
}

@Composable
private fun UnlockedApp(graph: AppGraph, auth: AuthGateway, onStartOver: suspend () -> Unit) {
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    val scope = rememberCoroutineScope()
    val reAuth = remember { ReAuth(graph.keyRing, auth, scope) }
    val connections by graph.vault.connections.collectAsState()
    val vaultState by graph.vault.state.collectAsState()

    when (val current = screen) {
        Screen.List -> ListScreen(
            connections = connections,
            vaultState = vaultState,
            onAdd = { screen = Screen.Add },
            onOpen = { id -> screen = Screen.Detail(id) },
            onSettings = { screen = Screen.Settings },
            onLock = graph.lock::lock,
        )

        Screen.Add -> {
            BackHandler { screen = Screen.List }
            AddScreen(vault = graph.vault, onDone = { screen = Screen.List })
        }

        Screen.Settings -> {
            BackHandler { screen = Screen.List }
            SettingsScreen(
                keyRing = graph.keyRing,
                auth = auth,
                onBack = { screen = Screen.List },
                onStartOver = onStartOver,
            )
        }

        is Screen.Detail -> {
            BackHandler { screen = Screen.List }
            val connection = connections.firstOrNull { it.id == current.id }
            if (connection == null) {
                screen = Screen.List
            } else {
                DetailScreen(
                    connection = connection,
                    vault = graph.vault,
                    reAuth = reAuth,
                    clipboard = graph.clipboard,
                    onBack = { screen = Screen.List },
                    onDeleted = { screen = Screen.List },
                )
            }
        }
    }

    ReAuthDialog(reAuth)
}
