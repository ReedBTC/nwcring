package com.nwcring.app.ui

import com.nwcring.app.nwc.ParseProblem
import com.nwcring.app.vault.KeyRing
import com.nwcring.app.vault.KeyStorage
import com.nwcring.app.vault.PinResult

/** What the user is told when a pasted connection is rejected. None of these repeat the input. */
fun ParseProblem.message(): String = when (this) {
    ParseProblem.EMPTY ->
        "Nothing to read. Copy the connection string in your wallet first."
    ParseProblem.TOO_LONG ->
        "That is too long to be a wallet connection."
    ParseProblem.HAS_WHITESPACE ->
        "That has spaces or line breaks in it. Copy the whole connection string again, as one piece."
    ParseProblem.BAD_CHARACTERS ->
        "That contains characters a wallet connection never has. Copy it again from your wallet."
    ParseProblem.NOT_A_CONNECTION ->
        "That isn't a wallet connection. One starts with nostr+walletconnect://"
    ParseProblem.BAD_WALLET_KEY ->
        "The wallet's key in that connection isn't valid. Copy it again from your wallet."
    ParseProblem.BAD_ENCODING ->
        "Part of that connection is garbled. Copy it again from your wallet."
    ParseProblem.NO_RELAY ->
        "That connection doesn't name a relay, so there would be no way to reach the wallet."
    ParseProblem.TOO_MANY_RELAYS ->
        "That connection lists more relays than NWC Ring accepts."
    ParseProblem.INSECURE_RELAY ->
        "That connection uses an unencrypted relay (ws://). NWC Ring only accepts encrypted relays (wss://)."
    ParseProblem.BAD_RELAY ->
        "The relay address in that connection isn't valid."
    ParseProblem.NO_SECRET ->
        "That connection has no secret in it."
    ParseProblem.REPEATED_SECRET ->
        "That connection has more than one secret in it, which a wallet should never produce."
    ParseProblem.BAD_SECRET ->
        "The secret in that connection isn't valid. Copy it again from your wallet."
}

/** What the user is told after a PIN that did not open the vault. Never mentions the digits. */
fun PinResult.message(): String? = when (this) {
    PinResult.Accepted -> null
    is PinResult.Wrong -> {
        val left = KeyRing.FREE_TRIES - failures
        when {
            waitMs > 0 -> "Wrong PIN. Try again in ${formatWait(waitMs)}."
            left > 1 -> "Wrong PIN. $left more tries before NWC Ring makes you wait."
            left == 1 -> "Wrong PIN. One more try before NWC Ring makes you wait."
            else -> "Wrong PIN."
        }
    }
    is PinResult.Wait -> "Too many wrong PINs. Try again in ${formatWait(remainingMs)}."
    PinResult.NotSetUp -> "No PIN has been set yet."
    PinResult.KeyLost ->
        "This phone's secure chip no longer has NWC Ring's key, so the saved connections can't be " +
            "opened again. The only way forward is to start over."
    PinResult.Failed -> "Could not check the PIN. Try again."
}

fun formatWait(ms: Long): String {
    val seconds = (ms + 999) / 1000
    return when {
        seconds >= 3600 -> "about an hour"
        seconds >= 90 -> "${(seconds + 59) / 60} minutes"
        seconds > 60 -> "about a minute"
        seconds == 1L -> "1 second"
        else -> "$seconds seconds"
    }
}

fun keyStorageLine(storage: KeyStorage): String = when (storage) {
    KeyStorage.STRONGBOX -> "The vault key is tied to this phone's dedicated security chip."
    KeyStorage.TRUSTED_ENVIRONMENT -> "The vault key is tied to this phone's secure hardware."
    KeyStorage.SOFTWARE -> "Warning: this phone has no secure hardware, so the vault key is tied to it in software only."
    KeyStorage.UNKNOWN, KeyStorage.NOT_CREATED -> ""
}
