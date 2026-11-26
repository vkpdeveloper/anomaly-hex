package ly.anoma.hex.keyboard

import android.text.TextUtils

/** The text around the cursor as the keyboard last read or wrote it, so a key reads it without a round
 * trip to the editor's app. Every read through an InputConnection blocks until that app's main thread
 * answers, and it is busy laying out the last key's text: on the emulator in Messages that was 20 ms a
 * letter and 80 ms a space. Android's own keyboard (LatinIME's RichInputConnection) keeps the same copy.
 *
 * Edits go through to the editor and are applied to the copy too. The editor then reports each new
 * cursor position ([selectionChanged]); one the keyboard didn't expect (a tap in the text, the app
 * changing its text), an edit the editor refused, or an expected report that never comes within
 * [STALE_MS] drops the copy, and the next read asks the editor again. */
internal class CachedText(
    private val field: KeyboardTextTarget,
    private val clock: () -> Long,
    private val capsOf: (CharSequence, Int) -> Int = { text, mask -> TextUtils.getCapsMode(text, text.length, mask) },
) : KeyboardTextTarget {
    private var before: String? = null
    private var beforeAll = false // [before] reaches the start of the text
    private var after: String? = null
    private var afterAll = false
    /** The cursor once the editor has applied every edit sent, or -1 when not known; with no selection. */
    private var caret = -1
    /** Cursor positions the editor has yet to report for edits sent, each with when it was sent. */
    private val expected = ArrayDeque<Pair<Int, Long>>()

    /** A field started, with its cursor and the text around it that the editor offered up front, each
     * side asked for up to [INITIAL] characters. */
    fun started(selStart: Int, selEnd: Int, initialBefore: CharSequence?, initialAfter: CharSequence?) {
        drop()
        caret = if (selStart >= 0 && selStart == selEnd) selStart else -1
        if (caret < 0) return
        initialBefore?.toString()?.let { before = it; beforeAll = it.length == caret }
        // EditorInfo keeps 2048 characters around the cursor. With all of the text before it here (so at
        // most [INITIAL] of them), a text after it shorter than [INITIAL] was not cut either: it is all there is.
        initialAfter?.toString()?.let { after = it; afterAll = beforeAll && it.length < INITIAL }
    }

    fun selectionChanged(selStart: Int, selEnd: Int) {
        if (selStart < 0 || selStart != selEnd) { lose(); return }
        val at = expected.indexOfLast { it.first == selStart }
        if (at >= 0) { repeat(at + 1) { expected.removeFirst() }; return }
        if (expected.isEmpty() && selStart == caret) return
        drop(); caret = selStart
    }

    private fun drop() { before = null; after = null; beforeAll = false; afterAll = false; expected.clear() }
    // The copy holds only while every edit sent was reported in time.
    private fun fresh(): Boolean {
        val oldest = expected.firstOrNull() ?: return caret >= 0
        if (clock() - oldest.second <= STALE_MS) return true
        lose()
        return false
    }

    override val connected get() = field.connected

    override fun before(length: Int): String? {
        if (!fresh()) return field.before(length)
        before?.let { if (it.length >= length || beforeAll) return it.takeLast(length) }
        val read = field.before(maxOf(length, WINDOW)) ?: return null
        before = read; beforeAll = read.length < maxOf(length, WINDOW)
        return read.takeLast(length)
    }
    override fun after(length: Int): String? {
        if (!fresh()) return field.after(length)
        after?.let { if (it.length >= length || afterAll) return it.take(length) }
        val read = field.after(maxOf(length, WINDOW)) ?: return null
        after = read; afterAll = read.length < maxOf(length, WINDOW)
        return read.take(length)
    }
    // The copy is kept only with no selection, so nothing is selected while it holds.
    override fun selected() = if (fresh()) "" else field.selected()
    override fun capsMode(mask: Int): Int {
        // The same rule the editor applies (BaseInputConnection), over the text it would read.
        val b = if (fresh()) before(WINDOW) else null
        return if (b != null && (beforeAll || b.length >= WINDOW)) capsOf(b, mask) else field.capsMode(mask)
    }

    override fun commit(text: String): Boolean {
        val ok = field.commit(text)
        if (!ok) lose() else if (fresh()) {
            before = before?.let { b -> (b + text).let { all -> if (all.length > KEEP) { beforeAll = false; all.takeLast(KEEP) } else all } }
            moved(caret + text.length)
        }
        return ok
    }
    override fun deleteBefore(length: Int) = deleted(field.deleteBefore(length), length, 0)
    override fun deleteCodePointsBefore(count: Int): Boolean {
        val units = before?.takeIf { fresh() }?.let { codeUnits(it, count) }
        val ok = field.deleteCodePointsBefore(count)
        if (units == null) { lose(); return ok }
        return deleted(ok, units, 0)
    }
    override fun deleteAround(before: Int, after: Int) = deleted(field.deleteAround(before, after), before, after)
    // The editor deletes what there is: at most [caret] characters before the cursor.
    private fun deleted(ok: Boolean, length: Int, ahead: Int): Boolean {
        if (!ok) { lose(); return false }
        if (!fresh()) return true
        val gone = minOf(length, caret)
        before = before?.let { b -> if (b.length >= gone) b.dropLast(gone) else null.also { beforeAll = false } }
        if (ahead > 0) after = after?.let { a -> if (a.length >= ahead || afterAll) a.drop(ahead) else null.also { afterAll = false } }
        moved(caret - gone)
        return true
    }
    private fun moved(to: Int) { caret = to; expected.addLast(to to clock()) }
    // Edits whose effect the copy can't follow: the next read asks the editor.
    private fun lose() { drop(); caret = -1 }

    override fun beginBatch() = field.beginBatch()
    override fun endBatch() = field.endBatch()
    // A cursor put somewhere in the text the copy holds (each step of a slide on space) moves the copy
    // with it: read from the editor, every step waited 17 ms for Messages on the emulator.
    override fun setSelection(start: Int, end: Int): Boolean {
        val ok = field.setSelection(start, end)
        if (!ok || start != end || !fresh() || !moveCopy(start)) lose()
        return ok
    }
    private fun moveCopy(to: Int): Boolean {
        val b = before ?: return false
        val a = after ?: return false
        val by = to - caret
        when {
            by == 0 -> return true
            by < 0 && -by <= b.length -> { before = b.dropLast(-by); after = b.takeLast(-by) + a }
            by > 0 && by <= a.length -> { before = b + a.take(by); after = a.drop(by) }
            else -> return false
        }
        before?.let { if (it.length > KEEP) { before = it.takeLast(KEEP); beforeAll = false } }
        after?.let { if (it.length > KEEP) { after = it.take(KEEP); afterAll = false } }
        moved(to)
        return true
    }
    override fun performEditorAction(id: Int) = field.performEditorAction(id).also { lose() }
    override fun defaultEditorAction() = field.defaultEditorAction().also { lose() }
    override fun sendKey(code: Int) { field.sendKey(code); lose() }
    // Undo keeps the field as each word starts. Read from the editor that is a round trip with the whole
    // text, 15 to 70 ms in Messages on the emulator, so it comes from the copy once the copy holds it all.
    override fun all(): FieldState? {
        if (fresh() && beforeAll && afterAll) {
            val b = before; val a = after
            if (b != null && a != null) return FieldState(b + a, caret, caret)
        }
        return allFromEditor()
    }
    // A whole read is the copy too: the editor answers after every edit sent, so it is current.
    override fun allFromEditor(): FieldState? {
        val read = field.all() ?: return null
        if (read.selStart == read.selEnd && (caret < 0 || caret == read.selStart)) {
            if (caret < 0) expected.clear()
            caret = read.selStart
            before = read.text.substring(0, caret); beforeAll = true
            after = read.text.substring(caret); afterAll = true
        }
        return read
    }
    override fun contextAction(id: Int): Boolean { val done = field.contextAction(id); lose(); return done }

    companion object {
        /** The text asked of EditorInfo each side of the cursor as a field starts: half of what it keeps. */
        const val INITIAL = 1024
        /** Read at least this much each way, so the next keys find it here. */
        private const val WINDOW = 128
        /** The text before the cursor kept while typing goes on. */
        private const val KEEP = 4096
        /** An edit the editor hasn't reported by then was refused or changed: read the text again. */
        private const val STALE_MS = 1_000L

        // The UTF-16 length of the last [count] code points of [text], or null when it holds fewer.
        private fun codeUnits(text: String, count: Int): Int? {
            var i = text.length
            repeat(count) {
                if (i == 0) return null
                i -= if (i >= 2 && Character.isLowSurrogate(text[i - 1]) && Character.isHighSurrogate(text[i - 2])) 2 else 1
            }
            return text.length - i
        }
    }
}
