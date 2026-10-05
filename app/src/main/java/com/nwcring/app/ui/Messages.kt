package com.nwcring.app.ui

import com.nwcring.app.nwc.ParseProblem

/** What the user is told when a pasted connection is rejected. None of these repeat the input. */
fun ParseProblem.message(): String = when (this) {
    ParseProblem.EMPTY ->
        "The clipboard is empty. Copy the connection string in your wallet first."
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
