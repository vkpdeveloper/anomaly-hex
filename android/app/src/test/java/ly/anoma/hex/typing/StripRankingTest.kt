package ly.anoma.hex.typing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Which words the strip offers while a word is typed, and in what order.
class StripRankingTest {
    private val hinglish = TypingModel.file(File("src/main/assets/typing/hien.hextm"), "hien")
    private val english = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    private val grid = Glides.grid()

    private fun yours(vararg words: Pair<String, Int>) = LearnedWords().apply { for ((w, n) in words) repeat(n) { learn(w, null, false) } }
    private fun writing(learned: LearnedWords = LearnedWords(), dictionary: List<String> = emptyList()) =
        TypingWord(SpatialDecoder(hinglish).also {
            it.english = english; it.learned = learned
            it.personal = PersonalWords.of(hinglish, dictionary.map { w -> w to "" }, learned.yours(hinglish))
        }, true)
    private fun TypingWord.typed(text: String) = apply {
        for (c in text) grid.centres.getValue(c.lowercaseChar()).let { letter(c.toString(), it.x, it.y, grid) }
    }
    private fun TypingWord.words() = strip().filter { it.kind != Suggestion.Kind.Typed }.map { it.text }

    @Test fun anUnfinishedWordKeepsItsCompletionOnTheStrip() {
        for ((typed, word) in listOf("alw" to "always", "climbi" to "climbing", "sle" to "sleep", "produc" to "production")) {
            val w = writing().typed(typed)
            val strip = w.strip()
            val fix = strip.single { it.kind == Suggestion.Kind.Fix }
            // The fix is still what space puts in.
            assertEquals(typed, w.replacement(), fix.text)
            assertTrue("$typed: $strip", strip.any { it.kind != Suggestion.Kind.Typed && it.text == word })
        }
    }

    @Test fun aWordYouOnlyWroteWaitsForTheCommonOnes() {
        // Kept once against a fix: two uses at once.
        val w = writing(yours("Wiht" to 2)).typed("wi").words()
        assertEquals(w.toString(), listOf("with", "will"), w.take(2))
        // One slip from a word, it goes after that word until you write it far more.
        val often = writing(yours("Wiht" to 3, "with" to 1)).typed("wi").words()
        assertTrue(often.toString(), "with" in often && ("Wiht" !in often || often.indexOf("with") < often.indexOf("Wiht")))
    }

    @Test fun aWordYouAddedStillCompletesAtOnce() {
        // Beside what space puts in, before the dictionary's longer words.
        val w = writing(dictionary = listOf("Zorbex")).typed("zo").strip()
        assertEquals(w.toString(), "Zorbex", w.first { it.kind == Suggestion.Kind.Word }.text)
    }

    @Test fun aWordYouWriteOftenComesFromItsFirstLetters() {
        assertFalse(writing().typed("tum").words().contains("tumko"))
        val w = writing(yours("tumko" to 6)).typed("tum").words()
        assertEquals(w.toString(), "tumko", w.first())
    }

    @Test fun aWordOnceWrittenInCapitalsShowsAsTheDictionaryWritesIt() {
        for ((word, typed) in listOf("HAPPY" to "happ", "COMPANY" to "comp")) {
            val w = writing(yours(word to 4)).typed(typed).words()
            assertTrue(w.toString(), word.lowercase() in w)
            assertFalse(w.toString(), word in w)
        }
        // A name's or a brand's capitals stay.
        val brand = writing(yours("GitHub" to 4)).typed("git").words()
        assertTrue(brand.toString(), "GitHub" in brand)
    }
}
