package ly.anoma.hex.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.Glides
import ly.anoma.hex.typing.LearnedWords
import ly.anoma.hex.typing.PersonalWords
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the strip offers and space corrects once you go back over a word: one put back by delete or undo
 * and then changed, one deleted back to its space, and one ended by return and taken up again. */
class CorrectionAfterEditTest {
    private val grid = Glides.grid()

    // A word delete picked back up is left as it stands, but letters typed onto it make a word being typed again.
    @Test fun aWordTakenUpAgainAndChangedIsCorrectedBySpace() {
        val k = Keyboard()
        k.tap("see you some "); k.engine.delete()
        k.tap("thng"); k.engine.space()
        assertEquals("see you something ", k.field.text)
    }

    // The word an undone autocorrection put back isn't corrected again, but a new word typed from it is.
    @Test fun aWordPutBackByUndoAndChangedIsCorrectedBySpace() {
        val k = Keyboard()
        k.tap("see you teh "); k.engine.delete()
        assertEquals("see you teh", k.field.text)
        k.engine.delete(); k.tap("e")
        k.engine.space()
        assertEquals("see you tee ", k.field.text)
        val again = Keyboard()
        again.tap("see you teh "); again.engine.delete()
        again.engine.delete(); again.engine.delete(); again.tap("hw ")
        assertEquals("see you the ", again.field.text)
    }

    @Test fun deletingAWordBackToItsSpaceOffersTheNextWordsAgain() {
        val k = Keyboard()
        k.tap("see you ")
        val next = k.strip
        assertTrue(next.isNotEmpty() && next.all { it.kind == Suggestion.Kind.Next })
        k.tap("t"); k.engine.delete()
        assertEquals("see you ", k.field.text)
        assertEquals(next, k.strip)
        k.tap("ther"); repeat(4) { k.engine.delete() }
        assertEquals(next, k.strip)
    }

    @Test fun aNextWordPickedThenDeletedOffersTheNextWordsAgain() {
        val k = Keyboard()
        k.tap("see ")
        val next = k.strip
        val picked = next.first().text
        k.engine.pickSuggestion(picked)
        repeat(picked.length) { k.engine.delete() }
        assertEquals("see ", k.field.text)
        assertEquals(next, k.strip)
    }

    // A word Return ends as typed, taken back up by delete and ended again, counts once, as a space's word
    // does, so it doesn't become one of your words.
    @Test fun aWordEndedByReturnThenTakenBackCountsOnce() {
        val k = Keyboard()
        k.tap("see you zorbly"); k.engine.enter()
        assertEquals("see you zorbly\n", k.field.text)
        k.engine.delete()
        assertEquals("see you zorbly", k.field.text)
        k.engine.space()
        assertFalse("zorbly" in k.learned.words())
    }

    // Return makes the fix space would, then breaks the line; delete right after puts the typed word back.
    @Test fun returnFixesASlipAndDeleteTakesItBack() {
        val k = Keyboard()
        k.tap("see you tomorow"); k.engine.enter()
        assertEquals("see you tomorrow\n", k.field.text)
        k.engine.delete()
        assertEquals("see you tomorow", k.field.text)
        k.engine.space()
        assertFalse("tomorow" in k.learned.words())
        // Undone, it isn't changed again for a while.
        k.tap("tomorow ")
        assertEquals("see you tomorow tomorow ", k.field.text)
    }

    // The same for a glided word: it counted when the next letter put its space in.
    @Test fun aGlidedWordTakenBackCountsOnce() {
        val k = Keyboard()
        k.glide("hello"); k.tap("w")
        assertEquals("hello w", k.field.text)
        k.engine.delete(); k.engine.delete(); k.engine.space()
        assertEquals("hello ", k.field.text)
        assertFalse("hello" in k.learned.words())
    }

    private inner class Keyboard {
        val field = Field("")
        val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = false }
        var strip = emptyList<Suggestion>()
        val learned = LearnedWords()
        val engine = KeyboardEngine(field, prefs, { Spoken.English }, { SpatialDecoder(model) },
            readPersonal = { m, mine -> PersonalWords(m, mine, emptyMap()) }, learned = learned, clock = { 0L })

        init {
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) {}
                override fun suggestions(slots: List<Suggestion>) { strip = slots }
                override fun undo(offer: UndoOffer?) {}
            }
            engine.startInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; initialSelStart = 0; initialSelEnd = 0 })
            engine.opened()
            engine.configureTyping()
        }

        fun tap(letters: String) {
            for (c in letters) {
                if (c == ' ') { engine.space(); continue }
                grid.centres.getValue(c).let { engine.letter(c.toString(), it.x, it.y, grid) }
            }
        }
        fun glide(word: String) = engine.glide(Glides.path(word, grid, smooth = .5 * grid.pitchX), grid)
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}
