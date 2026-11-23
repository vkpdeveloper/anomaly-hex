package ly.anoma.hex.keyboard

import android.view.inputmethod.EditorInfo
import java.io.File
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.Glides
import ly.anoma.hex.typing.LearnedWords
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.typing.TouchOffsets
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences
import ly.anoma.hex.typing.TypoMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slips you fix yourself are corrected the same way next time; a swap you undo is not made again; contractions are left whole. */
class TypoLearningTest {
    @Test fun aSlipFixedByHandFromTheStripIsRemembered() {
        val k = Keyboard()
        k.word("tehn"); k.engine.pickSuggestion("then")
        assertEquals("then", k.typos.fix("tehn")?.word)
    }

    @Test fun aSpellingYouKeepIsNeverCorrectedAgain() {
        val typos = TypoMemory()
        typos.learn("tehn", "then", TypoMemory.OWN)
        typos.learn("tehn", "then", TypoMemory.OWN)
        assertNotNull(typos.fix("tehn"))
        typos.forget("tehn")
        assertNull(typos.fix("tehn"))
    }

    @Test fun anUndoneSwapIsOnlyThatSwap() {
        val typos = TypoMemory()
        typos.learn("tehn", "then", TypoMemory.OWN)
        typos.learn("tehn", "teen", TypoMemory.OWN)
        typos.learn("tehn", "teen", TypoMemory.OWN)
        typos.remove("tehn", "then")
        assertEquals("teen", typos.fix("tehn")?.word)
        typos.remove("tehn", "teen")
        assertNull(typos.fix("tehn"))
        typos.learn("wrod", "word", TypoMemory.OWN)
        typos.learn("wrod", "word", TypoMemory.OWN)
        assertEquals("word", typos.fix("wrod")?.word)
    }

    @Test fun onlySlipsAreKeptAndTheyRoundTrip() {
        val typos = TypoMemory()
        assertFalse(typos.learn("cat", "elephant", TypoMemory.OWN))
        assertFalse(typos.learn("the", "then", TypoMemory.OWN))
        typos.learn("tehn", "then", TypoMemory.OWN)
        val again = TypoMemory().also { it.read(typos.write()) }
        assertEquals("then", again.fix("tehn")?.word)
        assertEquals(1, again.size())
    }

    @Test fun anUndoneAutocorrectionBlocksThatSwapOnce() {
        val k = Keyboard()
        k.word("teh"); k.engine.space()
        assertEquals("the ", k.field.text)
        k.engine.delete()
        assertEquals("teh", k.field.text)
        assertTrue(k.prefs.blocked("en", "teh", "the"))
        assertFalse(k.prefs.blocked("en", "teh", "them"))
        // The word the swap made is not banned: another slip of it is still corrected.
        k.engine.space(); k.word("thw"); k.engine.space()
        assertEquals("teh the ", k.field.text)
    }

    @Test fun anApostropheTypedInsideAWordKeepsItWhole() {
        val k = Keyboard()
        k.word("cna"); k.engine.type("'"); k.word("t"); k.engine.space()
        assertEquals("can't ", k.field.text)
    }

    @Test fun aPossessiveOfARealWordIsLeftAlone() {
        val k = Keyboard()
        k.word("dog"); k.engine.type("'"); k.word("s"); k.engine.space()
        assertEquals("dog's ", k.field.text)
    }

    private class Keyboard {
        val grid = Glides.grid()
        val field = Field("")
        val typos = TypoMemory()
        val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = false }
        val engine = KeyboardEngine(field, prefs, { Spoken.English }, { SpatialDecoder(model) }, learned = LearnedWords(),
            touch = TouchOffsets(), typos = typos, clock = { 0L })

        init {
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) {}
                override fun suggestions(slots: List<Suggestion>) {}
                override fun undo(offer: UndoOffer?) {}
            }
            engine.startInput(EditorInfo().apply { inputType = android.text.InputType.TYPE_CLASS_TEXT; initialSelStart = 0; initialSelEnd = 0 })
            engine.opened()
            engine.configureTyping()
        }

        /** Each letter tapped on its key's centre. */
        fun word(text: String) {
            for (c in text) { val p = grid.centres.getValue(c); engine.letter(c.toString(), p.x, p.y, grid) }
        }
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}
