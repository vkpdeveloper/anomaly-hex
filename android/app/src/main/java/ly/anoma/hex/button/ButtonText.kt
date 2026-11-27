package ly.anoma.hex.button

/**
 * The text to put at the cursor: a space before it when it follows a word,
 * so a second dictation doesn't run into the first.
 */
internal fun joinAtCursor(before: CharSequence, transcript: String): String {
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
internal fun fieldText(text: CharSequence?, hint: CharSequence?, showingHint: Boolean, cursor: Int): String {
    val current = text?.toString().orEmpty()
    if (showingHint) return ""
    val placeholder = !hint.isNullOrEmpty() && current == hint.toString() && cursor <= 0
    return if (placeholder) "" else current
}
