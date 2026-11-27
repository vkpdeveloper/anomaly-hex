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

/** Keep a typing log: each key, the strip after it and what was picked, saved only when flushed. */
class TypingLogTest {
    private val grid = Glides.grid()

    @Test fun keysStripsAndPicksAreRecordedInOrderAndSavedOnFlush() {
        val k = Keyboard()
        k.tap("hel")
        val offered = StripSlots.place(k.strip).map { it?.text }
        k.engine.pickSuggestion("hello")
        k.engine.delete()
        assertTrue("nothing saved before the flush", k.saved.isEmpty())
        k.log.flush()
        val lines = k.saved.single()
        val kinds = lines.map { Regex("\"e\":\"(\\w+)\"").find(it)!!.groupValues[1] }
        assertEquals(listOf("key", "key", "key"), kinds.filter { it == "key" })
        assertTrue(kinds.indexOf("pick") > kinds.lastIndexOf("key"))
        assertEquals("delete", kinds.last())
        val pick = lines.single { "\"e\":\"pick\"" in it }
        assertTrue(pick, "\"text\":\"hello\"" in pick && "\"slot\":${offered.indexOf("hello")}" in pick && "\"before\":\"hello\"" in pick)
        assertTrue(lines.any { "\"e\":\"strip\"" in it && "\"word\":\"hel\"" in it })
        assertTrue(lines.all { it.startsWith("{\"at\":\"10:15:30.000\"") })
    }

    @Test fun nothingIsRecordedInAPasswordField() {
        val k = Keyboard(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        k.tap("secret")
        k.log.flush()
        assertTrue(k.saved.isEmpty())
    }

    @Test fun nothingIsRecordedWhileItIsOff() {
        val k = Keyboard(on = false)
        k.tap("hel")
        k.log.flush()
        assertTrue(k.saved.isEmpty())
    }

    @Test fun textIsWrittenAsValidJsonStrings() {
        assertEquals("\"a \\\"b\\\" \\\\ c\\nd\"", TypingLog.json("a \"b\" \\ c\nd"))
        assertEquals("[\"x\",1,true]", TypingLog.json(listOf("x", 1, true)))
    }

    private inner class Keyboard(inputType: Int = InputType.TYPE_CLASS_TEXT, on: Boolean = true) {
        val field = Field("")
        val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = false }
        var strip = emptyList<Suggestion>()
        val saved = ArrayList<List<String>>()
        val log = TypingLog({ on }, { saved += it }, now = { LocalTime.of(10, 15, 30) })
        val engine = KeyboardEngine(field, prefs, { Spoken.English }, { SpatialDecoder(model) }, learned = LearnedWords(),
            typingLog = log, clock = { 0L })

        init {
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) {}
                override fun suggestions(slots: List<Suggestion>) { strip = slots }
                override fun undo(offer: UndoOffer?) {}
            }
            engine.startInput(EditorInfo().apply { this.inputType = inputType; initialSelStart = 0; initialSelEnd = 0 })
            engine.opened()
            engine.configureTyping()
        }

        fun tap(letters: String) {
            for (c in letters) grid.centres.getValue(c).let { engine.letter(c.toString(), it.x, it.y, grid) }
        }
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}
