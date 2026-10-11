package com.nwcring.app.vault

import com.nwcring.app.nwc.ConnectionStringParser
import com.nwcring.app.nwc.ParseResult
import com.nwcring.app.nwc.ParsedConnection
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Every connection string in this file is made up. */
class VaultTest {
    @get:Rule val folder = TemporaryFolder()

    private val secret = "a1".repeat(32)
    private val text = "nostr+walletconnect://${"b".repeat(64)}?relay=wss%3A%2F%2Frelay.example.com&secret=$secret"
    private val parsed: ParsedConnection = (ConnectionStringParser.parse(text) as ParseResult.Ok).connection

    /** The real session cipher with a key this test controls; null means the app is locked. */
    private var key: ByteArray? = ByteArray(32) { it.toByte() }
    private val cipher = SessionCipher(key = { key })
    private val scope = TestScope()
    private lateinit var file: File

    private fun vault(): Vault {
        if (!::file.isInitialized) file = File(folder.root, "vault.json")
        var counter = 0
        return Vault(
            store = ConnectionStore(file),
            cipher = cipher,
            nowMs = { 1_760_000_000_000 },
            newId = { "id-${++counter}-${System.nanoTime()}" },
            io = StandardTestDispatcher(scope.testScheduler),
        )
    }

    @Test fun startsEmpty() = scope.runTest {
        val vault = vault()
        vault.load()
        assertEquals(VaultState.READY, vault.state.value)
        assertEquals(emptyList<StoredConnection>(), vault.connections.value)
    }

    @Test fun savesAConnectionAndFindsItAgainAfterARestart() = scope.runTest {
        val first = vault()
        first.load()
        val result = first.add(parsed, "  Nostr zaps ", " Zapping ", " Alby Hub ")
        assertTrue(result is AddResult.Saved)

        val second = vault()
        second.load()
        val saved = second.connections.value.single()
        assertEquals("Nostr zaps", saved.name)
        assertEquals("Zapping", saved.purpose)
        assertEquals("Alby Hub", saved.walletLabel)
        assertEquals("b".repeat(64), saved.walletPubkey)
        assertEquals(listOf("wss://relay.example.com"), saved.relays)
        assertEquals(1_760_000_000_000, saved.addedAtMs)
        // What comes back out of the vault is exactly what went in.
        assertEquals(text, String(cipher.open(saved.sealed, saved.id)))
    }

    @Test fun theFileOnDiskNeverContainsTheSecretOrTheConnectionString() = scope.runTest {
        val vault = vault()
        vault.load()
        vault.add(parsed, "Podcasting", "", "")
        val onDisk = file.readText()
        assertFalse(onDisk.contains(secret))
        assertFalse(onDisk.contains("walletconnect"))
        assertFalse(onDisk.contains("secret="))
        assertTrue(onDisk.contains("Podcasting"))
    }

    @Test fun rejectsAMissingOrOversizedName() = scope.runTest {
        val vault = vault()
        vault.load()
        assertEquals(AddResult.InvalidName, vault.add(parsed, "   ", "", ""))
        assertEquals(AddResult.InvalidName, vault.add(parsed, "x".repeat(Vault.MAX_NAME + 1), "", ""))
        assertEquals(0, vault.connections.value.size)
    }

    @Test fun refusesToSaveWhileLocked() = scope.runTest {
        val vault = vault()
        vault.load()
        key = null
        assertEquals(AddResult.Locked, vault.add(parsed, "Zaps", "", ""))
        assertEquals(0, vault.connections.value.size)
        assertFalse(file.exists())
    }

    @Test fun revealsExactlyWhatWasSavedAndOnlyWhileUnlocked() = scope.runTest {
        val vault = vault()
        vault.load()
        val id = (vault.add(parsed, "Zaps", "", "") as AddResult.Saved).id
        val revealed = vault.reveal(id) as RevealResult.Revealed
        assertEquals(text, revealed.text.reveal())
        assertEquals("[secret]", revealed.text.toString())
        assertEquals(RevealResult.NotFound, vault.reveal("no-such-id"))
        key = null
        assertEquals(RevealResult.Locked, vault.reveal(id))
        key = ByteArray(32) { 99 }
        assertEquals(RevealResult.Unreadable, vault.reveal(id))
    }

