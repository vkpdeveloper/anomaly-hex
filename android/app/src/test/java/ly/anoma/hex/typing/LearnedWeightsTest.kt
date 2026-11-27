package ly.anoma.hex.typing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How [LearnedWords] weighs what it learned: recency, confidence, the two words before, the app. */
class LearnedWeightsTest {
    private val day = 24L * 60 * 60 * 1000
    private fun LearnedWords.use(word: String, times: Int, previous: String? = null, before: String? = null) =
        repeat(times) { learn(word, previous, false, before) }

    @Test fun aRecentWordBeatsAnOldOneUsedMore() {
        var clock = 1_000_000_000_000L
        val learned = LearnedWords { clock }
        learned.use("alpha", 200, "the")
        clock += 365 * day
        learned.use("beta", 5, "the")
        assertEquals("beta", learned.next("the").first())
        assertTrue(learned.mixed(12.0, "beta", "the") < learned.mixed(12.0, "alpha", "the"))
        // Within a few days the bigger count still wins.
        var soon = 1_000_000_000_000L
        val fresh = LearnedWords { soon }
        fresh.use("alpha", 200, "the"); soon += 2 * day; fresh.use("beta", 5, "the")
        assertEquals("alpha", fresh.next("the").first())
    }

    @Test fun fewUsesSoundLikeTheModelAndManyWin() {
        val model = 2.0
        val few = LearnedWords().apply { use("zorp", 2) }
        val many = LearnedWords().apply { use("zorp", 400) }
        assertEquals(model, few.mixed(model, "zorp", null), 0.05)
        assertTrue(many.mixed(model, "zorp", null) < model - 0.5)
        assertTrue(many.mixed(model, "zorp", null) < few.mixed(model, "zorp", null))
    }

    @Test fun twoWordsBeforeBreakATieThePairCannot() {
        val learned = LearnedWords()
        repeat(6) { learned.learn("soon", "you", false, "see") }
        repeat(6) { learned.learn("later", "you", false, "thank") }
        val model = 10.0
        assertTrue(learned.mixed(model, "soon", "you", "see") < learned.mixed(model, "later", "you", "see"))
        assertTrue(learned.mixed(model, "later", "you", "thank") < learned.mixed(model, "soon", "you", "thank"))
    }

    @Test fun theDecoderScoresWithTheTwoWordsBefore() {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
        val learned = LearnedWords().apply { repeat(400) { learn("soon", "you", false, "see") } }
        val decoder = SpatialDecoder(model).also { it.learned = learned }
        assertTrue(decoder.personalCost("soon", model.index("you"), "see") < decoder.personalCost("soon", model.index("you"), null))
    }

    @Test fun anAppPrefersWhatYouWriteInIt() {
        val learned = LearnedWords()
        learned.app = "chat"; learned.use("lol", 10, "haha")
        learned.app = "mail"; learned.use("regards", 10, "kind")
        val model = 12.0
        learned.app = "chat"
        val inChat = learned.mixed(model, "lol", "haha")
        learned.app = "mail"
        assertTrue(inChat < learned.mixed(model, "lol", "haha"))
        // Same counts globally, so the app is the difference.
        learned.app = "chat"
        assertTrue(learned.mixed(model, "lol", "haha") < learned.mixed(model, "regards", "kind"))
    }

    @Test fun onlyEightAppsAreKeptAndForgettingClearsThem() {
        val learned = LearnedWords()
        for (i in 0 until 12) { learned.app = "app$i"; learned.use("word", 2) }
        assertEquals(8, learned.write().lines().count { it.startsWith("a\t") })
        val back = LearnedWords().apply { read(learned.write()) }
        assertEquals(8, back.write().lines().count { it.startsWith("a\t") })
        learned.forgetWord("word")
        assertTrue(learned.write().lines().none { it.startsWith("A\t") || it.startsWith("B\t") })
        back.clear()
        assertTrue(back.write().isEmpty())
    }

    @Test fun anOldFileStillLoadsAtFullWeight() {
        val old = "w\tHex\t3\t0\nw\tbuild\t2\t0\np\thex\tbuild\t3\nt\tmy\thex\tbuild\t2\n"
        val learned = LearnedWords().apply { read(old) }
        assertEquals(listOf("Hex", "build"), learned.words().sortedBy { it.lowercase() != "hex" })
        assertEquals(listOf("build"), learned.next("hex"))
        assertEquals(listOf("build"), learned.next("my", "hex"))
        // Rewritten in the new form, and read back the same.
        val again = LearnedWords().apply { read(learned.write()) }
        assertEquals(learned.words().toSet(), again.words().toSet())
        assertEquals(listOf("build"), again.next("hex"))
    }
}
