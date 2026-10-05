package com.nwcring.app.vault

import com.nwcring.app.nwc.ParsedConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

enum class VaultState { LOADING, READY, FILE_UNREADABLE, FILE_FROM_NEWER_VERSION }

sealed interface AddResult {
    data class Saved(val id: String) : AddResult

    /** Ask the user to authenticate, then try again. */
    data object NeedsAuth : AddResult

    data object NoScreenLock : AddResult

    data object KeyLost : AddResult

    data object InvalidName : AddResult

    data object Failed : AddResult
}

/**
 * The inventory of saved connections. The list of names and labels is always available;
 * the secrets inside it are only ever handled through [cipher].
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
                note("connection_saved", listOf("count" to updated.size, "key_storage" to cipher.keyStorage()))
                AddResult.Saved(id)
            } catch (e: AuthRequiredException) {
                note("save_waiting_for_unlock", emptyList())
                AddResult.NeedsAuth
            } catch (e: NoScreenLockException) {
                AddResult.NoScreenLock
            } catch (e: KeyLostException) {
                AddResult.KeyLost
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

    /**
     * Replaces a destroyed key with a new one. Secrets sealed with the old key stay listed
     * but can never be read again.
     */
    suspend fun replaceLostKey() = withContext(io) {
        mutex.withLock { cipher.destroyKey() }
    }

    fun keyStorage(): KeyStorage = cipher.keyStorage()

    companion object {
        const val MAX_NAME = 60
        const val MAX_PURPOSE = 200
    }
}
