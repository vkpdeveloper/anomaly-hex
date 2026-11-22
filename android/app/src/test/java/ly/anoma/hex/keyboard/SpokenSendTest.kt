package ly.anoma.hex.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.LearnedWords
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Say "send it" to send: what counts as asking, and what the keyboard does then. */
class SpokenSendTest {
    @Test fun theLastWordsAloneOrAfterAPauseAskToSend() {
        assertEquals("", SpokenSend.strip("Send it."))
        assertEquals("", SpokenSend.strip("send it"))
        assertEquals("See you at six.", SpokenSend.strip("See you at six. Send it."))
        assertEquals("On my way", SpokenSend.strip("On my way, send it!"))
        assertEquals("Haan theek hai", SpokenSend.strip("Haan theek hai, bhej do."))
        assertEquals("ठीक है", SpokenSend.strip("ठीक है, भेज दो"))
    }

    @Test fun theWordsInsideASentenceStayText() {
        assertNull(SpokenSend.strip("I'll send it tomorrow."))
        assertNull(SpokenSend.strip("I'll send it."))
        assertNull(SpokenSend.strip("Can you send it?"))
        assertNull(SpokenSend.strip("Let me think… send it"))
        assertNull(SpokenSend.strip("Please resend it."))
        assertNull(SpokenSend.strip("Send."))
    }

    @Test fun aSendFieldTypesTheRestThenSendsWithNoUndo() {
        val k = Keyboard(EditorInfo.IME_ACTION_SEND)
        k.engine.insertDictation("See you at six. Send it.", "en")
        assertEquals("See you at six.", k.field.text)
        assertEquals(listOf(EditorInfo.IME_ACTION_SEND), k.field.actions)
        assertNull(k.undo)
    }

    @Test fun offOrWithoutASendKeyTheWordsAreTyped() {
        val off = Keyboard(EditorInfo.IME_ACTION_SEND, on = false)
        off.engine.insertDictation("Send it.", "en")
        assertEquals("Send it.", off.field.text)
        val search = Keyboard(EditorInfo.IME_ACTION_SEARCH)
        search.engine.insertDictation("Send it.", "en")
        assertEquals("Send it.", search.field.text)
        val password = Keyboard(EditorInfo.IME_ACTION_GO, type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        password.engine.insertDictation("send it", "en")
        assertEquals(emptyList<Int>(), off.field.actions + search.field.actions + password.field.actions)
    }

    // A field whose editor action is recorded.
    private class Sending(val inner: Field = Field("")) : KeyboardTextTarget by inner {
        val actions = mutableListOf<Int>()
        val text get() = inner.text
        override fun performEditorAction(id: Int) = actions.add(id)
    }

    private class Keyboard(action: Int, on: Boolean = true,
                           type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) {
        val field = Sending()
        var undo: UndoOffer? = null
        val prefs = TypingPreferences(Prefs.context()).also { it.sayToSend = on }
        val engine = KeyboardEngine(field, prefs, { Spoken.English }, { SpatialDecoder(model) },
            learned = LearnedWords(), clock = { 0L })

        init {
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) {}
                override fun suggestions(slots: List<ly.anoma.hex.typing.Suggestion>) {}
                override fun undo(offer: UndoOffer?) { undo = offer }
            }
            engine.startInput(EditorInfo().apply { inputType = type; imeOptions = action; initialSelStart = 0; initialSelEnd = 0 })
            engine.opened()
        }
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}
