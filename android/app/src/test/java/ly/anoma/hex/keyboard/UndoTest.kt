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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Undo and redo by two-finger swipe: what one step is, and what a swipe takes back. */
class UndoTest {
    @Test fun eachWordWithItsSpaceIsOneStep() {
        val k = Keyboard()
        k.chars("see you later")
        assertTrue(k.engine.undoStep()); assertEquals("see you ", k.field.text)
        assertTrue(k.engine.undoStep()); assertEquals("see ", k.field.text)
        assertTrue(k.engine.undoStep()); assertEquals("", k.field.text)
        assertFalse(k.engine.undoStep())
        assertTrue(k.engine.redoStep()); assertEquals("see ", k.field.text)
        assertTrue(k.engine.redoStep()); assertEquals("see you ", k.field.text)
    }

    @Test fun deletesAreAStepAndTypingAfterUndoEndsRedo() {
        val k = Keyboard()
        k.chars("good night")
        repeat(5) { k.engine.delete() }
        assertEquals("good ", k.field.text)
        k.engine.undoStep(); assertEquals("good night", k.field.text)
        k.engine.undoStep(); assertEquals("good ", k.field.text)
        k.chars("morning")
        assertFalse(k.engine.redoStep())
        k.engine.undoStep(); assertEquals("good ", k.field.text)
    }

    @Test fun aLongPauseStartsAnotherStepMidWord() {
        val k = Keyboard()
        k.chars("hel")
        k.now += 3_000
        k.chars("lo")
        k.engine.undoStep(); assertEquals("hel", k.field.text)
    }

    // The swipe's second finger types the first finger's key (Rollover): the swipe takes it back with the step before.
    @Test fun theKeyASwipeTypedGoesWithTheFirstUndo() {
        val k = Keyboard()
        k.chars("on my way ")
        k.now += 5_000
        val secondFinger = k.now
        k.chars("j")
        assertTrue(k.engine.undoStep(strayFrom = secondFinger))
        assertEquals("on my ", k.field.text)
        k.engine.redoStep(); assertEquals("on my way ", k.field.text)
    }

    @Test fun aKeyASwipeTypedMidWordNeverComesBack() {
        val k = Keyboard()
        k.chars("see you later")
        val secondFinger = k.now
        k.chars("u")
        assertTrue(k.engine.undoStep(strayFrom = secondFinger))
        assertEquals("see you ", k.field.text)
        k.engine.redoStep(); assertEquals("see you later", k.field.text)
    }

    @Test fun theKeyASwipeTypedDoesNotEndRedo() {
        val k = Keyboard()
        k.chars("on my way ")
        k.engine.undoStep(); assertEquals("on my ", k.field.text)
        k.now += 5_000
        val secondFinger = k.now
        k.chars("j")
        assertTrue(k.engine.redoStep(strayFrom = secondFinger))
        assertEquals("on my way ", k.field.text)
    }

    @Test fun aPasswordKeepsNoSteps() {
        val k = Keyboard(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        k.chars("hunter two")
        assertFalse(k.engine.undoStep())
        assertEquals("hunter two", k.field.text)
    }

    private class Keyboard(type: Int = InputType.TYPE_CLASS_TEXT) {
        var now = 0L
        val field = Field("")
        val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = false }
        val engine = KeyboardEngine(field, prefs, { Spoken.English }, { SpatialDecoder(model) }, clock = { now })

        init {
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) {}
                override fun suggestions(slots: List<Suggestion>) {}
                override fun undo(offer: UndoOffer?) {}
            }
            engine.startInput(EditorInfo().apply { inputType = type; initialSelStart = 0; initialSelEnd = 0 })
            engine.opened()
            engine.configureTyping()
        }

        fun chars(text: String) { for (c in text) { now += 100; if (c == ' ') engine.space() else engine.type(c.toString()) } }
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}
