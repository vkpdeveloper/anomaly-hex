package ly.anoma.hex.typing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The spelling you write of a word written several ways, learned from what you type (`Spellings`).
class SpellingPreferenceTest {
    private val hinglish = TypingModel.file(File("src/main/assets/typing/hien.hextm"), "hien")
    private val english = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    private val grid = Glides.grid()

    private fun yours(vararg words: Pair<String, Int>) = LearnedWords().apply { for ((w, n) in words) repeat(n) { learn(w, null, false) } }
    private fun writing(model: TypingModel, learned: LearnedWords, withEnglish: Boolean = true) =
        TypingWord(SpatialDecoder(model).also { it.learned = learned; if (withEnglish) it.english = english }, true)
    private fun TypingWord.typed(text: String, aimed: String = text) = apply {
        for ((i, c) in text.withIndex()) grid.centres.getValue(aimed[i]).let { letter(c.toString(), it.x, it.y, grid) }
    }
    private fun TypingWord.words() = strip().map { it.text }

    @Test fun theStripOffersYourSpellingAndASlipGoesToIt() {
        val learned = yours("nhi" to 5)
        val nahi = writing(hinglish, learned).typed("nahi")
        assertTrue(nahi.words().toString(), "nhi" in nahi.words())
        // Another spelling is a word: offered, never put in by itself.
        assertNull(nahi.replacement())
        assertEquals("nhi", writing(hinglish, learned).typed("nahu", "nahi").replacement())
        assertNull(writing(hinglish, learned).typed("nhi").replacement())
    }

    // Until English's model has loaded, Hindi spellings stay apart: "sheet" must never be "shit".
    @Test fun withoutEnglishHindiSpellingsStayApart() {
        val nahi = writing(hinglish, yours("nhi" to 5), withEnglish = false).typed("nahi")
        assertFalse(nahi.words().toString(), "nhi" in nahi.words())
    }

    @Test fun aGlideGivesYourSpellingOnce() {
        val glided = writing(hinglish, yours("achha" to 5)).glide(Glides.path("acha", grid, smooth = .5 * grid.pitchX), grid, "")
        assertEquals(glided.toString(), "achha", glided.first())
        assertEquals(1, glided.count { it == "achha" })
        assertFalse(glided.toString(), "acha" in glided || "accha" in glided)
    }

    @Test fun britishOrAmericanAsYouWrite() {
        val color = writing(english, yours("colour" to 4)).typed("color")
        assertTrue(color.words().toString(), "colour" in color.words())
        // A completion keeps its own spelling while yours doesn't complete what was typed.
        val learned = yours("nhi" to 5)
        val nah = writing(hinglish, learned).typed("nah")
        assertFalse(nah.words().toString(), "nhi" in nah.words())
    }
}
