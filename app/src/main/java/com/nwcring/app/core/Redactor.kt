package com.nwcring.app.core

/**
 * Decides what is allowed into a log line. Anything that could be, or could contain, a
 * secret or a connection string is replaced wholesale rather than trimmed.
 */
object Redactor {
    const val BLOCKED = "[blocked]"
    private const val MAX_LENGTH = 120
    private val longHex = Regex("[0-9a-fA-F]{32,}")
    private val markers = listOf("walletconnect", "secret", "nsec1", "relay=")

    fun scrub(text: String): String {
        val lower = text.lowercase()
        if (markers.any { it in lower }) return BLOCKED
        if (longHex.containsMatchIn(text)) return BLOCKED
        if (text.any { it.isISOControl() }) return BLOCKED
        return if (text.length > MAX_LENGTH) text.take(MAX_LENGTH) + "…" else text
    }

    fun render(name: String, facts: List<Pair<String, Any?>>): String {
        val builder = StringBuilder(scrub(name))
        for ((key, value) in facts) {
            builder.append(' ').append(scrub(key)).append('=').append(scrub(value.toString()))
        }
        return builder.toString()
    }
}
