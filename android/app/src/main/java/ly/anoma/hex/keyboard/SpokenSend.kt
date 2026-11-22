package ly.anoma.hex.keyboard

/** Say "send it" to send (Settings › Keyboard, off by default): a dictation whose last words are
 * "send it" (or Hinglish's "bhej do", "भेज दो") drops them and presses the field's send key. Read on
 * the phone from the text the computer sent back, so a computer of any version works, and a rewrite
 * that changes the words sends nothing.
 *
 * Conservative: the phrase must be the whole dictation or follow the end of a sentence or a pause the
 * text marks (". send it", ", send it", "- send it"), so "I'll send it" and "can you send it" stay as
 * text. "send" alone never sends. */
internal object SpokenSend {
    private val phrases = listOf("send it", "bhej do", "भेज दो")
    // After the phrase: its own sentence's end. Before it: a sentence's end, never "…", which trails off.
    private const val CLOSING = ".!?…"
    private const val SENTENCE_END = ".!?"
    // Marks a pause before the phrase; dropped with it, as nothing follows them now.
    private const val JOINING = ",;:-\u2013\u2014"

    /** The text to type before sending, "" for nothing, or null when [text] doesn't end by asking to send. */
    fun strip(text: String): String? {
        val words = text.trimEnd().trimEnd { it in CLOSING }.trimEnd()
        val phrase = phrases.firstOrNull { words.endsWith(it, ignoreCase = true) } ?: return null
        val before = words.dropLast(phrase.length)
        if (before.isNotEmpty() && !before.last().isWhitespace() && before.last() !in JOINING) return null
        val body = before.trimEnd()
        if (body.isEmpty()) return ""
        return when (body.last()) {
            in SENTENCE_END -> body
            in JOINING -> body.trimEnd { it in JOINING || it.isWhitespace() }.takeIf { it.isNotEmpty() } ?: ""
            else -> null
        }
    }
}
