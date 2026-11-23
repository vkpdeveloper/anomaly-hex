package ly.anoma.hex.keyboard

import ly.anoma.hex.relay.HexClient

/** A chip on the strip in the suggestions' place: something to do with the selected text, or with what
 * was just typed. */
internal sealed interface StripAction {
    data object Cut : StripAction
    data object Copy : StripAction
    /** Rewrite's tones, for the selection (shown only with a paired computer). */
    data class Rewrite(val style: HexClient.Style) : StripAction
    /** [label] on the chip; a tap puts [text] in place of the [replace] characters before the caret and
     * selects [select] within it, if given. */
    data class Insert(val label: String, val replace: Int, val text: String, val select: IntRange? = null) : StripAction
    /** A reply drafted for the chat on screen (Reply in the grid menu); a tap puts it in place of the field's text. */
    data class Reply(val text: String) : StripAction

    companion object {
        /** What a selection offers. */
        val selection = listOf(Cut, Copy, Rewrite(HexClient.Style.Fix), Rewrite(HexClient.Style.English))
    }
}
