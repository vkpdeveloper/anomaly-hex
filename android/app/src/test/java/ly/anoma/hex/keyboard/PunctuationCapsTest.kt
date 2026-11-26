package ly.anoma.hex.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.Glides
import ly.anoma.hex.typing.LearnedWords
import ly.anoma.hex.typing.PersonalWords
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Capitals and periods typed the way people type them: caps lock, abbreviations, links. */
class PunctuationCapsTest {
    private val grid = Glides.grid()

    @Test fun aDictionaryWordTypedInCapsLockIsntLearnedInCapitals() {
        val k = Keyboard(caps = true)
        repeat(3) { k.keys("we have a meeting today. ") }
        k.keys("we have a "); k.capsLock("MEETING TODAY"); k.keys(". ")
        k.keys("a mee")
        assertTrue("strip: ${k.strip}", k.strip.none { it.text == "MEETING" } && k.strip.any { it.text == "meeting" })
        repeat(2) { k.keys("ting. we went to "); k.capsLock("NASA"); k.keys(" now. a mee") }
        k.newMessage()
        k.keys("a meeting ")
        assertTrue("strip: ${k.strip}", k.strip.none { it.text == "TODAY" } && k.strip.any { it.text == "today" })
        // An acronym the dictionary lacks is still yours in capitals.
        k.keys("at na")
        assertTrue("strip: ${k.strip}", k.strip.any { it.text == "NASA" })
    }

    @Test fun aLetterRightAfterAPeriodTakesBackThePeriodsFix() {
        for ((typed, expected) in listOf("that is i.e. " to "That is i.e. ", "go to anoma.ly" to "Go to anoma.ly",
            "so do i. " to "So do I. ", "teh. " to "The. ")) {
            val k = Keyboard(caps = true)
            k.keys(typed)
            assertEquals(expected, k.field.text)
        }
    }

    @Test fun capsLockShowsTheStripInCapitals() {
        val k = Keyboard(caps = true)
        k.shift(); k.shift()
        k.keys("HELLO ")
        assertTrue("strip: ${k.strip}", k.strip.isNotEmpty() && k.strip.all { it.text == it.text.uppercase() })
        k.keys("T")
        assertTrue("strip: ${k.strip}", k.strip.size > 1 && k.strip.all { it.text == it.text.uppercase() })
        // Off again, its words lose the capitals.
        k.shift()
        assertTrue("strip: ${k.strip}", k.strip.size > 1 && k.strip.none { it.text.length > 1 && it.text == it.text.uppercase() })
    }

    private inner class Keyboard(caps: Boolean) {
        val field = Field("")
        val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = caps }
        var strip = emptyList<Suggestion>()
        val engine = KeyboardEngine(field, prefs, { Spoken.English }, { SpatialDecoder(model) },
            readPersonal = { m, mine -> PersonalWords(m, mine, emptyMap()) }, learned = LearnedWords(), clock = { 0L })
        private var caret = 0

        init {
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) {}
                override fun suggestions(slots: List<Suggestion>) { strip = slots }
                override fun undo(offer: UndoOffer?) {}
            }
            open()
        }

        private fun open() {
            engine.startInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES })
            engine.opened()
            engine.configureTyping()
        }
        /** The message went and another starts in a new field. */
        fun newMessage() { field.text = ""; field.setSelection(0, 0); caret = 0; open() }
        // The editor reports each edit's caret, as Android does.
        private fun sync() {
            val now = field.all().selStart
            if (now != caret) { val was = caret; caret = now; engine.updateSelection(was, was, now, now) }
        }
        fun shift() { engine.shiftDown(); engine.shiftUp() }
        /** Shift twice for caps lock, [text], then shift once to lift it. */
        fun capsLock(text: String) { shift(); shift(); keys(text); shift() }

        fun keys(s: String) {
            for (c in s) {
                val p = grid.centres[c.lowercaseChar()]
                when {
                    c == ' ' -> engine.space()
                    p != null -> engine.letter(if (c.isUpperCase() || engine.uppercase) c.uppercase() else c.toString(), p.x, p.y, grid)
                    else -> engine.type(c.toString())
                }
                sync()
            }
        }
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}
