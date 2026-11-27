package ly.anoma.hex.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which page the keys show after ?123, and the gap between keycaps with autocorrect off. */
class SymbolPageTest {
    @Test fun aSpaceAfterASymbolGoesBackToTheLetters() {
        val k = Keyboard()
        k.engine.mode()
        assertTrue(k.page.symbols)
        k.engine.type("?"); k.engine.space()
        assertFalse(k.page.symbols)
        assertEquals("? ", k.field.text)
    }

    @Test fun aSpaceBeforeAnySymbolStaysOnTheSymbols() {
        val k = Keyboard()
        k.engine.mode(); k.engine.space()
        assertTrue(k.page.symbols)
        // The second page too, once something is typed there.
        k.engine.shiftUp(); k.engine.type("€"); k.engine.space()
        assertFalse(k.page.symbols)
    }

    @Test fun aNumberFieldKeepsItsNumberPad() {
        val k = Keyboard(InputType.TYPE_CLASS_NUMBER)
        k.engine.type("5"); k.engine.space()
        assertTrue(k.page.numpad)
    }

    // A touch in the gap between "d" and "e" after "th" reads as "e", with autocorrect on or off.
    @Test fun aTouchInTheGapUsesTheWordsWithAutocorrectOff() {
        for (autocorrect in listOf(true, false)) {
            val k = Keyboard(autocorrect = autocorrect)
            k.engine.type("th")
            assertEquals("autocorrect $autocorrect", 'e', k.engine.likelierLetter('d', mapOf('e' to 2.0), "qwertyuiopasdfghjklzxcvbnm".toList()))
        }
    }

    private class Keyboard(type: Int = InputType.TYPE_CLASS_TEXT, autocorrect: Boolean = true) {
        val field = Field("")
        val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = false; it.autocorrect = autocorrect }
        lateinit var page: KeyboardPage
        val engine = KeyboardEngine(field, prefs, { Spoken.English }, { SpatialDecoder(model) }, clock = { 0L })

        init {
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) { this@Keyboard.page = page }
                override fun suggestions(slots: List<ly.anoma.hex.typing.Suggestion>) {}
                override fun undo(offer: UndoOffer?) {}
            }
            engine.startInput(EditorInfo().apply { inputType = type; initialSelStart = 0; initialSelEnd = 0 })
            engine.opened()
            engine.configureTyping()
        }
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}
