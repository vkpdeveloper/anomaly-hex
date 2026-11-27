package ly.anoma.hex.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.Glides
import ly.anoma.hex.typing.LearnedWords
import ly.anoma.hex.typing.NextWordModel
import ly.anoma.hex.typing.NextWords
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences
import ly.anoma.hex.typing.TypingWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a word starts and ends: in a hyphenated word, after a quote, over a selection, before a word a glide
 * goes in front of, and which words count as words at all. */
class WordBoundsTest {
    private val grid = Glides.grid()

    @Test fun aHyphenatedWordGetsTheWordsAfterIt() {
        val k = Keyboard()
        k.keys("a well-known ")
        assertTrue(k.strip.toString(), k.strip.isNotEmpty() && k.strip.all { it.kind == Suggestion.Kind.Next })
    }

    @Test fun theLastPartOfAHyphenatedWordCompletes() {
        val k = Keyboard()
        k.keys("a well-kn")
        assertTrue(k.strip.toString(), k.strip.any { it.text == "known" })
        // A hyphen in a link still joins no word.
        val link = Keyboard()
        link.keys("https://my-si")
        assertEquals(emptyList<Suggestion>(), link.strip)
    }

    // A short part after a hyphen is seldom a slip ("hi-fi" became "hi-if"); a longer one is fixed.
    @Test fun aShortPartAfterAHyphenStands() {
        for (word in listOf("hi-fi", "anti-vax", "bf-gf")) {
            val k = Keyboard()
            k.keys("I said $word ")
            assertEquals("I said $word ", k.ed.text)
        }
        val k = Keyboard()
        k.keys("a well-knwn ")
        assertEquals("a well-known ", k.ed.text)
    }

    @Test fun aWordAfterAnOpeningQuoteIsFixed() {
        val k = Keyboard()
        k.keys("I said 'teh ")
        assertEquals("I said 'the ", k.ed.text)
    }

    @Test fun aLetterTypedOverASelectionJoinsTheWordBeforeIt() {
        val k = Keyboard("I walked home", caret = "I walk".length)
        k.ed.end = "I walked".length; k.ed.report()
        k.keys("i")
        assertEquals("I walki home", k.ed.text)
        assertTrue(k.strip.toString(), k.strip.any { it.kind == Suggestion.Kind.Typed && it.text == "walki" })
    }

    @Test fun aGlideBeforeAWordPutsASpaceBetween() {
        val k = Keyboard("hello world", caret = "hello ".length)
        k.glide("big")
        assertEquals("hello big world", k.ed.text)
        assertEquals("hello big".length, k.ed.start)
        // Space has nothing to add, and the next glide still gets its space first.
        k.keys(" ")
        assertEquals("hello big world", k.ed.text)
        k.glide("small")
        assertEquals("hello big small world", k.ed.text)
    }

    @Test fun deleteTakesAGlideBeforeAWordBackWithItsSpace() {
        val k = Keyboard("hello world", caret = "hello ".length)
        k.glide("big")
        k.key { k.engine.delete() }
        assertEquals("hello world", k.ed.text)
        assertEquals("hello ".length, k.ed.start)
    }

    // The next-word model reads "e.g." and "Dr." as words and guessed letters after them ("G", "K").
    @Test fun theNextWordModelGuessesNoLoneLetters() {
        for (text in listOf("I like ice. e.g. ", "I saw Dr. ")) {
            val k = Keyboard(neural = true)
            k.keys(text)
            assertTrue(k.strip.toString(), k.strip.isNotEmpty() && k.strip.all { it.kind == Suggestion.Kind.Next })
            assertTrue(k.strip.toString(), k.strip.none { it.text.length == 1 && it.text.lowercase() !in setOf("a", "i") })
        }
    }

    // "café" typed as people type it is a word (en.valid), not a slip for "cage".
    @Test fun anAccentedWordTypedWithoutItsAccentStands() {
        val k = Keyboard()
        k.keys("I went to a cafe ")
        assertEquals("I went to a cafe ", k.ed.text)
        // One a letter short of a common word ("nee", "need") is left out of the list, so space completes it.
        val nee = Keyboard()
        nee.keys("the nee ")
        assertEquals("the need ", nee.ed.text)
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

    private inner class Keyboard(text: String = "", caret: Int = text.length, neural: Boolean = false) {
        val ed = Editor(text, caret)
        val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = false }
        var strip = emptyList<Suggestion>()
        var now = 0L
        val engine = KeyboardEngine(ed, prefs, { Spoken.English }, { SpatialDecoder(model) }, learned = LearnedWords(), clock = { now },
            nextWords = if (neural) NextWords({ next }, { it.run() }) else null)

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

        fun key(block: () -> Unit) { now += 150; block(); ed.report() }
        fun keys(s: String) {
            for (c in s) {
                val p = grid.centres[c.lowercaseChar()]
                when {
                    c == ' ' -> key { engine.space() }
                    p != null -> key { engine.letter(c.toString(), p.x, p.y, grid) }
                    else -> key { engine.type(c.toString()) }
                }
            }
        }
        fun glide(word: String) = key { engine.glide(Glides.path(word, grid, smooth = .5 * grid.pitchX), grid) }
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
        val next by lazy { NextWordModel.file(File("src/main/assets/typing/next.hexnn")) }
    }
}
