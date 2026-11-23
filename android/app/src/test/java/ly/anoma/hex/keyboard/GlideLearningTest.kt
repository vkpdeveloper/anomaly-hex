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

/** Glides spell the words you have, wherever they came from, and teach as tapping does: what a glide
 * puts in, what you pick over it, and what you type after deleting it. */
class GlideLearningTest {
    private val grid = Glides.grid()

    @Test fun aWordOnlyTheSpellingListsKnowGlidesOnceYouWriteIt() {
        val k = Keyboard()
        k.prefs.autocorrect = false
        assertTrue(model.index("kotlin") < 0 && model.known("kotlin"))
        assertTrue(model.index("ravi") < 0 && model.known("ravi"))
        repeat(2) { k.type("in kotlin with Ravi ") }
        k.glide("thanks"); k.glide("kotlin"); k.glide("ravi")
        assertTrue(k.field.text, k.field.text.endsWith(" thanks kotlin Ravi"))
    }

    @Test fun aWordLearnedSecondsAgoGlidesRightAfterACommonWord() {
        val k = Keyboard()
        k.prefs.autocorrect = false
        k.type("thanks Quorvane "); k.type("thanks Quorvane ")
        k.glide("thanks"); k.glide("quorvane")
        assertTrue(k.field.text, k.field.text.endsWith(" thanks Quorvane"))
    }

    @Test fun wordsFromTheComputerGlide() {
        val k = Keyboard()
        k.learned.seed(listOf("Zarvik" to 3, "github" to 5), emptyList())
        k.engine.learnedWordsChanged()
        k.glide("zarvik"); k.glide("github")
        assertEquals("Zarvik github", k.field.text)
    }

    @Test fun namesInYourContactsAndDictionaryGlide() {
        val k = Keyboard(contacts = listOf("Vaibhav", "Quorvane"))
        k.glide("vaibhav"); k.glide("with"); k.glide("quorvane")
        assertEquals("Vaibhav with Quorvane", k.field.text)
    }

    @Test fun aGlidedWordIsLearnedWhenTheNextWordStarts() {
        val k = Keyboard()
        k.glide("hello"); k.glide("world")
        assertTrue(k.learned.cost("hello", null).isFinite())
        assertTrue(k.learned.cost("world", null).isInfinite())
        k.engine.space()
        assertTrue(k.learned.cost("world", "hello").isFinite())
    }

    @Test fun aTappedWordAGlideFollowsAndTheWordBeforeReturnAreLearned() {
        val k = Keyboard()
        k.type("said"); k.glide("hello"); k.engine.enter()
        assertEquals("said hello\n", k.field.text)
        assertTrue(k.learned.cost("said", null).isFinite())
        assertTrue(k.learned.cost("hello", "said").isFinite())
    }

    @Test fun aWordTypedWhereAGlideWasDeletedIsYoursEvenWhenAGlideEndsIt() {
        val k = Keyboard()
        k.prefs.autocorrect = false
        k.glide("zarvik"); k.engine.delete()
        k.type("zarvik"); k.glide("with")
        assertTrue(k.learned.words().toString(), "zarvik" in k.learned.words())
    }

    @Test fun aReadingPickedOverTheGlideIsYoursAtOnce() {
        val k = Keyboard("I")
        k.glide("tip")
        val other = k.strip.drop(1).first { it.text == "tip" || it.text == "top" }.text
        k.engine.pickSuggestion(other)
        assertTrue(k.learned.words().toString(), other in k.learned.words())
    }

    @Test fun aPickAfterAGlideWithShiftTeachesTheWordNotTheCapital() {
        val k = Keyboard()
        k.engine.shiftDown(); k.engine.shiftUp()
        k.glide("tip")
        val other = k.strip.drop(1).first { it.text == "Tip" || it.text == "Top" }.text
        k.engine.pickSuggestion(other)
        assertEquals(other.lowercase(), k.learned.spelling(other))
    }

    @Test fun aWordTypedWhereAGlideWasDeletedIsYoursAndGlides() {
        val k = Keyboard()
        k.prefs.autocorrect = false
        k.glide("zarvik")
        val missed = k.field.text
        assertTrue(missed.isNotEmpty() && !missed.equals("zarvik", ignoreCase = true))
        k.engine.delete()
        assertEquals("", k.field.text)
        k.type("zarvik ")
        assertTrue(k.learned.words().toString(), "zarvik" in k.learned.words())
        k.glide("zarvik")
        assertEquals("zarvik zarvik", k.field.text)
    }

    @Test fun retypingTheSameGlidedWordTeachesNothingMore() {
        val k = Keyboard()
        k.glide("hello"); k.engine.delete()
        k.type("hello ")
        assertTrue(k.learned.words().isEmpty())
    }

    @Test fun nothingIsLearnedFromGlidesWhereLearningIsOff() {
        for (k in listOf(Keyboard(options = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING),
            Keyboard(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))) {
            assertTrue(k.engine.glides)
            k.glide("hello"); k.engine.space()
            k.glide("tip"); k.strip.drop(1).firstOrNull()?.let { k.engine.pickSuggestion(it.text) }
            k.glide("zarvik"); k.engine.delete(); k.type("zarvik "); k.type("zarvik ")
            k.glide("world"); k.engine.type(".")
            assertEquals("", k.learned.write())
            assertEquals("hex-touch 1\n", k.engine.touch.write())
        }
    }

    @Test fun manyLearnedWordsLeaveRoomForYourDictionary() {
        val learned = (0 until 6_000).map { "zq$it".map { c -> if (c.isDigit()) 'a' + (c - '0') else c }.joinToString("") }
        val personal = PersonalWords.of(model, listOf("Vaibhav" to "", "on my way" to "omw"), learned)
        assertTrue(personal.index("vaibhav") >= 0)
        assertEquals("On my way", personal.shortcut("Omw"))
        assertEquals("Vaibhav", personal.text(personal.index("vaibhav")))
    }

    private inner class Keyboard(text: String = "", type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                                 options: Int = 0, contacts: List<String> = emptyList()) {
        val field = Field(text)
        val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = false }
        var strip = emptyList<Suggestion>()
        val learned = LearnedWords()
        val engine = KeyboardEngine(field, prefs, { Spoken.English }, { SpatialDecoder(model) },
            readPersonal = { m, mine -> PersonalWords.of(m, emptyList(), mine, contactNames = contacts) }, learned = learned, clock = { 0L })

        init {
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) {}
                override fun suggestions(slots: List<Suggestion>) { strip = slots }
                override fun undo(offer: UndoOffer?) {}
            }
            engine.startInput(EditorInfo().apply {
                inputType = type; imeOptions = options; initialSelStart = text.length; initialSelEnd = text.length
            })
            engine.opened()
            engine.configureTyping()
        }

        fun glide(word: String) = engine.glide(Glides.path(word, grid, smooth = .5 * grid.pitchX), grid)
        /** Taps at each letter's key centre (a capital as the shifted key types it) and spaces. */
        fun type(text: String) {
            for (c in text) {
                if (c == ' ') { engine.space(); continue }
                val p = grid.centres.getValue(c.lowercaseChar())
                engine.letter(c.toString(), p.x, p.y, grid)
            }
        }
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}
