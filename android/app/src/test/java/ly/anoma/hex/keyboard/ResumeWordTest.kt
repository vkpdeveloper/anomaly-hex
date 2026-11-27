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
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deleting back into a word, or typing right after one, carries on that word: the strip completes it
 * instead of guessing the word after it. */
class ResumeWordTest {
    private val grid = Glides.grid()

    @Test fun deletingTheSpaceAfterAWordShowsThatWordsSuggestions() {
        val k = Keyboard()
        k.tap("hello world some"); k.engine.space()
        assertTrue(k.strip.all { it.kind == Suggestion.Kind.Next })
        k.engine.delete()
        assertEquals("hello world some", k.field.text)
        assertTrue("strip after delete: ${k.strip}", k.strip.isNotEmpty() && k.strip.none { it.kind == Suggestion.Kind.Next })
    }

    @Test fun aLetterAfterDeletingTheSpaceCarriesOnTheWord() {
        val k = Keyboard()
        k.tap("hello world some"); k.engine.space()
        k.engine.delete()
        k.tap("t")
        assertEquals("hello world somet", k.field.text)
        assertTrue("strip after t: ${k.strip}", k.strip.none { it.kind == Suggestion.Kind.Next })
        assertTrue("strip after t: ${k.strip}", k.strip.any { it.kind == Suggestion.Kind.Typed && it.text == "somet" })
    }

    @Test fun aWordFinishedAfterDeletingBackIsEndedByItsSpace() {
        val k = Keyboard()
        k.tap("hello world some"); k.engine.space()
        k.engine.delete()
        k.tap("thing"); k.engine.space()
        assertEquals("hello world something ", k.field.text)
        assertTrue(k.strip.all { it.kind == Suggestion.Kind.Next })
    }

    private inner class Keyboard {
        val field = Field("")
        val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = false }
        var strip = emptyList<Suggestion>()
        val engine = KeyboardEngine(field, prefs, { Spoken.English }, { SpatialDecoder(model) }, learned = LearnedWords(), clock = { 0L })

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
            for (c in letters) {
                if (c == ' ') { engine.space(); continue }
                grid.centres.getValue(c).let { engine.letter(c.toString(), it.x, it.y, grid) }
            }
        }
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}
