package ly.anoma.hex.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import ly.anoma.hex.fix.Fix
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Fix chip shows on the strip only while the text has fixes to make, for the text that was checked. */
class FixChipTest {
    @Test fun showsWhenTheTextHasAFixAndNotWhenItIsClean() {
        val k = Keyboard()
        k.type("i will see you form the office")
        assertTrue(k.chip)
        val clean = Keyboard()
        clean.type("i will see you from the office")
        assertFalse(clean.chip)
    }

    @Test fun tappingItMakesTheFixesAndTheChipGoes() {
        val k = Keyboard()
        k.type("i will see you form the office")
        k.engine.applyFixOffer()
        assertEquals("i will see you from the office", k.field.text)
        assertFalse(k.chip)
    }

    @Test fun anEditDropsTheChipUntilTheNewTextIsChecked() {
        val k = Keyboard()
        k.type("i will see you form the office")
        assertTrue(k.chip)
        k.type(" x", check = false)
        assertFalse(k.chip)
        k.type("")
        assertTrue(k.chip)
        // The issue typed away: the next check finds nothing and the chip stays gone.
        k.field.text = k.field.text.replace("form", "from")
        k.type(" now", check = false)
        k.engine.findFixes()
        assertFalse(k.chip)
    }

    @Test fun movingTheCaretDropsTheChip() {
        val k = Keyboard()
        k.type("i will see you form the office")
        val end = k.field.text.length
        k.engine.updateSelection(end, end, 3, 3)
        assertFalse(k.chip)
    }

    @Test fun aCheckForOldTextShowsNothing() {
        val k = Keyboard(hold = true)
        k.type("i will see you form the office")
        val stale = k.held!!
        assertFalse(stale.cancelled())
        k.type(" now", check = false)
        assertTrue(stale.cancelled())
        stale.done(k.fixes)
        assertFalse(k.chip)
    }

    @Test fun neverWithTheSettingOffOrInAPasswordField() {
        val off = Keyboard(on = false)
        off.type("i will see you form the office")
        assertFalse(off.chip)
        val password = Keyboard(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        password.type("i will see you form the office")
        assertFalse(password.chip)
        assertEquals(0, password.checks)
    }

    @Test fun nothingIsCheckedForEmptyText() {
        val k = Keyboard()
        k.engine.findFixes()
        assertEquals(0, k.checks)
        assertFalse(k.chip)
    }

    private class Held(val cancelled: () -> Boolean, val done: (List<Fix>) -> Unit)

    private inner class Keyboard(on: Boolean = true, inputType: Int = InputType.TYPE_CLASS_TEXT, private val hold: Boolean = false) {
        val field = Field("")
        // What the finder reports: the one fix "form" -> "from", while the text still has "form".
        var fixes = emptyList<Fix>()
        var chip = false
        var checks = 0
        var held: Held? = null
        private val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = false }
        // Field has no selection: a commit replaces the range setSelection last chose, as an editor's does.
        private var chosen: IntRange? = null
        private val editor = object : KeyboardTextTarget by field {
            override fun setSelection(start: Int, end: Int): Boolean { chosen = start until end; return field.setSelection(start, end) }
            override fun commit(text: String): Boolean {
                chosen?.takeIf { !it.isEmpty() }?.let { field.text = field.text.removeRange(it.first, it.last + 1); field.setSelection(it.first, it.first) }
                chosen = null
                return field.commit(text)
            }
        }
        val engine = KeyboardEngine(editor, prefs, { Spoken.English }, { SpatialDecoder(model) }, clock = { 0L },
            fixFinder = { f, cancelled, done ->
                checks++
                fixes = Regex("\\bform\\b").findAll(f.text).map { Fix(it.range.first, it.range.last + 1, "form", "from") }.toList()
                if (hold) held = Held(cancelled, done) else done(fixes)
            },
            fixOn = { on })

        init {
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) {}
                override fun suggestions(slots: List<Suggestion>) {}
                override fun undo(offer: UndoOffer?) {}
                override fun fixOffer(shown: Boolean) { chip = shown }
            }
            engine.startInput(EditorInfo().apply { this.inputType = inputType; initialSelStart = 0; initialSelEnd = 0 })
            engine.opened()
            engine.configureTyping()
        }

        /** The text goes in as the editor would report it, then the typing pauses and the text is checked. */
        fun type(text: String, check: Boolean = true) {
            val before = field.text.length
            field.commit(text)
            engine.updateSelection(before, before, field.text.length, field.text.length)
            if (check) engine.findFixes()
        }
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}
