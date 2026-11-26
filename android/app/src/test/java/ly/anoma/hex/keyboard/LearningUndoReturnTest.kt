package ly.anoma.hex.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.Glides
import ly.anoma.hex.typing.KeyGrid
import ly.anoma.hex.typing.KeyPoint
import ly.anoma.hex.typing.KeyRect
import ly.anoma.hex.typing.LearnedWords
import ly.anoma.hex.typing.PersonalWords
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences
import ly.anoma.hex.typing.TypingWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a word counts, and what taking it back takes back: a word tapped into, a pick, a phrase and a
 * swipe undo; and what space still fixes when one of its fixes was undone before. */
class LearningUndoReturnTest {
    private val grid = Glides.grid()

    // The word was in the text already: ending it again as it stands types nothing new.
    @Test fun aWordTappedAtItsEndAndEndedBySpaceCountsNothing() {
        val k = Keyboard("see you tomorow", caret = 0)
        k.tapAt("see you tomorow".length); k.space()
        assertEquals("see you tomorow ", k.ed.text)
        assertEquals(0, k.uses("tomorow"))
        k.tapAt(0); k.tapAt("see you tomorow".length); k.space()
        assertFalse("tomorow" in k.learned.words())
    }

    // "your welcom": the strip's fix is "welcome", and space puts it in though "you're welcome" was undone once.
    @Test fun aBlockedRevisionStillLetsTheWordsOwnFixIn() {
        val k = Keyboard()
        k.prefs.undo("en", "your welcom", "you're welcome")
        k.tap("your welcom"); k.space()
        assertEquals("your welcome ", k.ed.text)
    }

    // A pick of the word as typed makes it yours at once; deleted, it takes that back.
    @Test fun aPickOfTheTypedWordDeletedTakesBackWhatItTaught() {
        val k = Keyboard()
        k.tap("tomorow"); k.pick("tomorow")
        assertTrue("tomorow" in k.learned.words())
        repeat("tomorow".length) { k.delete() }
        assertEquals("", k.ed.text)
        assertEquals(0, k.uses("tomorow"))
    }

    @Test fun aPickedPhraseDeletedTakesBackEveryWord() {
        val k = Keyboard()
        k.tap("se"); k.pick("see you")
        assertEquals(1, k.uses("see")); assertEquals(1, k.uses("you"))
        repeat("see you".length) { k.delete() }
        assertEquals("", k.ed.text)
        assertEquals(0, k.uses("see")); assertEquals(0, k.uses("you"))
    }

    // A swipe undo takes the word back with its space, so what the space counted goes with it.
    @Test fun aSwipeUndoTakesBackTheWordsCount() {
        val k = Keyboard()
        repeat(2) {
            k.tap("later"); k.space()
            assertTrue(k.engine.undoStep())
            assertEquals("", k.ed.text)
        }
        assertEquals(0, k.uses("later"))
        // Undoing what came after it leaves the count: a word typed after it, or deletes that stopped at its space.
        k.tap("later"); k.space(); k.tap("on")
        assertTrue(k.engine.undoStep())
        assertEquals("later ", k.ed.text)
        k.tap("x"); k.delete()
        assertTrue(k.engine.undoStep())
        assertEquals("later x", k.ed.text)
        assertEquals(1, k.uses("later"))
    }

    // A redo puts the word back with its space, and what the space counted with it.
    @Test fun aSwipeRedoCountsTheWordAgain() {
        val k = Keyboard()
        k.tap("later"); k.space()
        assertTrue(k.engine.undoStep())
        assertEquals(0, k.uses("later"))
        assertTrue(k.engine.redoStep())
        assertEquals("later ", k.ed.text)
        assertEquals(1, k.uses("later"))
        assertTrue(k.engine.undoStep())
        assertEquals(0, k.uses("later"))
    }

    @Test fun aWordDeletedBackIntoAndEndedAgainCountsOnce() {
        val k = Keyboard()
        k.tap("later"); k.space(); k.tap("then"); k.space()
        repeat("then ".length + 1) { k.delete() }
        assertEquals("later", k.ed.text)
        k.space()
        assertEquals(1, k.uses("later"))
        // A word the field had already counts nothing; one changed counts.
        val j = Keyboard("see you soon ")
        j.delete(); j.space()
        assertEquals(0, j.uses("soon"))
        j.delete(); j.tap("er"); j.space()
        assertEquals("see you sooner ", j.ed.text)
        assertEquals(1, j.uses("sooner"))
    }

    // Its fix ("commit") undone before, "commu" stays as typed, and still joins the next word when its space was a letter.
    @Test fun aWordWhoseFixIsBlockedStillJoinsTheNextWord() {
        val k = Keyboard()
        k.prefs.undo("en", "commu", "commit")
        k.tap("so commu", bar); k.space(KeyPoint(bar.centres.getValue('n').x, 207.5))
        assertEquals("so commu ", k.ed.text)
        k.tap("ity", bar); k.space(KeyPoint(184.0, 234.0))
        assertEquals("so community ", k.ed.text)
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

    private inner class Keyboard(text: String = "", caret: Int = text.length) {
        val ed = Editor(text, caret)
        val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = false }
        val learned = LearnedWords()
        var strip = emptyList<Suggestion>()
        var now = 0L
        val engine = KeyboardEngine(ed, prefs, { Spoken.English }, { SpatialDecoder(model) },
            readPersonal = { m, mine -> PersonalWords(m, mine, emptyMap()) }, learned = learned, clock = { now })

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
        fun tap(letters: String, on: KeyGrid = grid) {
            for (c in letters) key { if (c == ' ') engine.space() else on.centres.getValue(c).let { engine.letter(c.toString(), it.x, it.y, on) } }
        }
        fun space(at: KeyPoint? = null) = key { engine.space(at, at?.let { bar }) }
        fun pick(text: String) = key { engine.pickSuggestion(text) }
        fun delete() = key { engine.delete() }
        fun tapAt(at: Int) { now += 1_000; ed.start = at; ed.end = at; ed.report() }
        /** How many times [word] was counted as typed. */
        fun uses(word: String) = learned.write().lines().firstOrNull { it.startsWith("w\t") && it.split('\t')[1].equals(word, ignoreCase = true) }
            ?.split('\t')?.get(2)?.toInt() ?: 0
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
        // Keys as the decoder tests lay them out, with the space bar's place, so a space tapped at its edge reads as a letter.
        val bar = KeyGrid(buildMap {
            for ((row, letters) in listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").withIndex()) for ((i, c) in letters.withIndex())
                put(c, KeyPoint(201 + (i - (letters.length - 1) / 2.0) * 39.5, 66.5 + 56 * row))
        }, 39.5, 56.0, KeyRect(64.76, 212.0, 303.1, 257.0))
    }
}
