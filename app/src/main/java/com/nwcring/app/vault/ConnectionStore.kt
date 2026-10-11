package com.nwcring.app.vault

import kotlinx.serialization.KSerializer
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

/** The saved file was written by an older version whose secrets this version cannot read. */
class OlderVaultFileException : Exception()

/**
 * One JSON file, read whole and written whole. A write goes to a temporary file first and
 * is then swapped in, so a crash mid-write cannot leave a half-written file.
 */
class JsonFile<T>(private val file: File, private val serializer: KSerializer<T>) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun exists(): Boolean = file.exists()

    /** Null when there is no file. Throws [UnreadableVaultFileException] when there is one it cannot read. */
    fun read(): T? {
        if (!file.exists()) return null
        return try {
            json.decodeFromString(serializer, file.readText(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw UnreadableVaultFileException()
        } catch (e: IllegalArgumentException) {
            throw UnreadableVaultFileException()
        } catch (e: IOException) {
            throw UnreadableVaultFileException()
        }
    }

    fun write(value: T) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(
            temp.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    fun delete(): Boolean = !file.exists() || file.delete()
}

/** Reads and writes the inventory file. */
class ConnectionStore(file: File) {
    private val json = JsonFile(file, VaultFile.serializer())

    fun load(): VaultFile {
        val parsed = json.read() ?: return VaultFile()
        if (parsed.version > VaultFile.CURRENT_VERSION) throw NewerVaultFileException()
        if (parsed.version < VaultFile.CURRENT_VERSION) throw OlderVaultFileException()
        return parsed
    }

    fun save(vault: VaultFile) = json.write(vault)

    fun delete(): Boolean = json.delete()
}

/** Reads and writes the key ring file. */
class KeyRingStore(file: File) {
    private val json = JsonFile(file, KeyRingFile.serializer())

    fun exists(): Boolean = json.exists()

    fun load(): KeyRingFile? {
        val parsed = json.read() ?: return null
        if (parsed.version > KeyRingFile.CURRENT_VERSION) throw NewerVaultFileException()
        return parsed
    }

    fun save(keyRing: KeyRingFile) = json.write(keyRing)

    fun delete(): Boolean = json.delete()
}
