package ly.anoma.hex.typing

import java.io.File
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/** The shortcuts the hot paths take must give what the plain definitions give. */
class TypingFastPathsTest {
    private val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")

    @Test fun personalCompletionsAreThePrefixedWordsShortestFirst() {
        val random = Random(1)
        val words = (0 until 800).map { model.text(random.nextInt(model.words)) } + listOf("Zorbex", "GitHub", "Anoma", "anomaly")
        val personal = PersonalWords(model, words, emptyMap())
        for (prefix in listOf("a", "an", "ano", "g", "gi", "zo", "Zor", "THE", "qx", "s'", "é", "")) {
            // Sorted by letter code, which is the order the list is kept in; equal lengths keep it.
            val expected = personal.completions("").filter { it.length > prefix.length && it.lowercase().startsWith(prefix.lowercase()) }
                .sortedBy { it.length }
            assertEquals(prefix, expected, personal.completions(prefix))
        }
        assertEquals(true, personal.completions("ano").containsAll(listOf("Anoma", "anomaly")))
    }

    // A word hidden from the strip leaves the cached next words and completions at once.
    @Test fun hidingAWordDropsItFromCachedLookups() {
        val own = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
        val the = own.index("the")
        val first = own.nextWords(the, false, fill = false)
        assertEquals(true, first.isNotEmpty())
        val after = own.completionsAfter("a", the, false).map { own.text(it) }
        own.neverSuggest.hide(first + after)
        val now = own.nextWords(the, false, fill = false)
        assertEquals(emptyList<String>(), now.filter { it in first })
        assertEquals(emptyList<String>(), own.completionsAfter("a", the, false).map { own.text(it) }.filter { it in after })
    }

    @Test fun nextWordsAreTheThreeCheapestPairs() {
        for (previous in listOf("the", "i", "of", "to", "thank", "mentally", "zebra").map { model.index(it) }.filter { it >= 0 }) {
            val first = model.nextWords(previous, false, fill = false)
            // Asked again, from the cache: the same words.
            assertEquals(first, model.nextWords(previous, false, fill = false))
            assertEquals(true, first.size <= 3)
            val costs = first.map { model.cost(it, model.text(previous))!! }
            assertEquals(costs.sorted(), costs)
        }
    }
}
