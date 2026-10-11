package com.nwcring.app.vault

import com.nwcring.app.nwc.ParsedConnection
import com.nwcring.app.nwc.SecretText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

enum class VaultState { LOADING, READY, FILE_UNREADABLE, FILE_FROM_NEWER_VERSION, FILE_FROM_OLDER_VERSION }

sealed interface AddResult {
    data class Saved(val id: String) : AddResult

    data object Locked : AddResult

    data object InvalidName : AddResult

    data object Failed : AddResult
}

sealed interface EditResult {
    data object Done : EditResult

    data object InvalidName : EditResult

    data object InvalidLabel : EditResult

    data object NotFound : EditResult

    data object Failed : EditResult
}

sealed interface RevealResult {
    class Revealed(val text: SecretText) : RevealResult

    data object Locked : RevealResult

    data object Unreadable : RevealResult

    data object NotFound : RevealResult
}

/**
 * The inventory of saved connections. The list of names and labels is always available;
 * the secrets inside it are only ever handled through [cipher], which works only while
 * the app is unlocked.
 */
class Vault(
    private val store: ConnectionStore,
    private val cipher: SecretCipher,
    private val nowMs: () -> Long,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Receives plain facts for the log (counts and outcomes), never anything from a connection. */
    private val note: (event: String, facts: List<Pair<String, Any?>>) -> Unit = { _, _ -> },
) {
    private val mutex = Mutex()
    private val _connections = MutableStateFlow<List<StoredConnection>>(emptyList())
    private val _state = MutableStateFlow(VaultState.LOADING)

    val connections: StateFlow<List<StoredConnection>> = _connections
    val state: StateFlow<VaultState> = _state

    suspend fun load() = withContext(io) {
        mutex.withLock {
            try {
                _connections.value = store.load().connections
                _state.value = VaultState.READY
                note("vault_loaded", listOf("count" to _connections.value.size))
            } catch (e: UnreadableVaultFileException) {
                _state.value = VaultState.FILE_UNREADABLE
            } catch (e: NewerVaultFileException) {
                _state.value = VaultState.FILE_FROM_NEWER_VERSION
            } catch (e: OlderVaultFileException) {
                _state.value = VaultState.FILE_FROM_OLDER_VERSION
            }
        }
    }

    suspend fun add(
        parsed: ParsedConnection,
        name: String,
        purpose: String,
        walletLabel: String,
    ): AddResult = withContext(io) {
        val cleanName = name.trim()
        if (cleanName.isEmpty() || cleanName.length > MAX_NAME) return@withContext AddResult.InvalidName
        mutex.withLock {
            if (_state.value != VaultState.READY) return@withLock AddResult.Failed
            val id = newId()
            val plain = parsed.original.reveal().toByteArray(Charsets.UTF_8)
            var readBack: ByteArray? = null
            try {
                val sealed = cipher.seal(plain, id)
                // Prove the stored form can be read back before telling anyone it is saved.
                readBack = cipher.open(sealed, id)
                if (!readBack.contentEquals(plain)) return@withLock AddResult.Failed
                val entry = StoredConnection(
                    id = id,
                    name = cleanName,
                    purpose = purpose.trim().take(MAX_PURPOSE),
                    walletLabel = walletLabel.trim().take(MAX_NAME),
                    walletPubkey = parsed.walletPubkey,
                    relays = parsed.relays,
                    lud16 = parsed.lud16,
                    addedAtMs = nowMs(),
                    sealed = sealed,
                )
                val updated = _connections.value + entry
                store.save(VaultFile(connections = updated))
                _connections.value = updated
                note("connection_saved", listOf("count" to updated.size))
                AddResult.Saved(id)
            } catch (e: VaultLockedException) {
                AddResult.Locked
            } catch (e: UnreadableSecretException) {
                AddResult.Failed
            } catch (e: IOException) {
                AddResult.Failed
            } finally {
                plain.fill(0)
                readBack?.fill(0)
            }
        }
    }

    /** Changes the words around a connection. The secret is not touched. */
    suspend fun edit(id: String, name: String, purpose: String, walletLabel: String): EditResult = withContext(io) {
        val cleanName = name.trim()
        if (cleanName.isEmpty() || cleanName.length > MAX_NAME) return@withContext EditResult.InvalidName
        change(id) { it.copy(name = cleanName, purpose = purpose.trim().take(MAX_PURPOSE), walletLabel = walletLabel.trim().take(MAX_NAME)) }
    }

    /** Replaces the "used in" list: where the user has pasted this connection. */
    suspend fun setUsedIn(id: String, labels: List<String>): EditResult = withContext(io) {
        val clean = labels.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (clean.size > MAX_USED_IN || clean.any { it.length > MAX_LABEL }) return@withContext EditResult.InvalidLabel
        change(id) { it.copy(usedIn = clean) }
    }

    private suspend fun change(id: String, update: (StoredConnection) -> StoredConnection): EditResult =
        mutex.withLock {
            if (_state.value != VaultState.READY) return@withLock EditResult.Failed
            val index = _connections.value.indexOfFirst { it.id == id }
            if (index < 0) return@withLock EditResult.NotFound
            val updated = _connections.value.toMutableList().also { it[index] = update(it[index]) }
            try {
                store.save(VaultFile(connections = updated))
                _connections.value = updated
                EditResult.Done
            } catch (e: IOException) {
                EditResult.Failed
            }
        }

    /** Decrypts one connection string. The caller shows or copies it and then lets it go. */
    suspend fun reveal(id: String): RevealResult = withContext(io) {
        mutex.withLock {
            val entry = _connections.value.firstOrNull { it.id == id } ?: return@withLock RevealResult.NotFound
            try {
                val plain = cipher.open(entry.sealed, entry.id)
                val text = String(plain, Charsets.UTF_8)
                plain.fill(0)
                note("revealed", emptyList())
                RevealResult.Revealed(SecretText(text))
            } catch (e: VaultLockedException) {
                RevealResult.Locked
            } catch (e: UnreadableSecretException) {
                RevealResult.Unreadable
            }
        }
    }

    /** Removes the entry from this app only. It does not revoke anything at the wallet. */
    suspend fun delete(id: String): Boolean = withContext(io) {
        mutex.withLock {
            if (_state.value != VaultState.READY) return@withLock false
            val updated = _connections.value.filterNot { it.id == id }
            if (updated.size == _connections.value.size) return@withLock false
            try {
                store.save(VaultFile(connections = updated))
                _connections.value = updated
                note("connection_deleted", listOf("count" to updated.size))
                true
            } catch (e: IOException) {
                false
            }
        }
    }

    /** Deletes every connection and the file. Used when starting over. */
    suspend fun wipe(): Boolean = withContext(io) {
        mutex.withLock {
            val gone = store.delete()
            if (gone) {
                _connections.value = emptyList()
                _state.value = VaultState.READY
                note("vault_wiped", emptyList())
            }
            gone
        }
    }

    companion object {
        const val MAX_NAME = 60
        const val MAX_PURPOSE = 200
        const val MAX_LABEL = 40
        const val MAX_USED_IN = 30
    }
}
