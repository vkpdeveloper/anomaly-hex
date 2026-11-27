package ly.anoma.hex.keyboard

import ly.anoma.hex.typing.Replacements

/** A text shortcut: typing [shortcut] offers [text] on the strip. A part of the text in braces is a
 * blank, "There in {10} min": the braces go and the first blank is selected, ready to type over. */
internal data class Snippet(val shortcut: String, val text: String) {
    /** The text without its braces, and where its first blank sits in that. */
    fun filled(): Pair<String, IntRange?> {
        val out = StringBuilder()
        var blank: IntRange? = null
        var start = -1
        for (c in text) when {
            c == '{' && start < 0 -> start = out.length
            c == '}' && start >= 0 -> { if (blank == null) blank = start until out.length; start = -1 }
            else -> out.append(c)
        }
        return out.toString() to blank
    }
}

internal object Snippets {
    private const val CHIPS = 3
    private const val LABEL = 28
    val starter = listOf(Snippet(";eta", "There in {10} min"))

    /** Kept as [Replacements] keeps its pairs: a shortcut and its text on each line. */
    fun read(stored: String?): List<Snippet> =
        if (stored == null) starter else Replacements.pairs(stored).map { (shortcut, text) -> Snippet(shortcut, text) }

    fun write(list: List<Snippet>): String = Replacements.write(list.map { it.shortcut to it.text })

    /** Chips for the shortcut being typed before the caret. One that starts with a mark (";eta") is offered
     * from its mark and one more character, so ";e" already shows it; any other only once typed whole, as
     * its own word. */
    fun offer(before: String, list: List<Snippet>): List<StripAction.Insert> {
        if (list.isEmpty() || before.isEmpty() || before.last().isWhitespace()) return emptyList()
        val word = before.takeLastWhile { !it.isWhitespace() }
        val whole = list.filter { word.endsWith(it.shortcut) && word.getOrNull(word.length - it.shortcut.length - 1)
            ?.isLetterOrDigit() != true }
        val started = list.filter { s ->
            val mark = s.shortcut.firstOrNull()?.takeIf { !it.isLetterOrDigit() } ?: return@filter false
            val typed = word.substring(word.lastIndexOf(mark).takeIf { it >= 0 } ?: return@filter false)
            typed.length >= 2 && s.shortcut.startsWith(typed)
        }
        return (whole + started.sortedBy { it.shortcut }).distinct().take(CHIPS).map { s ->
            val typed = if (s in whole) s.shortcut.length else word.length - word.lastIndexOf(s.shortcut[0])
            val (text, blank) = s.filled()
            val label = if (text.length > LABEL) text.take(LABEL - 1).trimEnd() + "…" else text
            StripAction.Insert(label, typed, text, blank)
        }
    }
}
