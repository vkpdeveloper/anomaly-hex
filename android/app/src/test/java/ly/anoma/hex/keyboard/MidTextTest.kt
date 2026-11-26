package ly.anoma.hex.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.Glides
import ly.anoma.hex.typing.LearnedWords
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences
import ly.anoma.hex.typing.TypingWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Editing away from the end of the text: words picked, typed and offered with text after the caret. */
class MidTextTest {
    private val grid = Glides.grid()

    @Test fun aWordTypedAfterAPickAndASpaceInsideASentenceGetsItsOwnSpace() {
        val k = Keyboard("I like apples", caret = "I like".length)
        k.space(); k.tap("ver")
        k.pick("very")
        assertEquals("I like very apples", k.ed.text)
        // The space after the pick is already there, so space types none; the next word still gets one.
        k.space(); k.tap("red")
        assertEquals("I like very red apples", k.ed.text)
    }

    @Test fun aNextWordPickedAfterAPickAndASpaceInsideASentenceGoesIn() {
        val k = Keyboard("I like apples", caret = "I like".length)
        k.space(); k.tap("ver")
        k.pick("very"); k.space()
        val next = k.strip.first { it.kind == Suggestion.Kind.Next }.text
        k.pick(next)
        assertEquals("I like very $next apples", k.ed.text)
    }

    @Test fun aFieldThatOpensRightAfterAWordOffersThatWord() {
        val k = Keyboard("see you tomor")
        assertTrue("strip: ${k.strip}", k.strip.any { it.text == "tomorrow" })
        k.pick("tomorrow")
        assertEquals("see you tomorrow", k.ed.text)
    }

    @Test fun aFieldThatOpensInsideAWordOffersThatWord() {
        val k = Keyboard("I like wrods here", caret = "I like wr".length)
        assertTrue("strip: ${k.strip}", k.strip.any { it.text == "words" })
        k.pick("words")
        assertEquals("I like words here", k.ed.text)
    }

    @Test fun aFieldThatOpensAfterASpaceStillGuessesTheNextWord() {
        val k = Keyboard("I like to ")
        assertTrue("strip: ${k.strip}", k.strip.isNotEmpty() && k.strip.all { it.kind == Suggestion.Kind.Next })
    }

    @Test fun aFixOnTheStripForAWordTappedIntoIsOneSpaceMakes() {
        val k = Keyboard("I like wrods")
        k.tapAt(2); k.tapAt("I like wrods".length)
        assertEquals(Suggestion("wrods", Suggestion.Kind.Typed), k.strip.first())
        assertEquals("words", k.strip[1].text)
        val fix = k.strip.firstOrNull { it.kind == Suggestion.Kind.Fix }?.text
        k.space()
        // The highlighted word is the one space puts in: none here, since space leaves a word you let stand.
        assertEquals("I like ${fix ?: "wrods"} ", k.ed.text)
    }

    /** An editor with a selection that tells the engine of each change once the key's work is done, as Android does. */
    private class Editor(var text: String, caret: Int) : KeyboardTextTarget {
        var start = caret; var end = caret
        var engine: KeyboardEngine? = null
        private var reported = caret to caret
        override val connected = true
        override fun before(length: Int) = text.substring(maxOf(0, start - length), start)
        override fun after(length: Int) = text.substring(end, minOf(text.length, end + length))
        override fun selected() = text.substring(start, end)
        override fun capsMode(mask: Int) = if (TypingWord.startsSentence(before(start))) mask else 0
        override fun commit(text: String): Boolean {
            this.text = this.text.substring(0, start) + text + this.text.substring(end); start += text.length; end = start; return true
        }
        override fun deleteBefore(length: Int): Boolean {
            val n = minOf(length, start); text = text.removeRange(start - n, start); start -= n; end -= n; return true
        }
        override fun deleteCodePointsBefore(count: Int) = deleteBefore(count)
        override fun deleteAround(before: Int, after: Int): Boolean {
            val a = minOf(after, text.length - end); text = text.removeRange(end, end + a); return deleteBefore(before)
        }
        override fun beginBatch() {}
        override fun endBatch() {}
        override fun setSelection(start: Int, end: Int): Boolean { this.start = start; this.end = end; return true }
        override fun performEditorAction(id: Int) = false
        override fun defaultEditorAction() = false
        override fun sendKey(code: Int) {}
        override fun all() = FieldState(text, start, end)
        fun report() {
            val now = start to end
            if (now != reported) { val old = reported; reported = now; engine?.updateSelection(old.first, old.second, now.first, now.second) }
        }
    }

    private inner class Keyboard(text: String, caret: Int = text.length) {
        val ed = Editor(text, caret)
        val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = false }
        var strip = emptyList<Suggestion>()
        var now = 0L
        val engine = KeyboardEngine(ed, prefs, { Spoken.English }, { SpatialDecoder(model) }, learned = LearnedWords(), clock = { now })

        init {
            ed.engine = engine
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) {}
                override fun suggestions(slots: List<Suggestion>) { strip = slots }
                override fun undo(offer: UndoOffer?) {}
            }
            engine.startInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; initialSelStart = caret; initialSelEnd = caret })
            engine.opened()
            engine.configureTyping()
        }
        // Keys come a little apart; a tap in the text comes well after the last key.
        private fun key(block: () -> Unit) { now += 150; block(); ed.report() }
        fun tap(letters: String) { for (c in letters) key { grid.centres.getValue(c).let { engine.letter(c.toString(), it.x, it.y, grid) } } }
        fun space() = key { engine.space() }
        fun pick(text: String) = key { engine.pickSuggestion(text) }
        fun tapAt(at: Int) { now += 1_000; ed.start = at; ed.end = at; ed.report() }
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}
