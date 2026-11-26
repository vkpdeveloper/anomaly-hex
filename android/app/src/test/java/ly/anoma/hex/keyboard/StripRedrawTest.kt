package ly.anoma.hex.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.EmojiWords
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

/** The strip follows what changed around the caret: a slide on space, a tap soon after a key, shift, a
 * panel closing, a letter typed inside a word, and emoji next to words. */
class StripRedrawTest {
    private val grid = Glides.grid()

    @Test fun aSlideOnSpaceOffersTheWordItStopsIn() {
        val k = Keyboard("I like wrods here")
        k.slide(-"ods here".length)
        assertEquals("I like wr".length, k.ed.start)
        assertTrue("strip: ${k.strip}", k.strip.any { it.text == "words" })
        k.pick("words")
        assertEquals("I like words here", k.ed.text)
        // Also right after a key.
        val j = Keyboard("I like wrods here")
        j.space(); j.slide(-"ods here ".length)
        assertTrue("strip: ${j.strip}", j.strip.any { it.text == "words" })
    }

    @Test fun closingAPanelBringsBackTheStripOfAWordTappedInto() {
        for (at in listOf("I like wr".length, "I like wrods".length)) {
            val k = Keyboard("I like wrods here")
            k.tapAt(at)
            val before = k.strip
            assertTrue("strip: $before", before.any { it.text == "words" })
            k.engine.pause()
            assertTrue(k.strip.isEmpty())
            k.engine.configureTyping(keepWord = true)
            assertEquals(before.map { it.text }, k.strip.map { it.text })
            k.pick("words")
            assertEquals("I like words here", k.ed.text)
        }
    }

    @Test fun aTapInTheTextRightAfterAKeyOffersTheWordThere() {
        val k = Keyboard("I like wrods here")
        k.space()
        assertTrue("strip: ${k.strip}", k.strip.all { it.kind == Suggestion.Kind.Next })
        k.tapAt("I like wr".length, after = 100)
        assertTrue("strip: ${k.strip}", k.strip.any { it.text == "words" })
        // And while a word is being typed.
        k.tapAt("I like wrods here ".length); k.tap("abo")
        k.tapAt("I like wr".length, after = 100)
        assertTrue("strip: ${k.strip}", k.strip.any { it.text == "words" })
    }

    @Test fun aShiftTapCapitalisesTheNextWords() {
        for (caps in listOf(false, true)) {
            val k = Keyboard("", caps = caps)
            k.tap("hello"); k.space()
            val lower = k.strip.map { it.text }
            assertTrue("strip: $lower", lower.isNotEmpty() && lower.any { it.first().isLowerCase() })
            k.shift()
            assertEquals(lower.map { w -> w.replaceFirstChar { it.uppercase() } }, k.strip.map { it.text })
            k.shift()
            assertEquals(lower, k.strip.map { it.text })
        }
    }

    @Test fun shiftTurnedOffAtASentencesStartLowersTheNextWords() {
        val k = Keyboard("", caps = true)
        k.tap("ok"); k.type("."); k.space()
        assertTrue("strip: ${k.strip}", k.strip.any { it.text == "The" })
        k.shift()
        assertTrue("strip: ${k.strip}", k.strip.any { it.text == "the" } && k.strip.none { it.text == "The" })
    }

    @Test fun aWordTappedIntoGetsItsEmoji() {
        for (at in listOf("I like piz".length, "I like pizza".length)) {
            val k = Keyboard("I like pizza a lot")
            k.tapAt(at)
            assertTrue("strip: ${k.strip}", "🍕" in k.emoji)
            k.engine.pickEmoji("🍕")
            assertEquals("I like 🍕 a lot", k.ed.text)
            assertEquals("🍕", k.picks.of("pizza").single().first)
        }
    }

    @Test fun aLetterTypedInsideAWordOffersTheWholeWord() {
        val k = Keyboard("I like wods here", caret = "I like w".length)
        k.tap("r")
        assertEquals("I like wrods here", k.ed.text)
        assertTrue("strip: ${k.strip}", k.strip.any { it.text == "words" })
        k.pick("words")
        assertEquals("I like words here", k.ed.text)
    }

