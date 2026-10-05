package com.nwcring.app.nwc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every connection string in this file is made up. None was issued by a wallet. */
class ConnectionStringParserTest {
    private val wallet = "b".repeat(64)
    private val secret = "a1".repeat(32)
    private val relay = "wss%3A%2F%2Frelay.example.com"
    private val good = "nostr+walletconnect://$wallet?relay=$relay&secret=$secret"

    private fun ok(input: String): ParsedConnection {
        val result = ConnectionStringParser.parse(input)
        assertTrue("expected a valid connection but got $result", result is ParseResult.Ok)
        return (result as ParseResult.Ok).connection
    }

    private fun problem(input: String): ParseProblem {
        val result = ConnectionStringParser.parse(input)
        assertTrue("expected a rejection", result is ParseResult.Invalid)
        return (result as ParseResult.Invalid).problem
    }

    // --- strings that should be accepted ---

    @Test fun acceptsTheStandardForm() {
        val parsed = ok(good)
        assertEquals(wallet, parsed.walletPubkey)
        assertEquals(listOf("wss://relay.example.com"), parsed.relays)
        assertEquals(secret, parsed.secret.reveal())
        assertNull(parsed.lud16)
    }

    @Test fun keepsTheOriginalExactlyForCopyingOut() {
        val messy = "nostr+walletconnect://${wallet.uppercase()}?secret=$secret&relay=$relay&extra=1"
        assertEquals(messy, ok("  $messy \n").original.reveal())
    }

    @Test fun acceptsTheSpecExample() {
        val example = "nostr+walletconnect://b889ff5b1513b641e2a139f661a661364979c5beee91842f8f0ef42ab558e9d4" +
            "?relay=wss%3A%2F%2Frelay.damus.io&secret=71a8c14c1407c113601079c4302dab36460f0ccd0ad506f1f2dc73b5100e4f3c"
        assertEquals(listOf("wss://relay.damus.io"), ok(example).relays)
    }

    @Test fun acceptsSeveralRelaysAndDropsRepeats() {
        val parsed = ok("$good&relay=wss%3A%2F%2Fother.example.org%3A4443%2Fnwc&relay=$relay")
        assertEquals(listOf("wss://relay.example.com", "wss://other.example.org:4443/nwc"), parsed.relays)
    }

    @Test fun acceptsARelayThatWasNotPercentEncoded() {
        assertEquals(listOf("wss://relay.example.com/v1"), ok("nostr+walletconnect://$wallet?relay=wss://relay.example.com/v1&secret=$secret").relays)
    }

    @Test fun readsTheLightningAddress() {
        assertEquals("reed@example.com", ok("$good&lud16=reed%40example.com").lud16)
        assertEquals("reed@example.com", ok("$good&lud16=reed@example.com").lud16)
    }

    @Test fun ignoresAMalformedLightningAddressRatherThanRejecting() {
        assertNull(ok("$good&lud16=not-an-address").lud16)
    }

    @Test fun acceptsUppercaseSchemeAndKeysAndLowercasesThem() {
        val parsed = ok("NOSTR+WALLETCONNECT://${wallet.uppercase()}?relay=WSS%3A%2F%2FRelay.Example.COM&secret=${secret.uppercase()}")
        assertEquals(wallet, parsed.walletPubkey)
        assertEquals(listOf("wss://relay.example.com"), parsed.relays)
        assertEquals(secret, parsed.secret.reveal())
    }

    @Test fun acceptsTheOldSchemeAndTheFormWithoutSlashes() {
        ok("nostrwalletconnect://$wallet?relay=$relay&secret=$secret")
        ok("nostr+walletconnect:$wallet?relay=$relay&secret=$secret")
    }

    @Test fun ignoresUnknownParametersAndAFragment() {
        ok("$good&name=whatever&budget=1000#section")
    }

    // --- strings that should be rejected, each for the right reason ---

    @Test fun rejectsNothing() {
        assertEquals(ParseProblem.EMPTY, problem(""))
        assertEquals(ParseProblem.EMPTY, problem("   \n\t "))
    }

