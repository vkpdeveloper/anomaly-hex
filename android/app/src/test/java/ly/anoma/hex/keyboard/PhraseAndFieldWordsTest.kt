package ly.anoma.hex.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.Glides
import ly.anoma.hex.typing.LearnedPhrases
import ly.anoma.hex.typing.LearnedWords
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phrase chips and the words already in the field, through the engine. */
class PhraseAndFieldWordsTest {
    @Test fun aPhraseYouKeepWritingBecomesOneChip() {
        val k = Keyboard()
        repeat(5) { k.chars("please let me know. ") }
        k.chars("please ")
        val chip = k.strip.single { it.kind == Suggestion.Kind.Phrase }
        assertEquals("let me know", chip.text)
        assertEquals(chip, k.strip.first())
    }

    @Test fun aPhraseSeenOnceOrTwiceIsNotOffered() {
        val k = Keyboard()
        repeat(2) { k.chars("please let me know. ") }
        k.chars("please ")
        assertTrue(k.strip.none { it.kind == Suggestion.Kind.Phrase })
    }

    @Test fun tappingTheChipTypesThePhraseAndTeachesEachWord() {
        val k = Keyboard()
        repeat(5) { k.chars("please let me know. ") }
        k.chars("please ")
        k.engine.pickSuggestion("let me know")
        assertTrue(k.field.text.endsWith(". please let me know"))
        assertTrue(k.learned.next("let", "me").contains("know"))
    }

    @Test fun backspaceAfterTheChipDeletesItsLastLetter() {
        val k = Keyboard()
        repeat(5) { k.chars("please let me know. ") }
        k.chars("please ")
        val before = k.field.text
        k.engine.pickSuggestion("let me know")
        k.engine.delete()
        assertEquals(before + "let me kno", k.field.text)
    }

    @Test fun aForgottenWordTakesItsPhrasesWithIt() {
        val k = Keyboard()
        repeat(5) { k.chars("please let me know. ") }
        k.phrases.forgetWord("me")
        k.chars("please ")
        assertTrue(k.strip.none { it.kind == Suggestion.Kind.Phrase })
    }

    @Test fun passwordAndIncognitoFieldsLearnNoPhrases() {
        for (k in listOf(Keyboard(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
            Keyboard(options = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))) {
            repeat(5) { k.chars("please let me know. ") }
            assertEquals("", k.phrases.write())
        }
    }

    @Test fun phrasesSurviveSavingAndLoading() {
        val k = Keyboard()
        repeat(5) { k.chars("please let me know. ") }
        val again = LearnedPhrases().also { it.read(k.phrases.write()) }
        assertEquals(listOf("let", "me", "know"), again.offer("please") { true })
    }

    @Test fun aWordAlreadyInTheFieldCompletesFromTwoLetters() {
        val k = Keyboard(text = "Meeting with Bartholomew tomorrow. ")
        k.tap("ba")
        assertTrue(k.strip.toString(), k.strip.any { it.text.equals("Bartholomew", true) })
    }

    @Test fun incognitoFieldsStillUseTheirOwnWordsButPasswordFieldsNeverDo() {
        val incognito = Keyboard(options = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, text = "Meeting with Bartholomew tomorrow. ")
        incognito.tap("ba")
        assertTrue(incognito.strip.toString(), incognito.strip.any { it.text.equals("Bartholomew", true) })
        val password = Keyboard(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, text = "Bartholomew ")
        password.tap("ba")
        assertTrue(password.strip.none { it.text.equals("Bartholomew", true) })
    }

    @Test fun wordsTypedInTheFieldCountToo() {
        val k = Keyboard()
        k.chars("Talk to Bartholomew. ")
        k.tap("ba")
        assertTrue(k.strip.toString(), k.strip.any { it.text.equals("Bartholomew", true) })
    }

    @Test fun aFreshFieldStartsWithoutTheLastOnesWords() {
        val k = Keyboard(text = "Meeting with Bartholomew tomorrow. ")
        k.field.text = ""; k.field.setSelection(0, 0)
        k.engine.startInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; initialSelStart = 0; initialSelEnd = 0 })
        k.tap("ba")
        assertTrue(k.strip.none { it.text.equals("Bartholomew", true) })
    }

    @Test fun theDecoderPrefersAWordFromTheFieldOverAnEqualOne() {
        val words = ly.anoma.hex.typing.FieldWords().also { it.seed("the Bartholomew") }
        assertNotNull(words.completion("Bar"))
        assertTrue(words.bonus("bartholomew") > 0.0)
        assertEquals(0.0, words.bonus("table"), 0.0)
        words.clear()
        assertNull(words.completion("Bar"))
        assertFalse(words.bonus("bartholomew") > 0.0)
    }

    private inner class Keyboard(type: Int = InputType.TYPE_CLASS_TEXT, options: Int = 0, text: String = "") {
        val field = Field(text)
        val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = false }
        var strip = emptyList<Suggestion>()
        val learned = LearnedWords()
        val phrases = LearnedPhrases()
        val engine = KeyboardEngine(field, prefs, { Spoken.English }, { SpatialDecoder(model) }, learned = learned, phrases = phrases, clock = { 0L })

        init {
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) {}
                override fun suggestions(slots: List<Suggestion>) { strip = slots }
                override fun undo(offer: UndoOffer?) {}
            }
            engine.startInput(EditorInfo().apply { inputType = type; imeOptions = options; initialSelStart = text.length; initialSelEnd = text.length })
            engine.opened()
            engine.configureTyping()
        }

        fun tap(letters: String) {
            for (c in letters) grid.centres.getValue(c).let { engine.letter(c.toString(), it.x, it.y, grid) }
        }
        fun chars(text: String) { for (c in text) if (c == ' ') engine.space() else engine.type(c.toString()) }
    }

    private val grid = Glides.grid()

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}
