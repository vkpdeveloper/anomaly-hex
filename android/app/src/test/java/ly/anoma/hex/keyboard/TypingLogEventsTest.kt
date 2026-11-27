package ly.anoma.hex.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import java.time.LocalTime
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

/** What the typing log records besides keys: emoji picks, pastes, and text the keyboard didn't type. */
class TypingLogEventsTest {
    private val grid = Glides.grid()

    @Test fun anEmojiPickAndAPasteAreRecorded() {
        val k = Keyboard()
        k.tap("hi "); k.engine.pickEmoji("😀"); k.engine.paste("see you")
        val lines = k.flushed()
        assertTrue(lines.any { "\"e\":\"pick\"" in it && "\"kind\":\"emoji\"" in it && "\"text\":\"😀\"" in it })
        assertTrue(lines.any { "\"e\":\"paste\"" in it && "\"chars\":7" in it })
    }

    // A send that clears the field, or an app's own paste at the end, is a change; a tap back into the text isn't.
    @Test fun textTheKeyboardDidNotTypeIsAChangeAndACaretMoveIsNot() {
        val k = Keyboard()
        k.tap("hello world"); k.settled()
        k.field.text += " again"; k.field.setSelection(17, 17); k.moved(11, 17)
        k.field.setSelection(5, 5); k.moved(17, 5)
        k.field.setSelection(8, 8); k.moved(5, 8)
        k.field.text = ""; k.field.setSelection(0, 0); k.moved(8, 0)
        assertEquals(listOf("changed", "caret", "caret", "changed"), k.flushed().map { kind(it) }.filter { it == "caret" || it == "changed" })
    }

    @Test fun aPasswordFieldRecordsNoChangeOrPaste() {
        val k = Keyboard(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        k.engine.paste("hunter2")
        k.field.text = ""; k.field.setSelection(0, 0); k.moved(7, 0)
        assertTrue(k.flushed().isEmpty())
    }

    private fun kind(line: String) = Regex("\"e\":\"(\\w+)\"").find(line)!!.groupValues[1]

    private inner class Keyboard(inputType: Int = InputType.TYPE_CLASS_TEXT) {
        var now = 0L
        val field = Field("")
        val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = false }
        val saved = ArrayList<List<String>>()
        val log = TypingLog({ true }, { saved += it }, now = { LocalTime.of(10, 15, 30) })
        val engine = KeyboardEngine(field, prefs, { Spoken.English }, { SpatialDecoder(model) }, learned = LearnedWords(),
            typingLog = log, clock = { now })

        init {
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) {}
                override fun suggestions(slots: List<Suggestion>) {}
                override fun undo(offer: UndoOffer?) {}
            }
            engine.startInput(EditorInfo().apply { this.inputType = inputType; initialSelStart = 0; initialSelEnd = 0 })
            engine.opened()
            engine.configureTyping()
        }

        fun tap(text: String) {
            for (c in text) if (c == ' ') engine.space() else grid.centres.getValue(c).let { engine.letter(c.toString(), it.x, it.y, grid) }
        }
        /** The editor reports the keyboard's own last edit, as Android does once a key's work is done. */
        fun settled() { val end = field.text.length; engine.updateSelection(end - 1, end - 1, end, end) }
        /** The caret moved from [from] to [to] well after the keyboard's last edit. */
        fun moved(from: Int, to: Int) { now += 1_000; engine.updateSelection(from, from, to, to) }
        fun flushed(): List<String> { log.flush(); return saved.flatten() }
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}