    @Test fun rejectsThingsThatAreNotConnections() {
        for (input in listOf(
            "hello",
            "https://example.com/?secret=$secret",
            "nsec1" + "q".repeat(58),
            "npub1" + "q".repeat(58),
            "lnbc1" + "q".repeat(100),
            "nostr:$wallet",
            "bitcoin:bc1qexample",
            secret,
        )) {
            assertEquals(ParseProblem.NOT_A_CONNECTION, problem(input))
        }
    }

    @Test fun rejectsABadWalletKey() {
        assertEquals(ParseProblem.BAD_WALLET_KEY, problem("nostr+walletconnect://${"b".repeat(63)}?relay=$relay&secret=$secret"))
        assertEquals(ParseProblem.BAD_WALLET_KEY, problem("nostr+walletconnect://${"b".repeat(65)}?relay=$relay&secret=$secret"))
        assertEquals(ParseProblem.BAD_WALLET_KEY, problem("nostr+walletconnect://${"g".repeat(64)}?relay=$relay&secret=$secret"))
        assertEquals(ParseProblem.BAD_WALLET_KEY, problem("nostr+walletconnect://?relay=$relay&secret=$secret"))
        assertEquals(ParseProblem.BAD_WALLET_KEY, problem("nostr+walletconnect://user@$wallet?relay=$relay&secret=$secret"))
    }

    @Test fun rejectsAMissingRelay() {
        assertEquals(ParseProblem.NO_RELAY, problem("nostr+walletconnect://$wallet?secret=$secret"))
        assertEquals(ParseProblem.NO_RELAY, problem("nostr+walletconnect://$wallet"))
    }

    @Test fun rejectsAnUnencryptedRelay() {
        assertEquals(ParseProblem.INSECURE_RELAY, problem("nostr+walletconnect://$wallet?relay=ws%3A%2F%2Frelay.example.com&secret=$secret"))
        // One bad relay among good ones is still a rejection.
        assertEquals(ParseProblem.INSECURE_RELAY, problem("$good&relay=ws://relay.example.com"))
    }

    @Test fun rejectsRelaysThatAreNotRelayAddresses() {
        for (bad in listOf(
            "https%3A%2F%2Frelay.example.com",
            "relay.example.com",
            "wss%3A%2F%2F",
            "wss%3A%2F%2Fuser%3Apass%40relay.example.com",
            "wss%3A%2F%2Frelay..example.com",
            "wss%3A%2F%2Frelay_bad.example.com",
            "wss%3A%2F%2Frelay.example.com%3A99999",
            "wss%3A%2F%2Frelay.example.com%3A0",
            "javascript%3Aalert(1)",
            "file%3A%2F%2F%2Fetc%2Fpasswd",
            "",
        )) {
            assertEquals("relay=$bad", ParseProblem.BAD_RELAY, problem("nostr+walletconnect://$wallet?relay=$bad&secret=$secret"))
        }
    }

    @Test fun rejectsTooManyRelays() {
        val many = (1..9).joinToString("&") { "relay=wss%3A%2F%2Fr$it.example.com" }
        assertEquals(ParseProblem.TOO_MANY_RELAYS, problem("nostr+walletconnect://$wallet?$many&secret=$secret"))
    }

    @Test fun rejectsAMissingOrRepeatedOrMalformedSecret() {
        assertEquals(ParseProblem.NO_SECRET, problem("nostr+walletconnect://$wallet?relay=$relay"))
        assertEquals(ParseProblem.REPEATED_SECRET, problem("$good&secret=${"c".repeat(64)}"))
        assertEquals(ParseProblem.BAD_SECRET, problem("nostr+walletconnect://$wallet?relay=$relay&secret=${"a".repeat(63)}"))
        assertEquals(ParseProblem.BAD_SECRET, problem("nostr+walletconnect://$wallet?relay=$relay&secret=${"z".repeat(64)}"))
        assertEquals(ParseProblem.BAD_SECRET, problem("nostr+walletconnect://$wallet?relay=$relay&secret="))
        assertEquals(ParseProblem.BAD_SECRET, problem("nostr+walletconnect://$wallet?relay=$relay&secret=${"0".repeat(64)}"))
        assertEquals(ParseProblem.BAD_SECRET, problem("nostr+walletconnect://$wallet?relay=$relay&secret=nsec1" + "q".repeat(58)))
    }

