package ly.anoma.hex.keyboard

import android.inputmethodservice.InputMethodService
import android.view.inputmethod.ExtractedTextRequest

/** Everything the keyboard engine may read from or do to the editor. The IME passes its live
 * InputConnection; a test can pass a fake. Each call reaches the field focused at that moment. */
internal interface KeyboardTextTarget {
    val connected: Boolean
    fun before(length: Int): String?
    fun after(length: Int): String?
    fun selected(): String?
    fun capsMode(mask: Int): Int
    fun commit(text: String): Boolean
    fun deleteBefore(length: Int): Boolean
    fun deleteCodePointsBefore(count: Int): Boolean
    fun deleteAround(before: Int, after: Int): Boolean
    fun beginBatch()
    fun endBatch()
    fun setSelection(start: Int, end: Int): Boolean
    fun performEditorAction(id: Int): Boolean
    fun defaultEditorAction(): Boolean
    fun sendKey(code: Int)
    /** The whole text and selection, or null where it can't be read whole (too long, or no editor). It
     * may come from the keyboard's own copy of the text ([CachedText]). */
    fun all(): FieldState? = null
    /** [all], read from the editor itself: for edits made from it, which must not go by an old copy. */
    fun allFromEditor(): FieldState? = all()
    /** The editor's own Cut, Copy, Paste or Select all (android.R.id.cut and the rest). */
    fun contextAction(id: Int): Boolean = false
}

/** The current editor's InputConnection, looked up on every call: it changes with each field. */
internal class InputConnectionTarget(private val ime: InputMethodService) : KeyboardTextTarget {
    private val ic get() = ime.currentInputConnection
    override val connected get() = ic != null
    override fun before(length: Int) = traced("hex:ic:before") { ic?.getTextBeforeCursor(length, 0)?.toString() }
    override fun after(length: Int) = traced("hex:ic:after") { ic?.getTextAfterCursor(length, 0)?.toString() }
    override fun selected() = traced("hex:ic:selected") { ic?.getSelectedText(0)?.toString() }
    override fun capsMode(mask: Int) = traced("hex:ic:caps") { ic?.getCursorCapsMode(mask) ?: 0 }
    override fun commit(text: String) = traced("hex:ic:commit") { ic?.commitText(text, 1) ?: false }
    override fun deleteBefore(length: Int) = traced("hex:ic:delete") { ic?.deleteSurroundingText(length, 0) ?: false }
    override fun deleteCodePointsBefore(count: Int) = traced("hex:ic:deleteCp") { ic?.deleteSurroundingTextInCodePoints(count, 0) ?: false }
    override fun deleteAround(before: Int, after: Int) = traced("hex:ic:delete") { ic?.deleteSurroundingText(before, after) ?: false }
    override fun beginBatch() { traced("hex:ic:batch") { ic?.beginBatchEdit() } }
    override fun endBatch() { traced("hex:ic:batch") { ic?.endBatchEdit() } }
    override fun setSelection(start: Int, end: Int) = traced("hex:ic:selection") { ic?.setSelection(start, end) ?: false }
    override fun performEditorAction(id: Int) = traced("hex:ic:action") { ic?.performEditorAction(id) ?: false }
    override fun defaultEditorAction() = traced("hex:ic:action") { ime.sendDefaultEditorAction(true) }
    override fun sendKey(code: Int) = traced("hex:ic:key") { ime.sendDownUpKeyEvents(code) }
    override fun contextAction(id: Int) = traced("hex:ic:action") { ic?.performContextMenuAction(id) ?: false }
    override fun all(): FieldState? = traced("hex:ic:all") {
        val got = ic?.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = MAX_FIELD }, 0)
        val text = got?.text?.toString()
        if (got == null || text == null || got.startOffset != 0 || text.length > MAX_FIELD || got.selectionStart < 0) null
        else FieldState(text, got.selectionStart, got.selectionEnd)
    }
    private companion object {
        /** Undo keeps the whole text at each step, so a longer field has none. */
        const val MAX_FIELD = 20_000
    }
}
