package ly.anoma.hex.keyboard

/** Say "send it" to send (Settings › Keyboard, off by default): a dictation whose last words are
 * "send it" (or Hinglish's "bhej do", "भेज दो") drops them and presses the field's send key. Read on
 * the phone from the text the computer sent back, so a computer of any version works. "send" alone
 * never sends; where the phrase may stand is [SpokenCommand]'s rule. */
internal object SpokenSend {
    private val phrases = listOf("send it", "bhej do", "भेज दो")

    /** The text to type before sending, "" for nothing, or null when [text] doesn't end by asking to send. */
    fun strip(text: String): String? = SpokenCommand.ending(text, phrases)?.second
}

/** "Scratch that" (or "delete that") ending a dictation takes back the text said before it, or the
 * dictation before when it is all that was said. Where it may stand is [SpokenCommand]'s rule. */
internal object SpokenEdit {
    private val phrases = listOf("scratch that", "delete that")

    /** The text said before the command ("" for none), or null when [text] doesn't end with one. */
    fun scratched(text: String): String? = SpokenCommand.ending(text, phrases)?.second
}

/** Where a spoken command may stand: the whole dictation, or after the end of a sentence or a pause the
 * text marks (". send it", ", send it", "- send it"), so "I'll send it" and "can you send it" stay text. */
internal object SpokenCommand {
    // After the phrase: its own sentence's end. Before it: a sentence's end, never "…", which trails off.
    private const val CLOSING = ".!?…"
    private const val SENTENCE_END = ".!?"
    // Marks a pause before the phrase; dropped with it, as nothing follows them now.
    private const val JOINING = ",;:-–—"

    /** The phrase [text] ends with, longest first, and the text to type before it ("" for nothing), or null
     * when it doesn't end with one where a command may stand. */
    fun ending(text: String, phrases: List<String>): Pair<String, String>? {
        val words = text.trimEnd().trimEnd { it in CLOSING }.trimEnd()
        val phrase = phrases.sortedByDescending { it.length }.firstOrNull { words.endsWith(it, ignoreCase = true) } ?: return null
        val before = words.dropLast(phrase.length)
        if (before.isNotEmpty() && !before.last().isWhitespace() && before.last() !in JOINING) return null
        val body = before.trimEnd()
        if (body.isEmpty()) return phrase to ""
        return when (body.last()) {
            in SENTENCE_END -> phrase to body
            in JOINING -> phrase to (body.trimEnd { it in JOINING || it.isWhitespace() }.takeIf { it.isNotEmpty() } ?: "")
            else -> null
        }
    }
}
