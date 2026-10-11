package com.nwcring.app.core

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.PersistableBundle
import android.os.SystemClock
import com.nwcring.app.nwc.SecretText

/**
 * The one place that puts a secret on the clipboard. It is flagged sensitive, so Android 13
 * and later keep it out of the clipboard preview, and it is cleared again after
 * [CLEAR_AFTER_MS] whether or not the app is still on screen. Some keyboards keep their own
 * clipboard history; nothing an app does can clear that.
 */
class SecretClipboard(
    private val context: Context,
    private val handler: Handler,
    private val note: (event: String) -> Unit = {},
) {
    private val clear = Runnable { clearNow() }

    /** Time since boot at which the last copy must be gone, or 0 when nothing is pending. */
    @Volatile
    private var deadlineMs = 0L

    fun copy(text: SecretText): Boolean {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return false
        val clip = ClipData.newPlainText("NWC Ring", text.reveal())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        manager.setPrimaryClip(clip)
        handler.removeCallbacks(clear)
        deadlineMs = SystemClock.elapsedRealtime() + CLEAR_AFTER_MS
        handler.postDelayed(clear, CLEAR_AFTER_MS)
        note("copied")
        return true
    }

    /**
     * Android can pause a backgrounded app's timers (the "cached app freezer"), so the timed
     * clear could be late. Called when the app comes back to the screen: if the deadline
     * has passed and the timer never fired, clear now.
     */
    fun clearIfOverdue() {
        if (deadlineMs != 0L && SystemClock.elapsedRealtime() >= deadlineMs) clearNow()
    }

    fun clearNow() {
        deadlineMs = 0
        handler.removeCallbacks(clear)
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        manager.clearPrimaryClip()
        note("clipboard_cleared")
    }

    companion object {
        const val CLEAR_AFTER_MS = 60_000L
    }
}
