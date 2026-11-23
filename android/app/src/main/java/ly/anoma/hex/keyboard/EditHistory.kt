package ly.anoma.hex.keyboard

/** A field's whole text and selection, as the editor reports it. */
internal data class FieldState(val text: String, val selStart: Int, val selEnd: Int)

/** Undo and redo for what the keyboard typed, kept by Hex rather than the app, so they work in any field
 * (a two-finger swipe on the keys, left to undo and right to redo). A step is a word with the space or
 * mark after it, a run of deletes, or one larger edit: a pick, an autocorrection, a dictation, a paste,
 * a rewrite. [checkpoint] keeps the field as it was before each step; undo puts that back with the
 * smallest edit that does it, so the rest of the text, and its formatting, is left alone. Text the app
 * changed itself is a step too. A field too long to read whole ([FieldState]) keeps no history. */
internal class EditHistory(private val target: KeyboardTextTarget) {
    private companion object { const val STEPS = 100 }
    private val past = ArrayDeque<FieldState>()
    private val future = ArrayDeque<FieldState>()
    /** Redo's steps as the last step began, back again if that step is forgotten. */
    private var cleared = emptyList<FieldState>()

    fun clear() { past.clear(); future.clear(); cleared = emptyList() }

    /** Before a step: the field as it is now is what undo goes back to, and redo's steps are gone. True
     * when that is a new step. */
    fun checkpoint(): Boolean {
        val now = target.all() ?: return false
        cleared = future.toList()
        future.clear()
        if (past.lastOrNull()?.text == now.text) return false
        past.addLast(now)
        if (past.size > STEPS) past.removeFirst()
        return true
    }

    /** Takes back the last step as if it never happened: an edit made by mistake. Redo has its steps again. */
    fun forget() {
        if (!step(from = past, to = future)) return
        future.clear(); future.addAll(cleared)
    }

    /** Deletes [length] characters before the caret as no step at all: a key typed by mistake. */
    fun delete(length: Int) { target.deleteBefore(length) }

    fun undo() = step(from = past, to = future)
    fun redo() = step(from = future, to = past)

    private fun step(from: ArrayDeque<FieldState>, to: ArrayDeque<FieldState>): Boolean {
        val now = target.all() ?: return false
        while (from.lastOrNull()?.text == now.text) from.removeLast()
        val back = from.removeLastOrNull() ?: return false
        if (to.lastOrNull()?.text != now.text) to.addLast(now)
        put(now, back)
        return true
    }

    // Only the part that differs is replaced: the caret goes to its end, the old part is deleted before
    // it and the new part committed, then the selection is put back as it was.
    private fun put(now: FieldState, back: FieldState) {
        val a = now.text; val b = back.text
        var prefix = 0
        while (prefix < a.length && prefix < b.length && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.length - prefix && suffix < b.length - prefix && a[a.length - 1 - suffix] == b[b.length - 1 - suffix]) suffix++
        // Never split a surrogate pair.
        if (prefix > 0 && Character.isHighSurrogate(a[prefix - 1])) prefix--
        if (suffix > 0 && Character.isLowSurrogate(a[a.length - suffix])) suffix--
        target.beginBatch()
        try {
            val end = a.length - suffix
            target.setSelection(end, end)
            if (end > prefix) target.deleteBefore(end - prefix)
            val added = b.substring(prefix, b.length - suffix)
            if (added.isNotEmpty()) target.commit(added)
            target.setSelection(back.selStart.coerceIn(0, b.length), back.selEnd.coerceIn(0, b.length))
        } finally { target.endBatch() }
    }
}
