package ly.anoma.hex.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.LearnedWords
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Learning from the words fixed in a dictation: what counts as a fix, and where it is followed. */
class DictationWatchTest {
    @Test fun aFixedWordInTheDictationIsACorrection() {
        val fix = Corrections.of("Note: hi rowan ak how are you", "hi rowan ak how are you", "Note: hi Raunak how are you")!!
        assertEquals("Note: hi rowan ak how are you", fix.baseline)
        assertEquals("hi rowan ak how are you", fix.inserted)
        assertEquals("Note: hi Raunak how are you", fix.edited)
        assertEquals(listOf("Raunak" to false), fix.words)
    }

    @Test fun anUntouchedFieldOrAnEditOutsideTheDictationTeachesNothing() {
        assertNull(Corrections.of("send it now", "send it now", "send it now"))
        assertNull(Corrections.of("Note: send it now", "send it now", "Notes: send it now"))
        assertNull(Corrections.of("send it now. Later", "send it now.", "send it now. Later on"))
    }

    @Test fun anEditRunningPastTheDictationGoesToTheComputerButTeachesThePhoneNothing() {
        val fix = Corrections.of("hi rowan. Bye", "hi rowan.", "hi Raunak! Hello")!!
        assertEquals(emptyList<Pair<String, Boolean>>(), fix.words)
    }

    @Test fun aLongFieldIsCutToTheTextAroundTheFix() {
        val filler = "word ".repeat(2_000)
        val baseline = filler + "tell rowan hello " + filler
        val edited = baseline.replace("rowan", "Raunak")
        val fix = Corrections.of(baseline, "tell rowan hello", edited)!!
        assertTrue(fix.baseline.length < 1_000)
        assertTrue(fix.edited.length < 1_000)
        assertTrue(fix.baseline.contains("tell rowan hello") && fix.edited.contains("tell Raunak hello"))
        assertNull(Corrections.of(filler + "x " + filler, filler, filler.replace("word", "w0rd") + "x " + filler))
    }

    @Test fun aCapitalAtASentenceStartIsNotAWordToLearn() {
        val fix = Corrections.of("okay then", "okay then", "Okay then")!!
        assertEquals(listOf("Okay" to true), fix.words)
        val learned = LearnedWords()
        Corrections.learn(learned, fix.words)
        assertNull(learned.spelling("okay")?.takeIf { it == "Okay" })
    }

    @Test fun theWatchEndsWithTheLastReadingOrNothingAtAll() {
        var now = 0L
        val watch = DictationWatch { now }
        watch.begin("see you at six", "Hello. see you at six")
        watch.sample("Hello. see you at 6")
        now += DictationWatch.WATCH_MS
        assertTrue(watch.expired())
        assertEquals("Hello. see you at 6", watch.finish()!!.edited)
        assertNull(watch.finish())
        watch.begin("not in there", "something else")
        assertTrue(!watch.active)
    }

    @Test fun termsFromTheComputerBecomeWords() {
        assertEquals(listOf("Raunak" to false, "Chowdhuri" to false), Corrections.termWords(listOf("Raunak Chowdhuri", "Raunak")))
    }

    @Test fun theQueueKeepsTheNewestFew() {
        val queue = PendingCorrections(max = 2)
        val fixes = (1..3).map { DictationFix("b$it", "i$it", "e$it", emptyList()) }
        fixes.forEach(queue::add)
        assertEquals(2, queue.size)
        assertEquals(fixes[1], queue.first())
        queue.remove(fixes[1])
        assertEquals(fixes[2], queue.first())
        queue.clear()
        assertNull(queue.first())
    }

    @Test fun waitingCorrectionsSurviveARestart() {
        val queue = PendingCorrections()
        val fixes = listOf(DictationFix("Note:\thi rowan\n", "hi rowan", "Note:\thi Raunak\n", emptyList()),
            DictationFix("ok ✓ café", "ok", "okay ✓ café", emptyList()))
        fixes.forEach(queue::add)
        val again = PendingCorrections().apply { read(queue.write() + "not\ta line\n") }
        assertEquals(2, again.size)
        assertEquals(fixes[0], again.first())
        again.remove(fixes[0])
        assertEquals(fixes[1], again.first())
        assertEquals("", PendingCorrections().write())
    }

    @Test fun aFixedDictationIsLearnedHereAndSent() {
        val k = Keyboard()
        k.engine.insertDictation("Tell rowan hello.", "en")
        k.field.text = "Tell Raunak hello."
        k.engine.sampleWatch()
        k.engine.finishWatch()
        val fix = k.sent.single()
        assertEquals("Tell rowan hello.", fix.inserted)
        assertEquals("Tell Raunak hello.", fix.edited)
        assertEquals("Raunak", k.learned.spelling("raunak"))
        assertTrue("Raunak" in k.learned.words())
    }

    @Test fun anUneditedDictationSendsNothing() {
        val k = Keyboard()
        k.engine.insertDictation("Tell rowan hello.", "en")
        k.engine.finishWatch(read = true)
        assertTrue(k.sent.isEmpty())
    }

    @Test fun aNewDictationEndsTheWatchAndSendsWhatWasFixed() {
        val k = Keyboard()
        k.engine.insertDictation("Tell rowan hello.", "en")
        k.field.text = "Tell Raunak hello."
        k.engine.clearForDictation()
        assertNotNull(k.sent.singleOrNull())
    }

    @Test fun incognitoPasswordAndOptedOutFieldsLearnAndSendNothing() {
        val incognito = Keyboard(options = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        val password = Keyboard(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val off = Keyboard(on = false)
        for (k in listOf(incognito, password, off)) {
            k.engine.insertDictation("Tell rowan hello.", "en")
            k.field.text = "Tell Raunak hello."
            k.engine.sampleWatch()
            k.engine.finishWatch(read = true)
            assertTrue(k.sent.isEmpty())
            assertNull(k.learned.spelling("raunak"))
        }
    }

    @Test fun turningItOffMidwayStopsTheWatch() {
        val k = Keyboard()
        k.engine.insertDictation("Tell rowan hello.", "en")
        k.prefs.learnFromCorrections = false
        k.field.text = "Tell Raunak hello."
        k.engine.finishWatch(read = true)
        assertTrue(k.sent.isEmpty())
        assertNull(k.learned.spelling("raunak"))
    }

    private class Keyboard(type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                           options: Int = 0, on: Boolean = true) {
        val field = Field("")
        val sent = mutableListOf<DictationFix>()
        val learned = LearnedWords()
        val prefs = TypingPreferences(Prefs.context()).also { it.learnFromCorrections = on }
        val engine = KeyboardEngine(field, prefs, { Spoken.English }, { SpatialDecoder(model) },
            learned = learned, corrected = { sent += it }, clock = { 0L })

        init {
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) {}
                override fun suggestions(slots: List<Suggestion>) {}
                override fun undo(offer: UndoOffer?) {}
            }
            engine.startInput(EditorInfo().apply { inputType = type; imeOptions = options; initialSelStart = 0; initialSelEnd = 0 })
            engine.opened()
        }
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}
