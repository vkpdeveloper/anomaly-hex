package ly.anoma.hex.fix

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import ly.anoma.hex.typing.TypingModel

class TinyfixTest {
    @Test fun tokenizesAsTheJavaScriptRegexDoes() {
        assertEquals(listOf("we", "met", "<name>", "in", "<name>", ".", "it's", "i", "said"),
            tokenize("We met Anna in Paris. It’s I said", true))
        // A line break ends a sentence, so the next capital isn't a name.
        assertEquals(listOf("notes", ".", "their", "is"), tokenize("Notes\nTheir is", true))
        // An emoji outside the basic plane is two code units, two tokens, as JavaScript sees it.
        assertEquals(listOf("a", "\uD83C", "\uDF55", "12.5", "%", "b"), tokenize("a 🍕 12.5% b_", true))
    }

    @Test fun typoCostsMatchTheJavaScript() {
        for ((typed, intended, cost) in listOf(Triple("recieve", "receive", 1.2), Triple("teh", "the", 1.2),
            Triple("sporrt", "sport", 1.2), Triple("xat", "cat", 3.1), Triple("helo", "hello", 1.2)))
            assertEquals("$typed → $intended", cost, TypoCost.of(typed, intended), 1e-9)
    }

    @Test fun trieFindsWordsOneEditAwayInItsWalkOrder() {
        assertEquals(listOf(4, 3, 0), WordTrie(listOf("the", "then", "them", "tea", "ten", "than")).search("teh", 1))
    }

    @Test fun sentenceStarts() {
        for (text in listOf("", "Done. ", "Why?", "Notes\n  ", "  - ", "•\t")) assertEquals(text, true, Rules.isSentenceStart(text))
        for (text in listOf("the ", "  ", "-", "a - ")) assertEquals(text, false, Rules.isSentenceStart(text))
    }

    @Test fun appliesFixesThatChangeTheLength() {
        val text = "I am to tired and your right."
        assertEquals("I am too tired and you're right.",
            Tinyfix.apply(text, listOf(Fix(5, 7, "to", "too"), Fix(18, 22, "your", "you're"))))
    }

    /** The package's own outputs (tinyfix at model revision 8efdf5cd) for the same sentences. Needs the
     * four model files in HEX_TINYFIX_DIR, so it is skipped where they aren't downloaded. */
    @Test fun fixesAsThePackageDoes() {
        val dir = System.getenv("HEX_TINYFIX_DIR")?.let(::File)
        assumeTrue(dir != null && File(dir, "wrong-word.onnx").exists())
        val fixer = TinyfixFiles.load(dir!!)
        val unescape = Regex("\\\\(.)")
        fun read(s: String) = unescape.replace(s) { when (val c = it.groupValues[1]) { "n" -> "\n"; "t" -> "\t"; else -> c } }
        val cases = javaClass.getResource("/tinyfix-parity.tsv")!!.readText().lines().filter { it.isNotEmpty() }
        val wrong = cases.mapNotNull { line ->
            val (typed, expected) = line.split('\t').map(::read)
            val got = Tinyfix.apply(typed, fixer.check(typed))
            if (got == expected) null else "$typed\n  expected $expected\n  got      $got"
        }
        assertEquals(wrong.joinToString("\n"), 0, wrong.size)
    }

    // On Hinglish the button keeps Hindi words, which tinyfix would read as English misspellings.
    @Test fun leavesHinglishHindiWordsAlone() {
        val dir = System.getenv("HEX_TINYFIX_DIR")?.let(::File)
        assumeTrue(dir != null && File(dir, "wrong-word.onnx").exists())
        val hinglish = TypingModel.file(File("src/main/assets/typing/hien.hextm"), "hien")
        val english = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
        val hindi = { word: String -> word.lowercase().let { hinglish.index(it) >= 0 && english.index(it) < 0 } }
        val fixer = TinyfixFiles.load(dir!!)
        val text = "kya hai yaar main khush nahi hoon, theek hai. how to go form here"
        assertEquals("kya hai yaar main khush nahi hoon, theek hai. how to go from here",
            Tinyfix.apply(text, fixer.check(text, hindi)))
    }
}
