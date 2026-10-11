package com.nwcring.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.nwcring.app.core.SafeLog
import com.nwcring.app.core.SecretClipboard
import com.nwcring.app.lock.LockController
import com.nwcring.app.vault.ConnectionStore
import com.nwcring.app.vault.KeyAuth
import com.nwcring.app.vault.KeyRing
import com.nwcring.app.vault.KeyRingStore
import com.nwcring.app.vault.KeystoreCipher
import com.nwcring.app.vault.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class NwcRingApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** The app's long-lived parts, created once and wired together by hand. */
class AppGraph(app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // noBackupFilesDir is never included in any Android backup, whatever the manifest says.
    private val dataDir: File = app.noBackupFilesDir

    val keyRing = KeyRing(
        store = KeyRingStore(File(dataDir, "keyring.json")),
        device = KeystoreCipher(KeystoreCipher.DEVICE_ALIAS, KeyAuth.None),
        biometric = KeystoreCipher(
            KeystoreCipher.BIOMETRIC_ALIAS,
            KeyAuth.Biometric(KeystoreCipher.BIOMETRIC_WINDOW_SECONDS),
        ),
        elapsedMs = { SystemClock.elapsedRealtime() },
        note = { event, facts -> SafeLog.event(event, *facts.toTypedArray()) },
    )

    val lock = LockController(nowMs = { SystemClock.elapsedRealtime() }, onLocked = { keyRing.lock() })

    val vault = Vault(
        store = ConnectionStore(File(dataDir, "vault.json")),
        cipher = keyRing.cipher,
        nowMs = { System.currentTimeMillis() },
        note = { event, facts -> SafeLog.event(event, *facts.toTypedArray()) },
    )

    val clipboard = SecretClipboard(app, Handler(Looper.getMainLooper())) { SafeLog.event(it) }

    init {
        scope.launch {
            keyRing.load()
            vault.load()
        }
    }

    /** Deletes everything: the connections, the key ring, and every hardware key, including Milestone 1's. */
    suspend fun startFresh() {
        lock.lock()
        vault.wipe()
        keyRing.wipe()
        KeystoreCipher(KeystoreCipher.OLD_ALIAS, KeyAuth.None).destroyKey()
        keyRing.load()
        vault.load()
    }
}
