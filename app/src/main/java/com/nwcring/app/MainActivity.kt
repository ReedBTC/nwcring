package com.nwcring.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.nwcring.app.core.SafeLog
import com.nwcring.app.lock.AuthAvailability
import com.nwcring.app.lock.AuthOutcome
import com.nwcring.app.lock.Authenticator
import com.nwcring.app.ui.AppRoot
import com.nwcring.app.ui.NwcRingTheme

class MainActivity : ComponentActivity() {
    private val graph get() = (application as NwcRingApp).graph
    private lateinit var authenticator: Authenticator
    private var visits by mutableIntStateOf(0)
    private var inFront = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the app out of screenshots, screen recordings, casting and the recent-apps
        // thumbnails. Applied to the whole window so no screen can be forgotten. Only a
        // debuggable build (never one that is released) skips it, so screens can be checked
        // during development.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        }
        // If a prompt ends while the app is no longer in front, the app is locked.
        authenticator = Authenticator(this) { if (!inFront) lockNow("prompt ended away from the screen") }
        enableEdgeToEdge()
        setContent {
            NwcRingTheme {
                AppRoot(
                    vault = graph.vault,
                    lock = graph.lock,
                    auth = authenticator,
                    visits = visits,
                    onOpenSecuritySettings = { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        inFront = true
        visits++
        // Coming to the screen while locked: ask straight away rather than wait for a tap.
        if (graph.lock.locked.value && authenticator.availability() == AuthAvailability.READY) {
            authenticator.request("Unlock your connections") { outcome ->
                if (outcome == AuthOutcome.SUCCESS) graph.lock.unlocked()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        inFront = false
        // Leaving the screen locks the app at once. The one exception is the phone's own
        // fingerprint / PIN prompt sitting on top of the app, which is not leaving.
        if (!isChangingConfigurations && !authenticator.inProgress) lockNow("paused")
    }

    override fun onStop() {
        super.onStop()
        // Rotating the phone is not leaving; anything else that gets this far is.
        if (!isChangingConfigurations) lockNow("stopped")
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        graph.lock.touched()
    }

    private fun lockNow(reason: String) {
        if (!graph.lock.locked.value) SafeLog.event("locked", "reason" to reason)
        graph.lock.lock()
    }
}
