package ly.anoma.hex.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetsTest {
    private val eta = Snippet(";eta", "There in {10} min")
    private val addr = Snippet(";addr", "12 Park Street, Kolkata")
    private val brb = Snippet("brb", "Be right back")
    private val all = listOf(eta, addr, brb)

    @Test fun theBracesGoAndTheFirstBlankIsSelected() {
        assertEquals("There in 10 min" to 9..10, eta.filled())
        assertEquals("Hi , I'm here" to (3 until 3), Snippet("x", "Hi {}, I'm {here}").filled())
    }

    @Test fun aMarkAndOneLetterOfferTheShortcutsThatStartSo() {
        assertEquals(listOf(StripAction.Insert("There in 10 min", 2, "There in 10 min", 9..10)), Snippets.offer("ok ;e", all))
        assertEquals(listOf("12 Park Street, Kolkata"), Snippets.offer("x ;a", all).map { it.text })
        assertTrue(Snippets.offer("x ;", all).isEmpty())
    }

    @Test fun aShortcutAfterAMarkTypedAgainstAWordStillCounts() {
        assertEquals(4, Snippets.offer("hello;eta", all).single().replace)
    }

    @Test fun aPlainShortcutOnlyAsItsOwnWholeWord() {
        assertEquals("Be right back", Snippets.offer("ok brb", all).single().text)
        assertTrue(Snippets.offer("ok br", all).isEmpty())
        assertTrue(Snippets.offer("ok xbrb", all).isEmpty())
        assertTrue(Snippets.offer("ok brb ", all).isEmpty())
    }

    @Test fun theyAreKeptAndReadBack() {
        assertEquals(all, Snippets.read(Snippets.write(all)))
        assertEquals(Snippets.starter, Snippets.read(null))
        assertTrue(Snippets.read("").isEmpty())
    }

    @Test fun pickingOneSelectsItsBlank() {
        val field = Field("ok ;eta")
        val engine = KeyboardEngine(field, ly.anoma.hex.typing.TypingPreferences(Prefs.context()), { ly.anoma.hex.speech.Spoken.English },
            { error("no typing model needed") }, clock = { 0L })
        engine.startInput(android.view.inputmethod.EditorInfo().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT; initialSelStart = 7; initialSelEnd = 7 })
        engine.act(Snippets.offer(field.text, all).single())
        assertEquals("ok There in 10 min", field.text)
    }
}
