package ly.anoma.hex.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.ContactNames
import ly.anoma.hex.typing.Forgotten
import ly.anoma.hex.typing.Glides
import ly.anoma.hex.typing.LearnedWords
import ly.anoma.hex.typing.PersonalWords
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What you keep, forget and put back is yours: the strip's long-press forget, a word kept as typed,
 * an autocorrection undone, and your contacts' names. */
class PersonalWordsLearningTest {
    private val grid = Glides.grid()

    @Test fun aForgottenModelWordLeavesTheStrip() {
        val k = Keyboard()
        k.tap("hell")
        assertTrue(k.strip.toString(), k.strip.any { it.text == "hello" })
        k.engine.forget("hello")
        k.tap("o")
        assertTrue(k.strip.toString(), k.strip.none { it.text.equals("hello", true) && it.kind != Suggestion.Kind.Typed })
        assertTrue("hello" in k.forgotten)
    }

    @Test fun aForgottenLearnedWordIsDroppedAndNotLearnedBackFromASlip() {
        val k = Keyboard()
        k.chars("zorbex zorbex ")
        assertEquals(listOf("zorbex"), k.learned.words())
        k.engine.forget("zorbex")
        assertTrue(k.learned.words().isEmpty())
        assertTrue(k.learned.next("zorbex").isEmpty())
        k.chars("zorbex ")
        assertTrue(k.learned.words().isEmpty())
        assertTrue("zorbex" in k.forgotten)
    }

    @Test fun forgettingWorksWhereLearningDoesNot() {
        val k = Keyboard(options = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        k.engine.forget("hello")
        assertTrue("hello" in k.forgotten)
    }

    @Test fun anUndoneAutocorrectionIsLearnedAndNotMadeAgain() {
        val k = Keyboard()
        k.tap("teh"); k.engine.space()
        assertEquals("the ", k.field.text)
        k.engine.delete()
        assertEquals("teh", k.field.text)
        assertEquals(listOf("teh"), k.learned.words())
        k.engine.space()
        assertEquals("teh ", k.field.text)
        k.tap("teh"); k.engine.space()
        assertEquals("teh teh ", k.field.text)
    }

    @Test fun anUndoneAutocorrectionTakesItsCountsBack() {
        val k = Keyboard()
        k.tap("you"); k.engine.space(); k.tap("zorbly"); k.engine.space()
        val words = k.learned.write(); val touches = k.engine.touch.write()
        k.tap("wprld"); k.engine.space()
        assertEquals("you zorbly world ", k.field.text)
        k.engine.delete()
        assertEquals("you zorbly wprld", k.field.text)
        assertTrue(k.learned.write(), k.learned.write().lines().none { "world" in it.split('\t') })
        assertTrue(k.learned.cost("world", null).isInfinite())
        assertEquals(listOf("wprld"), k.learned.words())
        // What came before is as it was, the taps on "wprld" aside: the keys learned nothing from "world".
        assertEquals(words.lines().filter { it.isNotEmpty() }.toSet(), k.learned.write().lines().filter { it.isNotEmpty() && "wprld" !in it.split('\t') }.toSet())
        assertEquals(stats(touches), stats(k.engine.touch.write()).filterKeys { it in stats(touches) })
    }
    private fun stats(text: String) = text.lines().drop(1).filter { it.isNotEmpty() }.associate { line ->
        line.split('\t').let { f -> f[0] to f.drop(1).map { "%.6f".format(it.toDouble()) } }
    }

    @Test fun aWordKeptAsTypedIsYoursAtOnce() {
        val k = Keyboard()
        k.tap("zorbex")
        assertTrue(k.strip.toString(), k.strip.any { it.kind == Suggestion.Kind.Typed && it.text == "zorbex" })
        k.engine.pickSuggestion("zorbex")
        assertEquals(listOf("zorbex"), k.learned.words())
    }

    @Test fun aWordPickedOverTheAutocorrectionIsLearned() {
        val k = Keyboard()
        k.tap("teh")
        k.engine.pickSuggestion("them")
        assertEquals(listOf("them"), k.learned.words())
    }

    @Test fun nothingIsLearnedOrCountedWhereLearningIsOff() {
        for (k in listOf(Keyboard(options = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING),
            Keyboard(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))) {
            k.tap("zorbex"); k.engine.pickSuggestion("zorbex")
            assertTrue(k.learned.words().isEmpty())
            k.engine.delete(); k.engine.delete(); k.engine.delete(); k.engine.delete(); k.engine.delete(); k.engine.delete(); k.engine.delete()
            k.tap("teh"); k.engine.space(); k.engine.delete()
            assertTrue(k.learned.words().isEmpty())
            assertFalse(k.prefs.blocked("en", "teh", "the"))
            assertTrue(k.learned.write().isEmpty())
        }
    }

    @Test fun contactNamesBecomeWordsOnce() {
        val words = ContactNames.words(
            sequenceOf("Priya Nair", "Anne-Marie Ó Briain", "J", null, "priya nair"),
            sequenceOf("Priya", "Nair", null))
        assertEquals(listOf("Priya", "Nair", "Anne", "Marie", "Briain"), words)
    }

    @Test fun aNameInYourContactsIsCompletedWithItsCapital() {
        val k = Keyboard(contacts = listOf("Zorbexa"))
        k.tap("zorbe")
        assertTrue(k.strip.toString(), k.strip.any { it.text == "Zorbexa" })
        k.tap("xa"); k.engine.space()
        assertEquals("Zorbexa ", k.field.text)
    }

    @Test fun aForgottenContactNameIsHidden() {
        val k = Keyboard(contacts = listOf("Zorbexa"))
        k.engine.forget("Zorbexa")
        k.tap("zorbe")
        assertTrue(k.strip.toString(), k.strip.none { it.text == "Zorbexa" })
    }

    @Test fun forgottenWordsAreSavedAndCleared() {
        val saved = Forgotten().apply { add("Hello"); add("zorbex") }
        val back = Forgotten().apply { read(saved.write()) }
        assertEquals(listOf("hello", "zorbex"), back.all())
        assertTrue("HELLO" in back)
        back.clear()
        assertTrue("hello" !in back)
    }

    private inner class Keyboard(type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                                 options: Int = 0, contacts: List<String> = emptyList()) {
        val field = Field("")
        val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = false }
        var strip = emptyList<Suggestion>()
        val learned = LearnedWords()
        val forgotten = Forgotten()
        val engine = KeyboardEngine(field, prefs, { Spoken.English }, { SpatialDecoder(model) },
            readPersonal = { m, mine -> PersonalWords(m, mine + contacts, emptyMap(), { it in forgotten }) },
            learned = learned, forgotten = forgotten, clock = { 0L })

        init {
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) {}
                override fun suggestions(slots: List<Suggestion>) { strip = slots }
                override fun undo(offer: UndoOffer?) {}
            }
            engine.startInput(EditorInfo().apply { inputType = type; imeOptions = options; initialSelStart = 0; initialSelEnd = 0 })
            engine.opened()
            engine.configureTyping()
        }

        // Taps on the letter keys, as the decoder reads them.
        fun tap(letters: String) {
            for (c in letters) grid.centres.getValue(c).let { engine.letter(c.toString(), it.x, it.y, grid) }
        }
        fun chars(text: String) { for (c in text) if (c == ' ') engine.space() else engine.type(c.toString()) }
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}
