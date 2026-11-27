package ly.anoma.hex.typing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The emoji search: Unicode's names and the lists' words, forgiving of forms, prefixes and slips. */
class EmojiSearchTest {
    private val search = EmojiWords(listOf(lists("en"), lists("slang")), names, listOf(lists("en"), lists("slang"), lists("hien")))
    private fun find(query: String, picks: EmojiWords.Picks = EmojiWords.Picks()) = search.search(query, picks)

    @Test fun namesAndKeywordsFindTheirEmoji() {
        assertEquals("❤️", find("red heart").first())
        assertTrue("🔥" in find("fire").take(3))
        assertTrue("🍕" in find("pizza").take(3))
    }

    @Test fun hinglishWordsFindTheirEmoji() {
        assertTrue(find("gussa").take(3).any { it == "😡" || it == "😠" })
        assertTrue("☕" in find("chai").take(3))
    }

    @Test fun slangFindsItsEmoji() {
        assertEquals("🍃", find("weed").first())
        assertEquals("🤨", find("sus").first())
    }

    @Test fun oneLetterFindsInTheSearchButNotOnTheStrip() {
        assertTrue("👍" in find("k"))
        assertNull(EmojiWords(listOf(lists("slang"))).find("k", EmojiWords.Picks()))
    }

    @Test fun aWordStillBeingTypedFindsByItsStart() {
        assertTrue(find("piz").take(3).contains("🍕"))
        assertTrue(find("lau").isNotEmpty())
    }

    @Test fun formsAndSlipsStillFind() {
        assertTrue("🍕" in find("pizzas").take(3))
        assertTrue(find("hapy").take(10).any { it in setOf("😀", "😃", "😄", "😁", "😊") })
    }

    @Test fun everyWordOfASearchMustMatch() {
        val hearts = find("red heart")
        assertTrue(hearts.none { it == "💙" || it == "💚" })
    }

    @Test fun whatYouPickedForASearchComesFirst() {
        val picks = EmojiWords.Picks()
        val first = find("fire").first()
        val other = find("fire").first { it != first }
        picks.pick("fire", other)
        assertEquals(other, find("fire", picks).first())
    }

    @Test fun aColonStartsASearchOnlyAfterASpaceWithTwoLetters() {
        assertEquals("fire", EmojiWords.colonQuery(":fire"))
        assertEquals("fi", EmojiWords.colonQuery("hot :fi"))
        assertNull(EmojiWords.colonQuery(":f"))
        assertNull(EmojiWords.colonQuery(":)"))
        assertNull(EmojiWords.colonQuery(":p"))
        assertNull(EmojiWords.colonQuery("10:30"))
        assertNull(EmojiWords.colonQuery("https:"))
        assertNull(EmojiWords.colonQuery("https://fire"))
        assertNull(EmojiWords.colonQuery("note:fire"))
        assertNull(EmojiWords.colonQuery(":fire "))
    }

    private companion object {
        val names = EmojiWords.Named.parse(File("src/main/assets/typing/emoji.names").readText())
        val parsed = HashMap<String, Map<String, List<String>>>()
        fun lists(code: String) = parsed.getOrPut(code) { EmojiWords.parse(File("src/main/assets/typing/$code.emoji").readText()) }
    }
}
