package com.nwcring.app.vault

import kotlinx.serialization.Serializable

/** An encrypted value: the random nonce the hardware chose, and the ciphertext. Both base64. */
@Serializable
data class SealedBlob(val iv: String, val ct: String)

/**
 * One saved connection. Everything here except [sealed] is stored unencrypted in the app's
 * private storage; [sealed] holds the whole original connection string, encrypted.
 */
@Serializable
data class StoredConnection(
    val id: String,
    val name: String,
    val purpose: String = "",
    val walletLabel: String = "",
    val walletPubkey: String,
    val relays: List<String>,
    val lud16: String? = null,
    val usedIn: List<String> = emptyList(),
    val addedAtMs: Long,
    val sealed: SealedBlob,
)

@Serializable
data class VaultFile(
    val version: Int = CURRENT_VERSION,
    val connections: List<StoredConnection> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}
