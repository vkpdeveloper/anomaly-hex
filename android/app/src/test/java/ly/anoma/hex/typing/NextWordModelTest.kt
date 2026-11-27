package ly.anoma.hex.typing

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The phone's next-word model against the training scripts: the same pieces and the same words
// (benchmark/typing/neural/export.py writes the fixture from the int8 model).
class NextWordModelTest {
    private val model = NextWordModel.file(File("src/main/assets/typing/next.hexnn"))
    private val fixture = File("src/test/resources/next-fixture.tsv").readLines()

    @Test fun splitsTextAsSentencePieceDoes() {
        val rows = fixture.dropWhile { !it.startsWith("# pieces") }.drop(1).takeWhile { !it.startsWith("#") }
        assertEquals(5, rows.size)
        for (row in rows) {
            val (text, ids) = row.split('\t')
            assertArrayEquals(text, ids.split(' ').map { it.toInt() }.toIntArray(), model.encode(text))
        }
    }

    @Test fun predictsWhatTheTrainingScriptsPredict() {
        val rows = fixture.dropWhile { !it.startsWith("# predictions") }.drop(1)
        assertEquals(52, rows.size)
        for (row in rows) {
            val (context, prefix, words) = row.split('\t')
            val before = context.split(' ').filter { it.isNotEmpty() }
            assertEquals("'$context' + '$prefix'", words, model.predict(before, prefix).joinToString(" "))
        }
    }

    // The cache of the words before: typing on, going back and starting over give what a fresh model gives.
    @Test fun cachedWordsBeforeGiveTheSameWords() {
        val fresh = { words: List<String>, prefix: String -> NextWordModel.file(File("src/main/assets/typing/next.hexnn")).predict(words, prefix) }
        val texts = listOf("bhai kal", "bhai kal milte", "bhai kal milte hai", "bhai kal", "kya scene hai", "kya scene hai aaj")
        for (t in texts) {
            val words = t.split(' ')
            assertEquals(t, fresh(words, ""), model.predict(words, ""))
            assertEquals(t, fresh(words, "s"), model.predict(words, "s"))
        }
    }

    // Long text: the model reads a window of the last pieces, refilled every few words, and keeps answering.
    @Test fun longTextKeepsAWindow() {
        val words = ("so yesterday i went to the market with my brother and we bought a lot of vegetables and fruits " +
            "then we came back home and mom made dinner for everyone and after that we watched a movie together " +
            "it was really nice and i think we should do it again next weekend if you are").split(' ')
        for (n in 30..words.size) assertTrue("$n", model.predict(words.take(n), "").size >= 3)
    }

    @Test fun timing() {
        val words = "hey are you coming to the party tonight or".split(' ')
        repeat(3) { model.predict(words, ""); model.predict(words, "s") }
        val runs = 20
        var next = 0L; var typed = 0L
        for (i in 0 until runs) {
            val w = words.take(4 + i % 6)
            var t = System.nanoTime(); model.predict(w, ""); next += System.nanoTime() - t
            t = System.nanoTime(); model.predict(w, "s"); typed += System.nanoTime() - t
        }
        println("next word: ${next / runs / 1_000_000.0} ms, with a letter: ${typed / runs / 1_000_000.0} ms")
    }
}