    // --- hostile input ---

    @Test fun rejectsWhitespaceAndLineBreaksInsideTheString() {
        assertEquals(ParseProblem.HAS_WHITESPACE, problem(good.replace("&", "& ")))
        assertEquals(ParseProblem.HAS_WHITESPACE, problem(good.replace("&", "\n&")))
        assertEquals(ParseProblem.HAS_WHITESPACE, problem(good.replace("&", " &")))
    }

    @Test fun rejectsControlCharactersAndNonAscii() {
        assertEquals(ParseProblem.BAD_CHARACTERS, problem(good + "\u0000"))
        assertEquals(ParseProblem.BAD_CHARACTERS, problem(good + "\u0007"))
        assertEquals(ParseProblem.BAD_CHARACTERS, problem(good.replace("relay.example", "rеlay.example"))) // Cyrillic е
        assertEquals(ParseProblem.BAD_CHARACTERS, problem(good + "‮")) // right-to-left override
        assertEquals(ParseProblem.BAD_CHARACTERS, problem(good + "🔑")) // emoji
    }

    @Test fun rejectsControlCharactersSmuggledInByPercentEncoding() {
        assertEquals(ParseProblem.BAD_CHARACTERS, problem("$good&lud16=a%0Ab%40example.com"))
        assertEquals(ParseProblem.BAD_CHARACTERS, problem("$good&note=%00"))
        assertEquals(ParseProblem.BAD_RELAY, problem("nostr+walletconnect://$wallet?relay=wss%3A%2F%2Frelay.example.com%20evil&secret=$secret"))
    }

    @Test fun rejectsBrokenPercentEncoding() {
        assertEquals(ParseProblem.BAD_ENCODING, problem("$good&x=%"))
        assertEquals(ParseProblem.BAD_ENCODING, problem("$good&x=%2"))
        assertEquals(ParseProblem.BAD_ENCODING, problem("$good&x=%zz"))
        assertEquals(ParseProblem.BAD_ENCODING, problem("$good&x=%ff%fe")) // not valid UTF-8
        assertEquals(ParseProblem.BAD_ENCODING, problem("$good&x=%c0%af")) // overlong encoding
    }

    @Test fun aPercentEncodedSecretKeyNameStillCountsAsASecret() {
        // "%73ecret" decodes to "secret": two secrets, so it is rejected as ambiguous.
        assertEquals(ParseProblem.REPEATED_SECRET, problem("$good&%73ecret=${"c".repeat(64)}"))
    }

    @Test fun rejectsOversizedInputWithoutChoking() {
        assertEquals(ParseProblem.TOO_LONG, problem(good + "&x=" + "a".repeat(5000)))
        assertEquals(ParseProblem.TOO_LONG, problem("a".repeat(5_000_000)))
    }

    @Test fun survivesManyEmptyParameters() {
        ok("nostr+walletconnect://$wallet?&&&relay=$relay&&&secret=$secret&&&=&=&")
    }

    @Test fun neverThrowsOnArbitraryInput() {
        val random = java.util.Random(47)
        val alphabet = "nostr+walletconnect:/?&=%#@.[]0123456789abcdefABCDEF \n\u0000éwss"
        repeat(20_000) {
            val length = random.nextInt(200)
            val text = buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
            ConnectionStringParser.parse(text)
            ConnectionStringParser.parse("nostr+walletconnect://$wallet?$text")
            ConnectionStringParser.parse("$good&relay=$text")
        }
    }

    // --- nothing about a rejected or accepted string leaks through printing ---

    @Test fun printingAParsedConnectionDoesNotShowTheSecret() {
        val printed = ok(good).toString()
        assertFalse(printed.contains(secret))
        assertFalse(printed.contains("walletconnect"))
        assertTrue(printed.contains("[secret]"))
    }

    @Test fun aRejectionCarriesNoneOfTheInput() {
        val result = ConnectionStringParser.parse("nostr+walletconnect://$wallet?relay=ws://relay.example.com&secret=$secret")
        val printed = result.toString()
        assertFalse(printed.contains(secret))
        assertFalse(printed.contains(wallet))
        assertFalse(printed.contains("relay.example.com"))
    }
}
