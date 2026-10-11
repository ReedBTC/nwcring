package com.nwcring.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nwcring.app.vault.KeyRing

/**
 * The digits typed so far. Kept as characters, not a string, so they can be wiped the
 * moment they have been checked. Nothing here is ever logged.
 */
class PinEntry {
    val digits = mutableStateListOf<Char>()
    val length get() = digits.size
    val complete get() = length >= KeyRing.MIN_PIN

    fun add(digit: Char) {
        if (length < KeyRing.MAX_PIN) digits.add(digit)
    }

    fun backspace() {
        if (digits.isNotEmpty()) digits.removeAt(digits.lastIndex)
    }

    /** Hands over the digits and forgets them. The receiver must wipe the array when done. */
    fun take(): CharArray {
        val pin = digits.toCharArray()
        digits.clear()
        return pin
    }

    fun clear() = digits.clear()
}

@Composable
fun rememberPinEntry(): PinEntry = remember { PinEntry() }

/** The app's own number pad, so the PIN never passes through a keyboard app. */
@Composable
fun PinPad(
    entry: PinEntry,
    enabled: Boolean,
    onSubmit: () -> Unit,
    submitLabel: String,
    modifier: Modifier = Modifier,
    corner: @Composable () -> Unit = {},
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        PinDots(entry.length)
        Spacer(Modifier.height(20.dp))
        val rows = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'))
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (digit in row) Key(digit.toString(), enabled) { entry.add(digit) }
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(KEY_SIZE), contentAlignment = Alignment.Center) { corner() }
            Key("0", enabled) { entry.add('0') }
            Box(Modifier.size(KEY_SIZE), contentAlignment = Alignment.Center) {
                TextButton(
                    onClick = entry::backspace,
                    enabled = enabled && entry.length > 0,
                    modifier = Modifier.semantics { contentDescription = "Delete the last digit" },
                ) { Text("⌫", style = MaterialTheme.typography.titleLarge) }
            }
        }
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(onClick = onSubmit, enabled = enabled && entry.complete) { Text(submitLabel) }
    }
}

@Composable
private fun Key(label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(KEY_SIZE), shape = CircleShape) {
        Text(label, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun PinDots(count: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .height(16.dp)
            .semantics { contentDescription = "$count digits entered" },
    ) {
        val filled = MaterialTheme.colorScheme.primary
        val empty = MaterialTheme.colorScheme.surfaceVariant
        repeat(KeyRing.MIN_PIN.coerceAtLeast(count)) { index ->
            Box(
                Modifier
                    .size(12.dp)
                    .background(if (index < count) filled else empty, CircleShape),
            )
        }
        if (count < KeyRing.MIN_PIN) Spacer(Modifier.width(0.dp))
    }
}

private val KEY_SIZE = 72.dp
