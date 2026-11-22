package ly.anoma.hex.keyboard

/** Where spaces go, as Gboard puts them (black box, Pixel 8 emulator, API 36), and iPhone Hex where
 * Gboard differs for the worse. */
internal object SpaceRules {
    /** Two spaces this close together after a word become ". " (Gboard: 0.87 s did, 1.07 s didn't). */
    const val DOUBLE_SPACE_MS = 950L

    /** Marks that take the space a picked suggestion typed ("Hello" picked, then "," gives "Hello,"). */
    const val CLAUSE_MARKS = ",.!?;:)"

    /** What goes against the word before rather than after a space a pick held (iPhone Hex's joiners). */
    const val JOINERS = ",.!?;:)]}'-/\"»…"

    /** Whether a glided word after [before] takes a space first: it follows a word, a number or a closing
     * mark. A quote closes when something other than a space comes before it. */
    fun followsWord(before: String): Boolean {
        val last = before.lastOrNull() ?: return false
        return when {
            last.isLetterOrDigit() -> true
            last in "\"'\u2019\u201D" -> before.length >= 2 && !before[before.length - 2].isWhitespace()
            else -> last in ".,!?;:)]}»…%"
        }
    }

    /** Whether ". " may replace the space that ends [before]: it follows a letter or a digit. */
    fun periodFits(before: String) = before.length >= 2 && before.last() == ' ' &&
        before[before.length - 2].let { it.isLetterOrDigit() }

    /** Whether the text after the cursor already separates the word before it from what follows: a
     * space (not a line break) or a closing mark. A picked word then holds its space (iPhone Hex
     * #2214): Gboard steps over the space instead, and a word typed next joins the one after it. */
    fun separates(after: String?): Boolean {
        val first = after?.firstOrNull() ?: return false
        if (first.isWhitespace()) return first != '\n'
        return first in ".,!?;:)]}\"'»…"
    }
}
