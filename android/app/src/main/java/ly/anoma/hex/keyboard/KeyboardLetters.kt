package ly.anoma.hex.keyboard

/** Each layout's letter rows, lowercase, keyed by the layout's BCP 47 code. A new layout is a new
 * entry here (and its accents in LetterFeedback); anything unknown types on English's rows. */
internal object KeyboardLetters {
    private val rows = mapOf(
        "en" to listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
        "es" to listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm"),
        "nb" to listOf("qwertyuiopå", "asdfghjkløæ", "zxcvbnm"),
        "de" to listOf("qwertzuiopü", "asdfghjklöä", "yxcvbnm"),
        // Gboard's French AZERTY (Pixel 8 emulator): ten keys edge to edge on rows 1 and 2, and an
        // apostrophe key after n on row 3, between shift and delete.
        "fr" to listOf("azertyuiop", "qsdfghjklm", "wxcvbn'"),
        "it" to listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
        "hien" to listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
    )

    fun rows(layoutCode: String) = rows[layoutCode] ?: rows.getValue("en")

    /** Gboard's two symbol pages, row by row; row 3 sits between the page switch and delete. */
    val numbers = listOf("1234567890", "@#₹_&-+()/", "*\"':;!?")
    val symbols = listOf("~`|•√π÷×§Δ", "€¥$¢^°={}\\", "%©®™✓[]")
    /** Row 1's long-press digits, as Gboard's small corner digits show them. */
    fun digit(layoutCode: String, c: Char): Char? = rows(layoutCode)[0].indexOf(c.lowercaseChar())
        .takeIf { it in 0..9 }?.let { "1234567890"[it] }
}
