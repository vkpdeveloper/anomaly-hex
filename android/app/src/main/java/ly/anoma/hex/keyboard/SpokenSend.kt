package ly.anoma.hex.keyboard

import ly.anoma.hex.relay.HexClient

/** Say "send it" to send (Settings › Keyboard, off by default): a dictation whose last words are
 * "send it" (or Hinglish's "bhej do", "भेज दो") drops them and presses the field's send key. Read on
 * the phone from the text the computer sent back, so a computer of any version works, and a rewrite
 * that changes the words sends nothing. "send" alone never sends; where the phrase may stand is
 * [SpokenCommand]'s rule. */
internal object SpokenSend {
    private val phrases = listOf("send it", "bhej do", "भेज दो")

    /** The text to type before sending, "" for nothing, or null when [text] doesn't end by asking to send. */
    fun strip(text: String): String? = SpokenCommand.ending(text, phrases)?.second
}

/** Editing by voice: a dictation that ends with a command acts on what was said. "Scratch that" takes
 * back the dictation it ends, or the one before when it is all that was said; "make it shorter",
 * "make it friendlier", "make it formal", "translate to English" and "fix the grammar" rewrite the text as Rewrite's tones
 * do, with the dictation in it. Where a command may stand is [SpokenCommand]'s rule, so "can you make it
 * shorter" stays text. */
internal object SpokenEdit {
    sealed interface Action {
        data object Scratch : Action
        data class Rewrite(val style: HexClient.Style) : Action
    }

    /** A command and the text said before it ("" for none). */
    data class Command(val action: Action, val body: String)

    private val commands: List<Pair<String, Action>> = buildList {
        fun add(action: Action, vararg phrases: String) = phrases.forEach { add(it to action) }
        add(Action.Scratch, "scratch that", "delete that")
        val rewrite = { style: HexClient.Style -> Action.Rewrite(style) }
        add(rewrite(HexClient.Style.Shorter), "make it shorter", "make that shorter", "shorten it", "shorten that")
        add(rewrite(HexClient.Style.Friendlier), "make it friendlier", "make it friendly", "make that friendlier",
            "make it nicer", "make it polite", "make it more polite", "say it politely")
        add(rewrite(HexClient.Style.Formal), "make it formal", "make it more formal", "make that formal",
            "make it professional", "make it more professional")
        add(rewrite(HexClient.Style.English), "translate to english", "translate it to english",
            "translate that to english", "translate into english", "english mein karo", "english mein likho")
        add(rewrite(HexClient.Style.Fix), "fix the grammar", "fix grammar")
    }

    fun read(text: String): Command? {
        val (phrase, body) = SpokenCommand.ending(text, commands.map { it.first }) ?: return null
        return Command(commands.first { it.first.equals(phrase, ignoreCase = true) }.second, body)
    }
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
