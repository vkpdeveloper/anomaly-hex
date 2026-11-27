package ly.anoma.hex.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.LearnedWords
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the keyboard learns of the two words before each one, through the engine. */
class PredictionLearningTest {
    @Test fun anOrdinaryFieldLearnsYourPhrasesAndOffersThemNextTime() {
        val k = Keyboard()
        repeat(3) { k.chars("see you soon. ") }
        assertEquals(listOf("soon"), k.learned.next("see", "you"))
        k.chars("see you ")
        assertEquals("soon", k.strip.first().text)
    }

    @Test fun passwordAndIncognitoFieldsLearnNoPhrases() {
        for (k in listOf(Keyboard(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
            Keyboard(options = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING),
            Keyboard(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))) {
            repeat(3) { k.chars("see you soon. ") }
            assertTrue(k.learned.next("see", "you").isEmpty())
            assertEquals("", k.learned.write())
        }
    }

    private inner class Keyboard(type: Int = InputType.TYPE_CLASS_TEXT, options: Int = 0) {
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
            engine.startInput(EditorInfo().apply { inputType = type; imeOptions = options; initialSelStart = 0; initialSelEnd = 0 })
            engine.opened()
            engine.configureTyping()
        }

        // Characters as committed text, space as the space bar, so nothing is autocorrected.
        fun chars(text: String) { for (c in text) if (c == ' ') engine.space() else engine.type(c.toString()) }
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}
