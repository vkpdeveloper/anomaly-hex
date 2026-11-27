package anomaly.hex.android

/** Tailscale gives each device an address in 100.64.0.0/10, and MagicDNS names end in .ts.net. */
fun routeKind(host: String): RouteKind {
    if (host.endsWith(".ts.net", ignoreCase = true)) return RouteKind.TAILSCALE
    val octets = host.split('.').mapNotNull { it.toIntOrNull() }
    val tailscale = octets.size == 4 && octets[0] == 100 && octets[1] and 0xC0 == 64
    return if (tailscale) RouteKind.TAILSCALE else RouteKind.LAN
}

private const val CODE_LENGTH = 12

/** The pairing code the way Hex reads it: Crockford base32, O as 0, I and L as 1. */
fun normalizeCode(typed: String): String = typed.uppercase()
    .filter { it.isLetterOrDigit() }
    .map {
        when (it) {
            'O' -> '0'
            'I', 'L' -> '1'
            else -> it
        }
    }
    .joinToString("")
    .take(CODE_LENGTH)

fun isCompleteCode(code: String): Boolean = normalizeCode(code).length == CODE_LENGTH

/** XXXX-XXXX-XXXX, as the computer shows it. */
fun displayCode(code: String): String = normalizeCode(code).chunked(4).joinToString("-")

/** `host` or `host:port`, with the port defaulting to Hex's. */
fun parseAddress(typed: String, defaultPort: Int): Pair<String, Int>? {
    val text = typed.trim().removePrefix("http://").trimEnd('/')
    if (text.isEmpty() || text.any { it.isWhitespace() || it == '/' }) return null
    val colon = text.lastIndexOf(':')
    if (colon < 0) return text to defaultPort
    val port = text.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    val host = text.substring(0, colon)
    return if (host.isEmpty()) null else host to port
}

/**
 * The text to put at the cursor: a space before it when it follows a word,
 * so a second dictation doesn't run into the first.
 */
fun joinAtCursor(before: CharSequence, transcript: String): String {
    val previous = before.lastOrNull() ?: return transcript
    val first = transcript.firstOrNull() ?: return transcript
    val needsSpace = !previous.isWhitespace() && previous !in "([{\"'" &&
        !first.isWhitespace() && first !in ".,;:!?)]}"
    return if (needsSpace) " $transcript" else transcript
}

/**
 * What a field really holds. An empty field reports its placeholder
 * ("Message") as its text; most apps flag that, but some don't, so text
 * equal to the hint with no cursor past the start counts as empty too.
 */
fun fieldText(text: CharSequence?, hint: CharSequence?, showingHint: Boolean, cursor: Int): String {
    val current = text?.toString().orEmpty()
    if (showingHint) return ""
    val placeholder = !hint.isNullOrEmpty() && current == hint.toString() && cursor <= 0
    return if (placeholder) "" else current
}
