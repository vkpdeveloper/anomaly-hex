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
import org.junit.Assert.assertEquals
import org.junit.Test

/** A picked word goes in with no space after it: the space waits for whatever comes next. */
class PickSpaceTest {
    private val grid = Glides.grid()

    @Test fun aPickedWordTypesNoSpaceAndTheNextWordPutsItIn() {
        val k = Keyboard()
        k.tap("hel")
        k.engine.pickSuggestion("hello")
        assertEquals("hello", k.field.text)
        k.tap("w")
        assertEquals("hello w", k.field.text)
    }

    @Test fun spaceAfterAPickTypesOneSpaceAndTwoMakeAFullStop() {
        val k = Keyboard()
        k.tap("hel")
        k.engine.pickSuggestion("hello")
        k.engine.space()
        assertEquals("hello ", k.field.text)
        k.engine.space()
        assertEquals("hello. ", k.field.text)
    }

    @Test fun aMarkGoesAgainstThePickedWord() {
        val k = Keyboard()
        k.tap("hel")
        k.engine.pickSuggestion("hello")
        k.engine.type(",")
        assertEquals("hello,", k.field.text)
    }

    @Test fun aNextWordPickedAfterAPickGetsItsSpace() {
        val k = Keyboard()
        k.tap("hel")
        k.engine.pickSuggestion("hello")
        val next = k.strip.first { it.kind == Suggestion.Kind.Next }.text
        k.engine.pickSuggestion(next)
        assertEquals("hello $next", k.field.text)
    }

    @Test fun deleteAfterAPickDeletesALetter() {
        val k = Keyboard()
        k.tap("hel")
        k.engine.pickSuggestion("hello")
        k.engine.delete()
        assertEquals("hell", k.field.text)
    }

    // The word counts once, as it's picked, however its space then goes in.
    @Test fun aPickIsLearnedOnceWhateverFollows() {
        for (then in listOf<(Keyboard) -> Unit>({ it.engine.space() }, { it.engine.type(".") }, { it.engine.enter() }, { it.tap("x") })) {
            val k = Keyboard()
            k.tap("zebr")
            k.engine.pickSuggestion("zebra")
            then(k)
            assertEquals(1, k.engine.stats.words)
        }
    }

    private inner class Keyboard {
        val field = Field("")
        val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = false }
        var strip = emptyList<Suggestion>()
        val learned = LearnedWords()
        val engine = KeyboardEngine(field, prefs, { Spoken.English }, { SpatialDecoder(model) }, learned = learned, clock = { 0L })

        init {
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) {}
                override fun suggestions(slots: List<Suggestion>) { strip = slots }
                override fun undo(offer: UndoOffer?) {}
            }
            engine.startInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; initialSelStart = 0; initialSelEnd = 0 })
            engine.opened()
            engine.configureTyping()
        }

        fun tap(letters: String) {
            for (c in letters) grid.centres.getValue(c).let { engine.letter(c.toString(), it.x, it.y, grid) }
        }
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}
