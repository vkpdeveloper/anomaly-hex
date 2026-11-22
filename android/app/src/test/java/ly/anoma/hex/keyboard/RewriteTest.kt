package ly.anoma.hex.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.PersonalWords
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The grid menu's Rewrite on the engine's side: what it sends, putting the answer back in one edit, its
 * undo, and leaving a field that changed while the computer worked alone. */
class RewriteTest {
    @Test fun rewritesEverythingAroundTheCaretWithoutTheSpacesAtTheEnds() {
        val k = Keyboard("  im on my way \n", caret = 5)
        val span = k.engine.rewriteSpan()!!
        assertEquals("im on my way", span.text)
        assertTrue(k.engine.applyRewrite(span, "I'm on my way."))
        assertEquals("  I'm on my way. \n", k.editor.text)
        assertEquals(UndoOffer.Rewrite, k.offer)
    }

    @Test fun rewritesOnlyTheSelectionAndKeepsItSelected() {
        val k = Keyboard("Hi. teh meeting is moved. Bye.", caret = 4, selEnd = 25)
        val span = k.engine.rewriteSpan()!!
        assertEquals("teh meeting is moved.", span.text)
        assertTrue(k.engine.applyRewrite(span, "The meeting has moved."))
        assertEquals("Hi. The meeting has moved. Bye.", k.editor.text)
        assertEquals("The meeting has moved.", k.editor.selected())
    }

    @Test fun undoPutsTheTextBack() {
        val k = Keyboard("Hi. teh meeting is moved. Bye.", caret = 4, selEnd = 25)
        k.engine.applyRewrite(k.engine.rewriteSpan()!!, "The meeting has moved.")
        k.synced()
        // A rewrite's words aren't handed to the fixer to leave alone.
        assertEquals(emptyList<String>(), k.engine.undo())
        assertEquals("Hi. teh meeting is moved. Bye.", k.editor.text)
        assertNull(k.offer)
    }

    @Test fun aFieldThatChangedMeanwhileIsLeftAlone() {
        val k = Keyboard("im on my way", caret = 12)
        val span = k.engine.rewriteSpan()!!
        k.editor.commit("!"); k.synced()
        assertFalse(k.engine.applyRewrite(span, "I'm on my way."))
        assertEquals("im on my way!", k.editor.text)
        assertNull(k.offer)
    }

    @Test fun undoDoesNothingOnceTheRewrittenTextChanged() {
        val k = Keyboard("im on my way", caret = 12)
        k.engine.applyRewrite(k.engine.rewriteSpan()!!, "I'm on my way.")
        k.synced()
        k.editor.commit(" Soon"); k.synced()
        k.engine.undo()
        assertEquals("I'm on my way. Soon", k.editor.text)
    }

    @Test fun nothingToRewriteInBlankOrPasswordFields() {
        assertNull(Keyboard("   ", caret = 1).engine.rewriteSpan())
        assertNull(Keyboard("hunter2", caret = 7,
            type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).engine.rewriteSpan())
    }

    private class Keyboard(text: String, caret: Int, selEnd: Int = caret, type: Int = InputType.TYPE_CLASS_TEXT) {
        val editor = Editor(text, caret, selEnd)
        var offer: UndoOffer? = null
        val engine = KeyboardEngine(editor, TypingPreferences(Prefs.context()), { Spoken.English }, { SpatialDecoder(model) },
            readPersonal = { m, mine -> PersonalWords(m, mine, emptyMap()) }, clock = { 0L })

        init {
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) {}
                override fun suggestions(slots: List<Suggestion>) {}
                override fun undo(offer: UndoOffer?) { this@Keyboard.offer = offer }
            }
            engine.startInput(EditorInfo().apply { inputType = type; initialSelStart = caret; initialSelEnd = selEnd })
        }

        /** The editor reports its selection, as Android does after an edit. */
        fun synced() = engine.updateSelection(-1, -1, editor.selStart, editor.selEnd)
    }

    /** An editor with a selection: a commit replaces it, as InputConnection's does. */
    private class Editor(var text: String, var selStart: Int, var selEnd: Int) : KeyboardTextTarget {
        override val connected = true
        override fun before(length: Int) = text.substring(maxOf(0, selStart - length), selStart)
        override fun after(length: Int) = text.substring(selEnd, minOf(text.length, selEnd + length))
        override fun selected() = text.substring(selStart, selEnd)
        override fun capsMode(mask: Int) = 0
        override fun commit(text: String): Boolean {
            this.text = this.text.substring(0, selStart) + text + this.text.substring(selEnd)
            selStart += text.length; selEnd = selStart; return true
        }
        override fun deleteBefore(length: Int) = false
        override fun deleteCodePointsBefore(count: Int) = false
        override fun deleteAround(before: Int, after: Int) = false
        override fun beginBatch() {}
        override fun endBatch() {}
        override fun setSelection(start: Int, end: Int): Boolean { selStart = start; selEnd = end; return true }
        override fun performEditorAction(id: Int) = false
        override fun defaultEditorAction() = false
        override fun sendKey(code: Int) {}
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}
