package com.nwcring.app.vault

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The saved file exists but cannot be read. It is left in place, never overwritten. */
class UnreadableVaultFileException : Exception()

/** The saved file was written by a newer version of the app. It is left in place. */
class NewerVaultFileException : Exception()

/**
 * Reads and writes the inventory file. A write goes to a temporary file first and is then
 * swapped in, so a crash mid-write cannot leave a half-written inventory.
 */
class ConnectionStore(private val file: File) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): VaultFile {
        if (!file.exists()) return VaultFile()
        val parsed = try {
            json.decodeFromString(VaultFile.serializer(), file.readText(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw UnreadableVaultFileException()
        } catch (e: IllegalArgumentException) {
            throw UnreadableVaultFileException()
        } catch (e: IOException) {
            throw UnreadableVaultFileException()
        }
        if (parsed.version > VaultFile.CURRENT_VERSION) throw NewerVaultFileException()
        return parsed
    }

    fun save(vault: VaultFile) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(json.encodeToString(VaultFile.serializer(), vault).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(
            temp.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}