    @Test fun anEmojiAndASpaceGuessTheNextWord() {
        val k = Keyboard("")
        k.tap("hello"); k.space(); k.panelEmoji("😀"); k.space()
        assertEquals("hello 😀 ", k.ed.text)
        assertTrue("strip: ${k.strip}", k.strip.isNotEmpty() && k.strip.all { it.kind == Suggestion.Kind.Next })
        val next = k.strip.first().text
        k.pick(next)
        assertEquals("hello 😀 $next", k.ed.text)
    }

    @Test fun anEmojiPickedFromTheStripGuessesTheNextWord() {
        val k = Keyboard("")
        k.tap("pizza"); k.space()
        k.key { k.engine.pickEmoji("🍕") }
        assertEquals("pizza 🍕", k.ed.text)
        assertTrue("strip: ${k.strip}", k.strip.isNotEmpty() && k.strip.all { it.kind == Suggestion.Kind.Next })
        val next = k.strip.first().text
        k.pick(next)
        assertEquals("pizza 🍕 $next", k.ed.text)
    }

    @Test fun aWordRightAfterAnEmojiIsTypedAsAWord() {
        val k = Keyboard("")
        k.panelEmoji("😀"); k.tap("tomor")
        assertTrue("strip: ${k.strip}", k.strip.any { it.text == "tomorrow" })
        k.pick("tomorrow"); k.space()
        k.panelEmoji("👍"); k.tap("teh"); k.space()
        assertEquals("😀tomorrow 👍the ", k.ed.text)
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

    private inner class Keyboard(text: String, caret: Int = text.length, caps: Boolean = false) {
        val ed = Editor(text, caret)
        val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = caps }
        var strip = emptyList<Suggestion>()
        val emoji get() = strip.filter { it.kind == Suggestion.Kind.Emoji }.map { it.text }
        val picks = EmojiWords.Picks()
        var now = 0L
        val engine = KeyboardEngine(ed, prefs, { Spoken.English }, { SpatialDecoder(model) }, learned = LearnedWords(),
            emojiLists = { EmojiWords(listOf(lists)) }, emojiPicks = picks, clock = { now })

        init {
            ed.engine = engine
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) {}
                override fun suggestions(slots: List<Suggestion>) { strip = slots }
                override fun undo(offer: UndoOffer?) {}
            }
            engine.startInput(EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; initialSelStart = caret; initialSelEnd = caret
            })
            engine.opened()
            engine.configureTyping()
        }
        // Keys come a little apart; a tap in the text comes well after the last key unless said otherwise.
        fun key(block: () -> Unit) { now += 150; block(); ed.report() }
        fun tap(letters: String) {
            for (c in letters) key { grid.centres.getValue(c).let { engine.letter(if (engine.uppercase) c.uppercase() else c.toString(), it.x, it.y, grid) } }
        }
        fun type(text: String) = key { engine.type(text) }
        fun space() = key { engine.space() }
        fun delete() = key { engine.delete() }
        fun pick(text: String) = key { engine.pickSuggestion(text) }
        fun panelEmoji(emoji: String) = key { engine.emoji(emoji) }
        fun shift() { now += 1_000; engine.shiftDown(); engine.shiftUp() }
        fun tapAt(at: Int, after: Long = 1_000) { now += after; ed.start = at; ed.end = at; ed.report() }
        /** A slide on space [characters] to the right (left when negative), a step at a time. */
        fun slide(characters: Int) {
            now += 200
            val slide = engine.cursorSlide()
            val step = if (characters < 0) -1 else 1
            for (i in 1..kotlin.math.abs(characters)) { now += 30; slide.moveTo(i * step); ed.report() }
        }
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
        val lists by lazy { EmojiWords.parse(File("src/main/assets/typing/en.emoji").readText()) }
    }
}
