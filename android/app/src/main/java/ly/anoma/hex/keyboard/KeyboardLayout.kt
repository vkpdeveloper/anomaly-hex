package ly.anoma.hex.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import ly.anoma.hex.speech.Spoken

/** The enter key: the editor's own label, else one of the standard actions, else plain return. */
internal data class EnterKey(val label: String?, val action: Int) {
    companion object {
        const val RETURN = EditorInfo.IME_ACTION_NONE
        fun from(info: EditorInfo?): EnterKey {
            val options = info?.imeOptions ?: 0
            if (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return EnterKey(null, RETURN)
            return EnterKey(info?.actionLabel?.toString(), options and EditorInfo.IME_MASK_ACTION)
        }
    }
}

/** What the key beside ?123 types on the letters page: Gboard's @ in email fields and / in web
 * addresses, else a comma. */
internal enum class FieldKind(val mark: String) {
    Text(","), Email("@"), Url("/");
    companion object {
        fun of(inputType: Int): FieldKind {
            if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return Text
            return when (inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> Email
                InputType.TYPE_TEXT_VARIATION_URI -> Url
                else -> Text
            }
        }
    }
}

/** What the keys show: one page of one layout, with shift, the enter key and the field's kind.
 * [symbols] is any page but the letters; [more] is the second symbols page and [numpad] the number pad. */
internal data class KeyboardPage(
    val language: Spoken,
    val symbols: Boolean,
    val more: Boolean,
    val shifted: Boolean,
    val locked: Boolean,
    val enter: EnterKey,
    val numpad: Boolean = false,
    val kind: FieldKind = FieldKind.Text,
) {
    val lines: List<String> get() {
        val code = language.layoutCode
        return when {
            !symbols -> KeyboardLetters.rows(code)
            more -> KeyboardLetters.symbols
            else -> KeyboardLetters.numbers
        }
    }
}

/** Sizes in dp, measured from Gboard's dark theme on a OnePlus Nord (3.5 px per dp, default height):
 * a 52 dp toolbar, keycaps 40.6 dp tall on a 50.6 dp row pitch (row 4's are 41.4 dp), 2 dp from the
 * screen's edges with 4.6 dp between them, and row 4 ending 6 dp above the system's bar. Each row's
 * cell is the pitch with half the gap above and below its keycap, and taps in the gaps and edges type
 * the nearest key, as Gboard's do. Widths are in cells: a tenth of the row, an eleventh where a layout
 * has 11 letters. */
internal object KeyboardMetrics {
    const val SIDE = 2f
    const val GAP = 4.57f
    /** The toolbar above the keys; row 1's cell takes the last 5 dp of Gboard's 52. */
    const val TOOLBAR = 47f
    /** Where the toolbar's icons centre, from its top. */
    const val TOOLBAR_CENTER = 21.7f
    const val ROW = 50.57f
    const val KEY_HEIGHT = 40.57f
    const val INSET_TOP = 5f
    /** Row 4's keycaps are taller, and the gap under them wider. */
    const val LAST_KEY_HEIGHT = 41.43f
    const val LAST_BOTTOM = 10f
    /** The number pad's keycaps on rows 1 to 3 reach 4 dp lower. */
    const val NUMPAD_KEY_HEIGHT = 44.57f
    // Turned, the keys are shorter; the toolbar and widths stay.
    const val ROW_LANDSCAPE = 40f
    const val KEY_HEIGHT_LANDSCAPE = 32f
    const val TOOLBAR_LANDSCAPE = 40f
    const val TOOLBAR_CENTER_LANDSCAPE = 19f
    const val CORNER = 6f
    /** The mic's and the clipboard chip's height, and the mic's width: fully round. */
    const val PILL = 34f
    /** The mic's touch target at the toolbar's trailing end, centred where Gboard's is. */
    const val MIC_TARGET = 48f
    /** Letters, the number pad's digits, and the small digits in row 1's corners. */
    const val LETTER = 24.5f
    const val SYMBOL = 23.5f
    const val NUMPAD_DIGIT = 31f
    const val HINT = 10.5f
    const val LABEL = 14.75f
    /** A slide on space moves the cursor one character after this much travel, then one more each step. */
    const val CURSOR_START = 17f
    const val CURSOR_STEP = 12.6f
    /** A slide left from delete selects a word after this much travel, one more each step; a press
     * that went further than the cancel distance deletes nothing on its own. Held still, delete
     * repeats every 50 ms after 400 ms. */
    const val DELETE_START = 53f
    const val DELETE_STEP = 36f
    const val DELETE_CANCEL = 32f
    const val DELETE_REPEAT_DELAY = 400L
    const val DELETE_REPEAT_EVERY = 50L
    /** A long press shows row 1's digit and the accents after this long. */
    const val LONG_PRESS = 300L

    /** Cells across the letter rows: the longer of rows 1 and 2. */
    fun columns(lines: List<String>) = maxOf(lines[0].length, lines[1].length)
    /** Shift and delete (or the symbol pages' switch) beside row 3's keys, in that row's cells. */
    fun sideWidth(page: KeyboardPage, lines: List<String>): Float =
        if (page.symbols) 1.5f else (columns(lines) - lines[2].length) / 2f
}