    @Test fun editsTheWordsAroundAConnectionWithoutTouchingTheSecret() = scope.runTest {
        val vault = vault()
        vault.load()
        val id = (vault.add(parsed, "Zaps", "", "") as AddResult.Saved).id
        val before = vault.connections.value.single().sealed
        assertEquals(EditResult.Done, vault.edit(id, " Nostr zaps ", "all the clients", "Alby Hub"))
        assertEquals(EditResult.InvalidName, vault.edit(id, " ", "", ""))
        assertEquals(EditResult.NotFound, vault.edit("no-such-id", "x", "", ""))

        val reloaded = vault()
        reloaded.load()
        val saved = reloaded.connections.value.single()
        assertEquals("Nostr zaps", saved.name)
        assertEquals("all the clients", saved.purpose)
        assertEquals("Alby Hub", saved.walletLabel)
        assertEquals(before, saved.sealed)
    }

    @Test fun keepsTheUsedInListTidy() = scope.runTest {
        val vault = vault()
        vault.load()
        val id = (vault.add(parsed, "Zaps", "", "") as AddResult.Saved).id
        assertEquals(EditResult.Done, vault.setUsedIn(id, listOf(" Amethyst ", "", "Primal", "Amethyst")))
        assertEquals(listOf("Amethyst", "Primal"), vault.connections.value.single().usedIn)
        assertEquals(EditResult.InvalidLabel, vault.setUsedIn(id, listOf("x".repeat(Vault.MAX_LABEL + 1))))
        assertEquals(EditResult.InvalidLabel, vault.setUsedIn(id, List(Vault.MAX_USED_IN + 1) { "app $it" }))
        assertEquals(EditResult.Done, vault.setUsedIn(id, emptyList()))

        val reloaded = vault()
        reloaded.load()
        assertEquals(emptyList<String>(), reloaded.connections.value.single().usedIn)
    }

    @Test fun aFileFromTheMilestoneOneTestBuildIsReportedAndCanBeWiped() = scope.runTest {
        file = File(folder.root, "vault.json")
        val older = """{"version":1,"connections":[]}"""
        file.writeText(older)
        val vault = vault()
        vault.load()
        assertEquals(VaultState.FILE_FROM_OLDER_VERSION, vault.state.value)
        assertEquals(AddResult.Failed, vault.add(parsed, "Zaps", "", ""))
        assertEquals(older, file.readText())
        assertTrue(vault.wipe())
        assertFalse(file.exists())
        assertEquals(VaultState.READY, vault.state.value)
        assertTrue(vault.add(parsed, "Zaps", "", "") is AddResult.Saved)
    }

    @Test fun deletesOnlyTheChosenConnection() = scope.runTest {
        val vault = vault()
        vault.load()
        vault.add(parsed, "One", "", "")
        vault.add(parsed, "Two", "", "")
        val one = vault.connections.value.first { it.name == "One" }
        assertTrue(vault.delete(one.id))
        assertFalse(vault.delete("no-such-id"))

        val reloaded = vault()
        reloaded.load()
        assertEquals(listOf("Two"), reloaded.connections.value.map { it.name })
    }

    @Test fun aDamagedFileIsReportedAndLeftAlone() = scope.runTest {
        file = File(folder.root, "vault.json")
        file.writeText("{ this is not the file we wrote")
        val vault = vault()
        vault.load()
        assertEquals(VaultState.FILE_UNREADABLE, vault.state.value)
        assertEquals(AddResult.Failed, vault.add(parsed, "Zaps", "", ""))
        assertEquals("{ this is not the file we wrote", file.readText())
    }

    @Test fun aFileFromANewerVersionIsReportedAndLeftAlone() = scope.runTest {
        file = File(folder.root, "vault.json")
        val newer = """{"version":99,"connections":[],"somethingNew":true}"""
        file.writeText(newer)
        val vault = vault()
        vault.load()
        assertEquals(VaultState.FILE_FROM_NEWER_VERSION, vault.state.value)
        assertFalse(vault.delete("anything"))
        assertEquals(newer, file.readText())
    }

    @Test fun savingLeavesNoTemporaryFileBehind() = scope.runTest {
        val vault = vault()
        vault.load()
        vault.add(parsed, "Zaps", "", "")
        assertEquals(listOf("vault.json"), folder.root.list()!!.toList())
    }
}
