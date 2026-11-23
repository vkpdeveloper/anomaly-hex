package ly.anoma.hex.keyboard

/** A chip on the strip in the suggestions' place: something to do with the selected text, or with what
 * was just typed. */
internal sealed interface StripAction {
    data object Cut : StripAction
    data object Copy : StripAction
    /** [label] on the chip; a tap puts [text] in place of the [replace] characters before the caret and
     * selects [select] within it, if given. */
    data class Insert(val label: String, val replace: Int, val text: String, val select: IntRange? = null) : StripAction

    companion object {
        /** What a selection offers. */
        val selection = listOf(Cut, Copy)
    }
}
