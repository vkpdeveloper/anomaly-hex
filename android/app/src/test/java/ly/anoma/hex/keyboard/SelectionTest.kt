package ly.anoma.hex.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shift held while sliding on space selects, and a selection puts its actions on the strip. */
class SelectionTest {
    @Test fun aSelectingSlideGrowsTheSelectionTheWayItGoes() {
        val field = Selecting("hello big world", caret = 6)
        val slide = CursorSlide(field, 6, 6, readable = true, selecting = true)
        slide.moveTo(3)
        assertEquals(6 to 9, field.selection)
        slide.moveTo(-2)
        assertEquals(4 to 6, field.selection)
        slide.moveTo(0)
        assertEquals(6 to 6, field.selection)
    }

    @Test fun aSelectionOffersCutAndCopyAndClearsThemAfter() {
        val k = Keyboard("see you at six")
        k.engine.updateSelection(14, 14, 4, 7)
        assertEquals(StripAction.selection, k.actions)
        k.engine.updateSelection(4, 7, 7, 7)
        assertTrue(k.actions.isEmpty())
    }

    @Test fun aPasswordOffersNothing() {
        val k = Keyboard("hunter2", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        k.engine.updateSelection(7, 7, 0, 7)
        assertTrue(k.actions.isEmpty())
    }

    @Test fun anInsertChipReplacesWhatWasTypedAndSelectsItsBlank() {
        val k = Keyboard("45*12")
        k.engine.act(StripAction.Insert("= 540", replace = 5, text = "540"))
        assertEquals("540", k.field.text)
    }

    @Test fun aSumBeforeTheCaretOffersItsAnswer() {
        val k = Keyboard("45*1")
        k.engine.type("2")
        k.engine.updateSelection(4, 4, 5, 5)
        assertEquals(listOf(StripAction.Insert("= 540", 5, "540")), k.actions)
    }

    @Test fun aCurrencyAfterAnAmountIsNeverCorrected() {
        val k = Keyboard("")
        for (c in "250 usd ") if (c == ' ') k.engine.space() else k.engine.type(c.toString())
        assertEquals("250 usd ", k.field.text)
    }

    // A field whose selection is kept, as an editor's is.
    private class Selecting(var text: String, caret: Int) : KeyboardTextTarget by Field(text) {
        var selection = caret to caret
        override fun after(length: Int) = text.substring(selection.second, minOf(text.length, selection.second + length))
        override fun before(length: Int) = text.substring(maxOf(0, selection.first - length), selection.first)
        override fun setSelection(start: Int, end: Int): Boolean { selection = start to end; return true }
    }

    private class Keyboard(text: String, type: Int = InputType.TYPE_CLASS_TEXT) {
        val field = Field(text)
        var actions = emptyList<StripAction>()
        val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = false }
        val engine = KeyboardEngine(field, prefs, { Spoken.English }, { SpatialDecoder(model) }, clock = { 0L })

        init {
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) {}
                override fun suggestions(slots: List<Suggestion>) {}
                override fun undo(offer: UndoOffer?) {}
                override fun actions(list: List<StripAction>) { actions = list }
            }
            engine.startInput(EditorInfo().apply { inputType = type; initialSelStart = text.length; initialSelEnd = text.length })
            engine.opened()
            engine.configureTyping()
        }
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}
