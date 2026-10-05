package com.nwcring.app.nwc

import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Wraps text that must never be printed. Printing it shows a placeholder instead. */
class SecretText(private val value: String) {
    fun reveal(): String = value

    override fun toString(): String = "[secret]"
}

/** What a connection string says, split into the public parts and the secret part. */
data class ParsedConnection(
    val walletPubkey: String,
    val relays: List<String>,
    val lud16: String?,
    val secret: SecretText,
    /** The string exactly as pasted (trimmed), so copying out returns what the wallet issued. */
    val original: SecretText,
)

enum class ParseProblem {
    EMPTY,
    TOO_LONG,
    HAS_WHITESPACE,
    BAD_CHARACTERS,
    NOT_A_CONNECTION,
    BAD_WALLET_KEY,
    BAD_ENCODING,
    NO_RELAY,
    TOO_MANY_RELAYS,
    INSECURE_RELAY,
    BAD_RELAY,
    NO_SECRET,
    REPEATED_SECRET,
    BAD_SECRET,
}

sealed interface ParseResult {
    data class Ok(val connection: ParsedConnection) : ParseResult

    /** Carries only the kind of problem, never any of the input. */
    data class Invalid(val problem: ParseProblem) : ParseResult
}

/**
 * Reads a Nostr Wallet Connect connection string:
 * `nostr+walletconnect://<wallet pubkey>?relay=<wss url>&secret=<64 hex>&lud16=<optional>`.
 * Input is treated as hostile. Nothing here logs, and no error carries the input.
 */
object ConnectionStringParser {
    const val MAX_LENGTH = 4096
    const val MAX_RELAYS = 8

    private val schemes = listOf("nostr+walletconnect:", "nostrwalletconnect:")
    private val hex64 = Regex("[0-9a-fA-F]{64}")
    private val hostName = Regex("[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?")
    private val lightningAddress = Regex("[^@\\s]{1,64}@[^@\\s]{1,255}")

    fun parse(input: String): ParseResult {
        val text = input.trim()
        if (text.isEmpty()) return invalid(ParseProblem.EMPTY)
        if (text.length > MAX_LENGTH) return invalid(ParseProblem.TOO_LONG)
        if (text.any { it.isWhitespace() }) return invalid(ParseProblem.HAS_WHITESPACE)
        if (text.any { it.code < 0x21 || it.code > 0x7E }) return invalid(ParseProblem.BAD_CHARACTERS)

        val scheme = schemes.firstOrNull { text.startsWith(it, ignoreCase = true) }
            ?: return invalid(ParseProblem.NOT_A_CONNECTION)
        val body = text.substring(scheme.length).removePrefix("//").substringBefore('#')
        val questionMark = body.indexOf('?')
        val authority = (if (questionMark >= 0) body.substring(0, questionMark) else body).trimEnd('/')
        val query = if (questionMark >= 0) body.substring(questionMark + 1) else ""

        if (!hex64.matches(authority)) return invalid(ParseProblem.BAD_WALLET_KEY)

        val relayValues = mutableListOf<String>()
        val secretValues = mutableListOf<String>()
        var lud16: String? = null
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val key = percentDecode(part.substringBefore('=')) ?: return invalid(ParseProblem.BAD_ENCODING)
            val value = percentDecode(part.substringAfter('=', "")) ?: return invalid(ParseProblem.BAD_ENCODING)
            if (value.any { it.isISOControl() }) return invalid(ParseProblem.BAD_CHARACTERS)
            when (key) {
                "relay" -> relayValues += value
                "secret" -> secretValues += value
                "lud16" -> if (lud16 == null && lightningAddress.matches(value)) lud16 = value
            }
        }

        if (relayValues.isEmpty()) return invalid(ParseProblem.NO_RELAY)
        val relays = LinkedHashSet<String>()
        for (raw in relayValues) {
            when (val relay = normalizeRelay(raw)) {
                is RelayResult.Ok -> relays += relay.url
                is RelayResult.Bad -> return invalid(relay.problem)
            }
        }
        if (relays.size > MAX_RELAYS) return invalid(ParseProblem.TOO_MANY_RELAYS)

        if (secretValues.isEmpty()) return invalid(ParseProblem.NO_SECRET)
        if (secretValues.size > 1) return invalid(ParseProblem.REPEATED_SECRET)
        val secret = secretValues.single()
        if (!hex64.matches(secret) || secret.all { it == '0' }) return invalid(ParseProblem.BAD_SECRET)

        return ParseResult.Ok(
            ParsedConnection(
                walletPubkey = authority.lowercase(),
                relays = relays.toList(),
                lud16 = lud16,
                secret = SecretText(secret.lowercase()),
                original = SecretText(text),
            ),
        )
    }

    private fun invalid(problem: ParseProblem) = ParseResult.Invalid(problem)

    private sealed interface RelayResult {
        data class Ok(val url: String) : RelayResult

        data class Bad(val problem: ParseProblem) : RelayResult
    }

    private fun normalizeRelay(raw: String): RelayResult {
        val bad = RelayResult.Bad(ParseProblem.BAD_RELAY)
        if (raw.length > 512 || raw.any { it.code < 0x21 || it.code > 0x7E }) return bad
        val uri = try {
            URI(raw)
        } catch (e: URISyntaxException) {
            return bad
        }
        when (uri.scheme?.lowercase()) {
            "wss" -> Unit
            "ws" -> return RelayResult.Bad(ParseProblem.INSECURE_RELAY)
            else -> return bad
        }
        if (uri.rawUserInfo != null) return bad
        val host = uri.host?.lowercase() ?: return bad
        val isIpv6 = host.startsWith("[") && host.endsWith("]")
        if (!isIpv6 && (!hostName.matches(host) || ".." in host)) return bad
        val port = uri.port
        if (port == 0 || port > 65535) return bad
        val path = uri.rawPath.orEmpty().let { if (it == "/") "" else it }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val portPart = if (port == -1) "" else ":$port"
        return RelayResult.Ok("wss://$host$portPart$path$query")
    }

    /** Strict percent-decoding: malformed escapes and invalid UTF-8 are rejected, not repaired. */
    private fun percentDecode(text: String): String? {
        val bytes = ByteArray(text.length)
        var length = 0
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '%') {
                if (index + 2 >= text.length) return null
                val high = Character.digit(text[index + 1], 16)
                val low = Character.digit(text[index + 2], 16)
                if (high < 0 || low < 0) return null
                bytes[length++] = ((high shl 4) or low).toByte()
                index += 3
            } else {
                if (char.code > 0x7E) return null
                bytes[length++] = char.code.toByte()
                index++
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, 0, length)).toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }
}
