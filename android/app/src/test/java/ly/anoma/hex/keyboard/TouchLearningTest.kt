package ly.anoma.hex.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.Glides
import ly.anoma.hex.typing.KeyPoint
import ly.anoma.hex.typing.LearnedWords
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.typing.TouchOffsets
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine learns where taps land from the words it finishes, and only where it may learn at all. */
class TouchLearningTest {
    private val grid = Glides.grid()
    private val sentence = "hello world and this is the time we have been waiting for after all "

    @Test fun finishedWordsTeachWhereTheThumbLands() {
        val k = Keyboard()
        k.type(sentence.repeat(8))
        val o = k.touch.offset('e')
        assertTrue("${o.x}", o.x > .15)
        assertTrue("${o.y}", o.y > .15)
    }

    @Test fun passwordFieldsTeachNothing() {
        val k = Keyboard(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        k.type(sentence.repeat(8))
        assertEquals("hex-touch 1\n", k.touch.write())
    }

    @Test fun fieldsThatAskForNoPersonalisationTeachNothing() {
        val k = Keyboard(options = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        k.type(sentence.repeat(8))
        assertEquals("hex-touch 1\n", k.touch.write())
    }

    private class Keyboard(type: Int = InputType.TYPE_CLASS_TEXT, options: Int = 0) {
        val grid = Glides.grid()
        val field = Field("")
        val touch = TouchOffsets()
        val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = false }
        val engine = KeyboardEngine(field, prefs, { Spoken.English }, { SpatialDecoder(model) }, learned = LearnedWords(),
            touch = touch, clock = { 0L })

        init {
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) {}
                override fun suggestions(slots: List<Suggestion>) {}
                override fun undo(offer: UndoOffer?) {}
            }
            engine.startInput(EditorInfo().apply { inputType = type; imeOptions = options; initialSelStart = 0; initialSelEnd = 0 })
            engine.opened()
            engine.configureTyping()
        }

        /** Every letter tapped a quarter key low and right of its centre. */
        fun type(text: String) {
            for (c in text) {
                if (c == ' ') { engine.space(); continue }
                val p = grid.centres.getValue(c)
                engine.letter(c.toString(), p.x + .25 * grid.pitchX, p.y + .25 * grid.pitchY, grid)
            }
        }
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}
