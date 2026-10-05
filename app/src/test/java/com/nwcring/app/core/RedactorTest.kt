package com.nwcring.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RedactorTest {
    private val secret = "a1".repeat(32)

    @Test fun blocksAnythingThatLooksLikeAKeyOrSecret() {
        assertEquals(Redactor.BLOCKED, Redactor.scrub(secret))
        assertEquals(Redactor.BLOCKED, Redactor.scrub("value was $secret here"))
        assertEquals(Redactor.BLOCKED, Redactor.scrub(secret.uppercase()))
        assertEquals(Redactor.BLOCKED, Redactor.scrub("c".repeat(32)))
    }

    @Test fun blocksConnectionStringsEvenWhenTruncated() {
        assertEquals(Redactor.BLOCKED, Redactor.scrub("nostr+walletconnect://abc"))
        assertEquals(Redactor.BLOCKED, Redactor.scrub("NOSTR+WALLETCONNECT://abc"))
        assertEquals(Redactor.BLOCKED, Redactor.scrub("?relay=wss://x&secret=12"))
        assertEquals(Redactor.BLOCKED, Redactor.scrub("nsec1qqqq"))
    }

    @Test fun blocksControlCharacters() {
        assertEquals(Redactor.BLOCKED, Redactor.scrub("line one\nline two"))
    }

    @Test fun letsOrdinaryFactsThrough() {
        assertEquals("vault_loaded count=3", Redactor.render("vault_loaded", listOf("count" to 3)))
        assertEquals("key_created storage=STRONGBOX", Redactor.render("key_created", listOf("storage" to "STRONGBOX")))
    }

    @Test fun aSecretPassedAsAFactNeverReachesTheLine() {
        val line = Redactor.render("saved", listOf("value" to secret, "uri" to "nostr+walletconnect://x?secret=$secret"))
        assertFalse(line.contains(secret))
        assertEquals("saved value=[blocked] uri=[blocked]", line)
    }

    @Test fun cutsLongText() {
        assertEquals(121, Redactor.scrub("word ".repeat(100)).length)
    }
}
