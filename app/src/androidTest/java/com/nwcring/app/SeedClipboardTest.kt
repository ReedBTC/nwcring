package com.nwcring.app

import android.content.ClipData
import android.content.ClipboardManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a test of the app: a helper for checking the screens by hand on the emulator. It puts
 * a made-up connection string (or any text passed as `clip`) on the clipboard, because
 * there is no other way to do that from the build box. Skipped unless asked for with
 * `-e seedClipboard true`.
 */
@RunWith(AndroidJUnit4::class)
class SeedClipboardTest {
    @Test fun putsMadeUpTextOnTheClipboard() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("seedClipboard") == "true")
        val text = arguments.getString("clip")
            ?: ("nostr+walletconnect://" + "b".repeat(64) + "?relay=wss%3A%2F%2Frelay.example.com&secret=" + "a1".repeat(32))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("made up", text))
        }
    }
}
