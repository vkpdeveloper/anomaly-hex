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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Telling autocorrect no: what counts as rejecting a fix, and what is left alone after one. */
class AutocorrectRejectionTest {
    private val grid = Glides.grid()

    // Deleting the fixed word later and typing what it replaced, where it was, is undoing the fix.
    @Test fun deletingAFixAndTypingTheWordAgainRejectsIt() {
        val k = Keyboard()
        k.tap("teh "); assertEquals("the ", k.field.text)
        k.tap("a"); k.engine.delete()
        repeat(4) { k.engine.delete() }
        assertEquals("", k.field.text)
        k.tap("teh ")
        assertEquals("teh ", k.field.text)
        assertTrue(k.prefs.blocked("en", "teh", "the"))
    }

    // Another word typed in the fix's place is just a new word.
    @Test fun anotherWordInTheFixsPlaceRejectsNothing() {
        val k = Keyboard()
        k.tap("teh "); k.tap("a"); k.engine.delete()
        repeat(4) { k.engine.delete() }
        k.tap("wprld ")
        assertEquals("world ", k.field.text)
        assertFalse(k.prefs.blocked("en", "teh", "the"))
    }

    // A sent message's last fix is gone with it: the same slip first in the next message is fixed again.
    @Test fun theSameSlipInTheNextMessageIsNoRejection() {
        val k = Keyboard()
        k.tap("teh "); assertEquals("the ", k.field.text)
        k.field.text = ""; k.field.setSelection(0, 0); k.engine.updateSelection(4, 4, 0, 0)
        k.tap("teh ")
        assertEquals("the ", k.field.text)
        assertFalse(k.prefs.blocked("en", "teh", "the"))
    }

    // Put back by delete and then changed by hand, the word is left as typed unless the fix is one slip off.
    @Test fun aWordEditedAfterItsUndoIsLeftAloneUnlessOneSlipOff() {
        assertEquals("world ", Keyboard().apply { tap("wprlf ") }.field.text)
        val k = Keyboard()
        k.tap("wprld "); assertEquals("world ", k.field.text)
        k.engine.delete(); assertEquals("wprld", k.field.text)
        k.engine.delete(); k.tap("f ")
        assertEquals("wprlf ", k.field.text)

        val slip = Keyboard()
        slip.tap("wprld "); slip.engine.delete()
        repeat(4) { slip.engine.delete() }
        slip.tap("orlf ")
        assertEquals("world ", slip.field.text)
    }

    private inner class Keyboard {
        val field = Field("")
        val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = false }
        val engine = KeyboardEngine(field, prefs, { Spoken.English }, { SpatialDecoder(model) }, learned = LearnedWords(), clock = { 0L })

        init {
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) {}
                override fun suggestions(slots: List<Suggestion>) {}
                override fun undo(offer: UndoOffer?) {}
            }
            engine.startInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; initialSelStart = 0; initialSelEnd = 0 })
            engine.opened()
            engine.configureTyping()
        }

        fun tap(text: String) {
            for (c in text) if (c == ' ') engine.space() else grid.centres.getValue(c).let { engine.letter(c.toString(), it.x, it.y, grid) }
        }
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}
