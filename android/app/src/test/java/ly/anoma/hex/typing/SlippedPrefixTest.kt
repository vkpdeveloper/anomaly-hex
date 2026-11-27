package ly.anoma.hex.typing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The strip while a word is being typed: a slip early in the word still offers the word it starts, and a
// word not yet finished is never offered as two; space completes a word cut short.
class SlippedPrefixTest {
    private val grid = Glides.grid()

    /** The word after [letters] tapped at their key centres, after [before]. */
    private fun typed(letters: String, before: String = ""): TypingWord {
        val word = TypingWord(SpatialDecoder(model), true)
        word.reset(before)
        for (c in letters) grid.centres.getValue(c).let { word.letter(c.toString(), it.x, it.y, grid) }
        return word
    }
    private fun strip(letters: String, before: String = "") = typed(letters, before).strip()
    private fun texts(s: List<Suggestion>) = s.filter { it.kind != Suggestion.Kind.Typed }.map { it.text.lowercase() }

    // A neighbouring key ("somr"), an extra letter ("somrt") or a missing one ("somth", "tns").
    @Test fun aSlipEarlyInTheWordStillOffersTheWord() {
        for ((typed, word) in listOf("somr" to "something", "somrt" to "something", "somth" to "something", "tns" to "tension")) {
            val s = strip(typed)
            assertTrue("$typed: $s", word in texts(s))
        }
    }

    @Test fun lettersTypedRightKeepTheirOwnCompletions() {
        for (typed in listOf("some", "thin", "beca", "wor", "peop")) {
            val words = strip(typed).filter { it.kind == Suggestion.Kind.Word }.map { it.text.lowercase() }
            assertTrue("$typed: $words", words.isNotEmpty() && words.all { it.startsWith(typed) })
        }
    }

    @Test fun aWordStillBeingTypedIsNotSplit() {
        for (typed in listOf("somet", "someth", "somethi", "understa", "superhe", "beca")) {
            val s = strip(typed, "any ")
            assertTrue("$typed: $s", texts(s).none { ' ' in it })
        }
    }

    @Test fun finishedWordsRunTogetherStillSplit() {
        for ((typed, words) in listOf("ofthe" to "of the", "tothe" to "to the", "inthe" to "in the", "iam" to "i am", "alot" to "a lot")) {
            val fix = strip(typed).firstOrNull { it.kind == Suggestion.Kind.Fix }
            assertEquals("$typed: ${strip(typed)}", words, fix?.text?.lowercase())
        }
    }

    // The word the letters start, far likelier than the fix, goes in ("peop" became "prop"), slip and all when common;
    // not when another word they start is about as likely ("wor": "work" or "world"), nor past a close fix ("bene").
    @Test fun spaceCompletesAWordCutShort() {
        for ((letters, word) in listOf("peop" to "people", "becau" to "because", "climbi" to "climbing", "tomor" to "tomorrow",
                "somth" to "something")) {
            val w = typed(letters, "I said ")
            assertEquals(letters, word, w.replacement())
            assertEquals(letters, word, w.strip().single { it.kind == Suggestion.Kind.Fix }.text)
        }
        for ((letters, fix) in listOf("wor" to "work", "bene" to "been", "teh" to "the")) assertEquals(letters, fix, typed(letters, "I said ").replacement())
    }

    // Nor does space put in a fix that a word the letters start beats, when the fix drops the last letter ("aac" is
    // on its way to "aacha", not "aa") or the words it starts are each far likelier ("inte"); the strip offers both.
    // Three letters cut short of a rare word don't complete through a slip ("aac" isn't "academic"), and a
    // three-letter slip still goes in ("det" -> "set").
    @Test fun aWordTheLettersStartHoldsBackAWorseFix() {
        val hinglish = TypingModel.file(File("src/main/assets/typing/hien.hextm"), "hien")
        fun typedIn(m: TypingModel, letters: String) = TypingWord(SpatialDecoder(m), true).apply {
            reset("I said ")
            for (c in letters) grid.centres.getValue(c).let { letter(c.toString(), it.x, it.y, grid) }
        }
        val aac = typedIn(hinglish, "aac")
        assertEquals(null, aac.replacement())
        assertTrue(aac.strip().toString(), "aacha" in texts(aac.strip()))
        for (m in listOf(model, hinglish)) {
            val inte = typedIn(m, "inte")
            assertEquals(m.language, null, inte.replacement())
            assertTrue(inte.strip().toString(), "international" in texts(inte.strip()))
        }
        assertTrue(typedIn(model, "aac").replacement() != "academic")
        assertEquals("set", typedIn(model, "det").replacement())
    }

    @Test fun commonestTakesTheCommonestLongEnoughWordsOfARange() {
        val range = model.range("some")
        val lo = (range ushr 32).toInt(); val hi = range.toInt()
        val all = (lo until hi).filter { model.length(it) > 5 }.sortedBy { model.frequencyCost(it) }
        val three = model.commonest(lo, hi, 5, 3) { false }.map { model.text(it) }
        assertEquals(all.take(3).map { model.text(it) }, three)
        val blocked = model.commonest(lo, hi, 5, 3) { model.text(it) == three[0] }.map { model.text(it) }
        assertEquals(all.drop(1).take(3).map { model.text(it) }, blocked)
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}
