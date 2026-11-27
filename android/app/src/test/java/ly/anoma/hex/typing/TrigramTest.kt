package ly.anoma.hex.typing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The words you write after two words: learned, offered, saved, forgotten, and fast.
class TrigramTest {
    private val english = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    private val grid = Glides.grid()

    private fun LearnedWords.say(text: String, times: Int = 1) = repeat(times) {
        val words = text.split(' ')
        for (i in words.indices) learn(words[i], words.getOrNull(i - 1), false, words.getOrNull(i - 2))
    }
    private fun writing(learned: LearnedWords) = TypingWord(SpatialDecoder(english).also { it.learned = learned }, true)

    @Test fun theTwoWordsBeforeChooseWhatIsOffered() {
        val learned = LearnedWords()
        learned.say("see you soon", 3); learned.say("tell you everything", 3); learned.say("see you later", 2)
        val word = writing(learned)
        assertEquals(listOf("soon", "later"), word.predictions("see you ").take(2))
        assertEquals("everything", word.predictions("tell you ").first())
        // Just one word of context falls back to the pairs, which hold all three.
        assertEquals(setOf("soon", "everything", "later"), learned.next("you", 3).toSet())
    }

    @Test fun aSingleUseIsNotOffered() {
        val learned = LearnedWords()
        learned.say("park your zebra")
        assertTrue(learned.next("park", "your").isEmpty())
    }

    @Test fun theLetterYouTypeCompletesYourPhrase() {
        val learned = LearnedWords()
        learned.say("send the invoice", 3); learned.say("send the report", 2)
        val word = writing(learned)
        word.reset("send the ")
        for (c in "inv") grid.centres.getValue(c).let { word.letter(c.toString(), it.x, it.y, grid) }
        assertEquals("invoice", word.strip().first { it.kind != Suggestion.Kind.Typed }.text)
    }

    @Test fun triplesSurviveASaveAndAnOldFileStillReads() {
        val learned = LearnedWords()
        learned.say("on my way", 3)
        val copy = LearnedWords().apply { read(learned.write()) }
        assertEquals(listOf("way"), copy.next("on", "my"))
        val old = LearnedWords().apply { read("w\thex\t4\t0\np\tthe\thex\t3\ns\tthe\tapp\t2\n") }
        assertEquals(listOf("hex", "app"), old.next(null, "the"))
        // A line it cannot read is skipped.
        LearnedWords().read("t\tonly\ttwo\nt\ta\tb\tc\tmany\n")
    }

    @Test fun forgettingWipesThem() {
        val learned = LearnedWords()
        learned.say("on my way", 3)
        learned.clear()
        assertTrue(learned.next("on", "my").isEmpty())
        assertEquals("", learned.write())
    }

    @Test fun forgettingAWordDropsItsPhrases() {
        val learned = LearnedWords()
        learned.say("see you soon", 3); learned.say("on my way", 3)
        learned.forgetWord("soon")
        assertTrue(learned.next("see", "you").isEmpty())
        learned.forgetWord("my")
        assertTrue(learned.next("on", "my").isEmpty())
        assertTrue(learned.write().lines().none { it.startsWith("t\t") })
    }

    @Test fun keptTriplesStayBounded() {
        val learned = LearnedWords()
        for (i in 0 until 70_000) learned.say("a b w$i".replace(Regex("\\d"), { ('a' + it.value.toInt()).toString() }))
        assertTrue(learned.write().lines().count { it.startsWith("t\t") } <= 60_000)
    }

    // Your own phrases (where the word after two differs from the word after one), trained on half
    // the sentences and tested on the other half: how often the next word is among the three offered.
    @Test fun trigramsOfferMoreOfWhatYouWrite() {
        val subjects = listOf("see", "tell", "call", "ask", "thank", "text")
        val objects = listOf("you", "him", "her", "them")
        val endings = mapOf("see you" to "soon", "tell you" to "everything", "call you" to "later", "ask you" to "something",
            "thank you" to "very", "text you" to "tonight", "see him" to "tomorrow", "tell him" to "nothing", "call him" to "back",
            "ask him" to "directly", "thank him" to "again", "text him" to "now", "see her" to "tonight", "tell her" to "gently",
            "call her" to "twice", "ask her" to "politely", "thank her" to "kindly", "text her" to "first", "see them" to "often",
            "tell them" to "plainly", "call them" to "maybe", "ask them" to "nicely", "thank them" to "warmly", "text them" to "again")
        val sentences = subjects.flatMap { s -> objects.map { o -> "$s $o ${endings.getValue("$s $o")}" } }
        val withTriples = LearnedWords(); val pairsOnly = LearnedWords()
        repeat(3) {
            for (text in sentences) {
                val w = text.split(' ')
                for (i in w.indices) {
                    withTriples.learn(w[i], w.getOrNull(i - 1), false, w.getOrNull(i - 2))
                    pairsOnly.learn(w[i], w.getOrNull(i - 1), false)
                }
            }
        }
        fun hits(learned: LearnedWords): Int {
            val word = writing(learned)
            return sentences.count { text ->
                val w = text.split(' ')
                w[2] in word.predictions(w[0] + " " + w[1] + " ")
            }
        }
        val (a, b) = hits(withTriples) to hits(pairsOnly)
        println("next-word strip hits over ${sentences.size} phrases: pairs only $b, with trigrams $a")
        assertTrue("$a vs $b", a > b)
    }

    @Test fun aPredictionStaysFarUnderAMillisecond() {
        val learned = LearnedWords()
        val letters = "abcdefghijklmnopqrstuvwxyz"
        fun word(n: Int) = buildString { var x = n; repeat(4) { append(letters[x % 26]); x /= 26 } }
        for (i in 0 until 60_000) learned.say("${word(i % 900)} ${word(i % 700)} ${word(i)}")
        val word = writing(learned)
        val texts = (0 until 200).map { "${word(it)} ${word(it % 700)} " }
        repeat(300) { for (t in texts) word.predictions(t) }  // warm up
        val start = System.nanoTime()
        var calls = 0
        repeat(5) { for (t in texts) { word.predictions(t); calls++ } }
        val perCall = (System.nanoTime() - start) / 1e6 / calls
        println("predictions with 60k learned triples: ${"%.4f".format(perCall)} ms per call")
        assertTrue("$perCall ms", perCall < 0.5)
    }
}
