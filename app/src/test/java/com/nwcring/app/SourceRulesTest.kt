package com.nwcring.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Rules about the app's own source that must hold in every build. These read the files
 * under src/main, so breaking a rule fails the build rather than waiting for a review.
 */
class SourceRulesTest {
    private val main = File("src/main").also { check(it.isDirectory) { "run from the app module" } }
    private val sources = main.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
    private val manifest = File(main, "AndroidManifest.xml").readText()

    private fun offenders(pattern: Regex, except: Set<String> = emptySet()): List<String> =
        sources.filter { it.name !in except }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (pattern.containsMatchIn(line)) "${file.name}:${index + 1}" else null
            }
        }

    @Test fun thereIsSourceToCheck() {
        assertTrue(sources.size >= 10)
    }

    @Test fun onlyTheSafeLogWritesLogLines() {
        val direct = Regex("""android\.util\.Log|\bLog\.[a-z]+\(|\bprintln\(|\bprint\(|printStackTrace|System\.(out|err)|\bTimber\b""")
        assertEquals(emptyList<String>(), offenders(direct, except = setOf("SafeLog.kt")))
    }

    @Test fun nothingInTheAppCanAskAWalletToMoveMoney() {
        val spending = Regex(
            """pay_?invoice|pay_?keysend|make_?invoice|hold_?invoice|make_?offer|multi_?pay|sign_?message|""" +
                """create_?connection|PayMethod|ReceiveMethod|"pay"|"receive"""",
            RegexOption.IGNORE_CASE,
        )
        assertEquals(emptyList<String>(), offenders(spending))
    }

    @Test fun theAppHasNoNetworkAccessYet() {
        // Milestone 1 never talks to the network. Milestone 3 replaces this with a check
        // that connections are opened in exactly one place.
        assertFalse(manifest.contains("android.permission.INTERNET"))
        val networking = Regex("""java\.net\.(Socket|URL\b|HttpURLConnection)|okhttp|WebSocket|javax\.net""")
        assertEquals(emptyList<String>(), offenders(networking))
    }

    @Test fun theOnlyPermissionIsTheFingerprintPrompt() {
        val permissions = Regex("""<uses-permission[^>]*android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toList()
        assertEquals(listOf("android.permission.USE_BIOMETRIC"), permissions)
    }

    @Test fun backupsAndDeviceTransferAreOff() {
        assertTrue(manifest.contains("""android:allowBackup="false""""))
        assertTrue(manifest.contains("""android:fullBackupContent="false""""))
        assertTrue(manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""))
        val rules = File(main, "res/xml/data_extraction_rules.xml").readText()
        assertFalse(rules.contains("<include"))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val body = rules.substringAfter("<$section>").substringBefore("</$section>")
            for (domain in listOf("root", "file", "database", "sharedpref", "external")) {
                assertTrue("$section must exclude $domain", body.contains("""<exclude domain="$domain""""))
            }
        }
    }

    @Test fun onlyTheLauncherScreenIsOpenToOtherApps() {
        assertEquals(1, Regex("""android:exported="true"""").findAll(manifest).count())
        for (component in listOf("<service", "<receiver", "<provider", "<activity-alias")) {
            assertFalse(manifest.contains(component))
        }
        // No way for another app or a link to hand the app a connection string.
        assertFalse(manifest.contains("android.intent.action.VIEW"))
        assertFalse(manifest.contains("<data "))
    }

    @Test fun screenshotsAreBlockedForTheWholeWindow() {
        val activity = sources.single { it.name == "MainActivity.kt" }.readText()
        assertTrue(activity.contains("FLAG_SECURE"))
    }

    @Test fun theVaultFileLivesWhereBackupsNeverLook() {
        val app = sources.single { it.name == "NwcRingApp.kt" }.readText()
        assertTrue(app.contains("noBackupFilesDir"))
        assertEquals(emptyList<String>(), offenders(Regex("""getSharedPreferences|\bfilesDir\b|externalCacheDir|getExternalFilesDir""")))
    }
}
