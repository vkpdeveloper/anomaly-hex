package ly.anoma.hex.typing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Fixes space must not make: your own words' capitals over a real word, a stretched word, and Hinglish
// words read as English slips or split apart.
class AutocorrectRestraintTest {
    private val hinglish = TypingModel.file(File("src/main/assets/typing/hien.hextm"), "hien")
    private val english = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    private val grid = Glides.grid()

    private fun learned(vararg words: String) = LearnedWords().apply { for (w in words) repeat(LearnedWords.WORD_USES) { learn(w, null, false) } }
    private fun writing(model: TypingModel, learned: LearnedWords = LearnedWords(), dictionary: List<String> = emptyList()) =
        TypingWord(SpatialDecoder(model).also {
            it.learned = learned
            if (model !== english) it.english = english
            it.personal = PersonalWords(model, dictionary + learned.yours(model), emptyMap())
        }, true)
    // Each letter of [text] touched at the centre of [aimed]'s key in its place.
    private fun TypingWord.typed(text: String, aimed: String) = apply {
        for ((i, c) in text.withIndex()) grid.centres.getValue(aimed[i]).let { letter(c.toString(), it.x, it.y, grid) }
    }
    private fun fix(model: TypingModel, text: String, learned: LearnedWords = LearnedWords(), dictionary: List<String> = emptyList(),
                    aimed: String = text.lowercase()) =
        writing(model, learned, dictionary).typed(text, aimed).replacement()

    // A real word saved in capitals ("COMPANY") or learned with one ("Opencode") keeps the case you typed.
    @Test fun yourCapitalsDoNotOverrideTheCaseYouTyped() {
        assertNull(fix(english, "company", dictionary = listOf("COMPANY")))
        assertNull(fix(english, "Happy", dictionary = listOf("HAPPY")))
        assertNull(fix(english, "opencode", learned("Opencode")))
        // Capitals inside a word are its spelling, not emphasis.
        assertEquals("GitHub", fix(english, "github", learned("GitHub")))
    }

    // A slip you happened to keep twice still goes to the common word it is one key off.
    @Test fun aLearnedSlipOfACommonWordIsStillFixed() {
        assertEquals("like", fix(english, "likr", learned("likr")))
        assertEquals("with", fix(english, "wiht", learned("wiht")))
        // A word of your own that isn't one key off a common one stays.
        assertNull(fix(english, "zorbly", learned("zorbly")))
    }

    @Test fun aStretchedWordStands() {
        for (word in listOf("thankss", "yayyy", "whyy", "cooll")) assertNull(word, fix(english, word))
    }

    @Test fun hinglishWordsAreNotReadAsEnglishSlipsOrSplit() {
        assertNotEquals("ko se", fix(hinglish, "konse"))
        assertNotEquals("jury", fix(hinglish, "kuru"))
        assertNotEquals("jyada", fix(hinglish, "jaysa"))
        // Touched nearer the keys of the fix.
        assertNotEquals("Gupta", fix(hinglish, "typa", dictionary = listOf("Gupta"), aimed = "gupa"))
        assertNotEquals("Aacha", fix(hinglish, "Aadha", aimed = "aacha"))
    }
}
