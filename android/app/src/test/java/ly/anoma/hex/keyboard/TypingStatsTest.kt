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
import ly.anoma.hex.typing.TypingStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine counts keys, suggestion taps, autocorrections and their undos, only where it learns. */
class TypingStatsTest {
    @Test fun countsWhatTheStripAndAutocorrectDo() {
        val k = Keyboard()
        k.tap("hell")
        assertTrue(k.strip.toString(), k.strip.any { it.text == "hello" })
        k.engine.pickSuggestion("hello")
        val s = k.engine.stats
        assertEquals(1, s.completions); assertEquals(0, s.predictions)
        // Four letters typed, and the tap put in the fifth and the space.
        assertEquals(4, s.keys); assertEquals(1, s.saved)
        k.tap("teh"); k.engine.space()
        assertEquals("hello the ", k.field.text)
        assertEquals(1, s.corrected)
        k.engine.delete()
        assertEquals(1, s.undone); assertEquals(1, s.backspaces)
        assertEquals(2, s.words)
    }

    @Test fun aPickRightAfterASpaceIsAPrediction() {
        val k = Keyboard()
        repeat(3) { k.tap("see"); k.engine.space(); k.tap("you"); k.engine.space(); k.tap("soon"); k.engine.space() }
        k.tap("see"); k.engine.space(); k.tap("you"); k.engine.space()
        assertEquals("soon", k.strip.first().text)
        val before = k.engine.stats.predictions
        k.engine.pickSuggestion("soon")
        assertEquals(before + 1, k.engine.stats.predictions)
    }

    @Test fun passwordAndIncognitoFieldsCountNothing() {
        for (k in listOf(Keyboard(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
            Keyboard(options = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))) {
            k.tap("teh"); k.engine.space(); k.engine.delete(); k.tap("hell"); k.engine.pickSuggestion("hello")
            assertEquals(TypingStats().write(), k.engine.stats.write())
        }
    }

    @Test fun savesAndReadsBackAndSaysNothingUntilThereIsEnough() {
        val stats = TypingStats().apply { words = 100; keys = 700; saved = 300; corrected = 40; undone = 2 }
        assertNull(stats.summary())
        stats.words = 400
        assertEquals("Suggestions saved you 30% of keystrokes. 5% of autocorrections were undone.", stats.summary())
        val again = TypingStats().also { it.read(stats.write()) }
        assertEquals(stats.write(), again.write())
        again.clear()
        assertEquals(TypingStats().write(), again.write())
    }

    private class Keyboard(type: Int = InputType.TYPE_CLASS_TEXT, options: Int = 0) {
        val grid = Glides.grid()
        val field = Field("")
        var strip = emptyList<Suggestion>()
        val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = false }
        val engine = KeyboardEngine(field, prefs, { Spoken.English }, { SpatialDecoder(model) }, learned = LearnedWords(), clock = { 0L })

        init {
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) {}
                override fun suggestions(slots: List<Suggestion>) { strip = slots }
                override fun undo(offer: UndoOffer?) {}
            }
            engine.startInput(EditorInfo().apply { inputType = type; imeOptions = options; initialSelStart = 0; initialSelEnd = 0 })
            engine.opened()
            engine.configureTyping()
        }

        fun tap(letters: String) { for (c in letters) { val p = grid.centres.getValue(c); engine.letter(c.toString(), p.x, p.y, grid) } }
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}
