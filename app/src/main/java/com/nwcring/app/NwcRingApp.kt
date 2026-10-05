package com.nwcring.app

import android.app.Application
import android.app.KeyguardManager
import android.os.SystemClock
import com.nwcring.app.core.SafeLog
import com.nwcring.app.lock.LockController
import com.nwcring.app.vault.ConnectionStore
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

    val lock = LockController(nowMs = { SystemClock.elapsedRealtime() })

    val vault = Vault(
        // noBackupFilesDir is never included in any Android backup, whatever the manifest says.
        store = ConnectionStore(File(app.noBackupFilesDir, "vault.json")),
        cipher = KeystoreCipher(
            isDeviceSecure = { app.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true },
        ),
        nowMs = { System.currentTimeMillis() },
        note = { event, facts -> SafeLog.event(event, *facts.toTypedArray()) },
    )

    init {
        scope.launch { vault.load() }
    }
}
