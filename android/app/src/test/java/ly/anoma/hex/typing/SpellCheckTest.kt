package ly.anoma.hex.typing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
