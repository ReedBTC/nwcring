package com.nwcring.app.vault

import kotlinx.serialization.Serializable

/** An encrypted value: the random nonce used, and the ciphertext. Both base64. */
@Serializable
data class SealedBlob(val iv: String, val ct: String)

/**
 * One saved connection. Everything here except [sealed] is stored unencrypted in the app's
 * private storage; [sealed] holds the whole original connection string, encrypted under
 * the vault key.
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
        /** Version 1 (Milestone 1) encrypted each entry straight under a hardware key. */
        const val CURRENT_VERSION = 2
    }
}

/**
 * The vault key, kept in two locked copies. Neither copy is readable off this phone, and
 * neither is readable on it without the PIN or a fingerprint.
 */
@Serializable
data class KeyRingFile(
    val version: Int = CURRENT_VERSION,
    /** Base64. Random, chosen when the PIN was set. */
    val salt: String,
    /** How hard the PIN is stretched. Stored so it can be raised later without breaking old files. */
    val iterations: Int,
    /** The vault key, encrypted under the stretched PIN, then under the phone's device key. */
    val pinCopy: SealedBlob,
    /** The vault key encrypted under the phone's fingerprint-locked key, while that is switched on. */
    val biometricCopy: SealedBlob? = null,
    /** Wrong PINs in a row. Cleared by a right one. */
    val failures: Int = 0,
    /** Wall-clock time before which no PIN is accepted, or 0. */
    val lockedUntilWallMs: Long = 0,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}
