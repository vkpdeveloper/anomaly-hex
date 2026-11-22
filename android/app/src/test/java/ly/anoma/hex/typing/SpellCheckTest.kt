package ly.anoma.hex.typing

import java.io.File
import ly.anoma.hex.fix.Fix
import ly.anoma.hex.fix.TinyfixFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Which words Hex's spell checker underlines, and what it offers for them. */
class SpellCheckTest {
    private fun checker(language: String) =
        SpellCheck(SpatialDecoder(TypingModel.file(File("src/main/assets/typing/$language.hextm"), language)), false, false)

    private fun misspelled(language: String, text: String) =
        checker(language).check(text).filter { it.suggestions != null }.associate { text.substring(it.from, it.to) to it.suggestions!! }

    @Test fun anUnknownWordIsUnderlinedWithItsFixFirst() {
        val found = misspelled("en", "I saw teh dog yesterday")
        assertEquals(setOf("teh"), found.keys)
        assertEquals("the", found.getValue("teh").first())
    }

    @Test fun aSlipOrSwappedLettersFindTheWord() {
        val found = misspelled("en", "these wrods are a wprd apart")
        assertEquals("words", found.getValue("wrods").first())
        assertEquals("word", found.getValue("wprd").first())
    }

    @Test fun hinglishWordsAreKnownInHinglish() {
        assertEquals(emptyMap<String, List<String>>(), misspelled("hien", "kal milte hain, nahi toh call karna"))
    }

    @Test fun addressesHandlesAndAcronymsAreLeftAlone() {
        assertEquals(emptyMap<String, List<String>>(),
            misspelled("en", "Mail john@exmaple.com or see github.com/qwzx and #qwzx, the NASA iPhone v2beta"))
    }

    @Test fun wordsCarryTheirPlace() {
        val words = checker("en").check("ok teh")
        assertNull(words[0].suggestions)
        assertEquals(3 to 6, words[1].from to words[1].to)
    }

    @Test fun aKnownWordTheSentenceSaysIsWrongCarriesItsFix() {
        val text = "how to go form here, teh end"
        var kept = emptyList<String>()
        val words = checker("en").check(text) { keep ->
            kept = listOf("form", "teh").filter(keep)
            // A stand-in for tinyfix: "form" should be "from", and a fix for a misspelled word is the checker's own.
            listOf(Fix(10, 14, "form", "from"), Fix(21, 24, "teh", "ten"))
        }
        assertEquals(listOf("teh"), kept)
        val form = words.single { text.substring(it.from, it.to) == "form" }
        assertEquals(listOf("from") to true, form.suggestions to form.wrongWord)
        val teh = words.single { text.substring(it.from, it.to) == "teh" }
        assertEquals("the" to false, teh.suggestions!!.first() to teh.wrongWord)
    }

    /** With tinyfix's files in HEX_TINYFIX_DIR: "form" for "from" is underlined with its fix. */
    @Test fun tinyfixFindsTheWrongWord() {
        val dir = System.getenv("HEX_TINYFIX_DIR")?.let(::File)
        assumeTrue(dir != null && File(dir, "wrong-word.onnx").exists())
        val fixer = TinyfixFiles.load(dir!!)
        val text = "how to go form here"
        val wrong = checker("en").check(text) { keep -> fixer.check(text, keep) }.filter { it.wrongWord }
        assertEquals(listOf("form" to listOf("from")), wrong.map { text.substring(it.from, it.to) to it.suggestions })
    }
}
