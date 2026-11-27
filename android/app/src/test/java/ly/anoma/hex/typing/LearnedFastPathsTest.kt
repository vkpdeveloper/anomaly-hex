package ly.anoma.hex.typing

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shortcuts [LearnedWords] takes on a big store must give what the plain definitions give. */
class LearnedFastPathsTest {
    // A file's lines but the `h` one, which carries when it was saved.
    private fun body(file: String) = file.lines().filter { !it.startsWith("h\t") }.sorted()
    private fun LearnedWords.use(word: String, times: Int) = repeat(times) { learn(word, null, false) }

    @Test fun completionsAreYourPrefixedWordsMostUsedFirst() {
        val learned = LearnedWords()
        learned.use("Kubernetes", 5); learned.use("kubectl", 9); learned.use("kuber", 2); learned.use("kube", 1); learned.use("kiwi", 7)
        assertEquals(listOf("kubectl", "Kubernetes", "kuber"), learned.completions("ku"))
        assertEquals(listOf("kubectl", "Kubernetes", "kuber"), learned.completions("KU"))
        // One use is a slip: not offered. The word typed whole isn't completed.
        assertEquals(listOf("kubectl", "Kubernetes"), learned.completions("kube", limit = 2))
        assertEquals(emptyList<String>(), learned.completions("kubectl"))
        assertEquals(listOf("kubectl", "kiwi", "Kubernetes"), learned.completions("k"))

        // What changes between asks shows at once, though the strip asks again for the same prefix.
        learned.use("kubeadm", 20)
        assertEquals("kubeadm", learned.completions("ku").first())
        learned.unlearn("kubeadm", null)
        repeat(19) { learned.unlearn("kubeadm", null) }
        assertEquals(listOf("kubectl", "Kubernetes", "kuber"), learned.completions("ku"))
        learned.forgetWord("kubectl")
        assertEquals(listOf("Kubernetes", "kuber"), learned.completions("ku"))
        learned.seed(listOf("kubelet" to 40), emptyList())
        assertEquals(listOf("kubelet", "Kubernetes", "kuber"), learned.completions("ku"))
        learned.clear()
        assertEquals(emptyList<String>(), learned.completions("ku"))
    }

    @Test fun aFileReadsBackAsItWasWritten() {
        val random = Random(3)
        val learned = LearnedWords()
        val words = List(300) { buildString { repeat(3 + random.nextInt(4)) { append('a' + random.nextInt(26)) } } }
        repeat(4000) { learned.learn(words[random.nextInt(300)], words[random.nextInt(300)], false, words[random.nextInt(300)]) }
        learned.learnAddress("me@example.com")
        learned.seed(listOf("seeded" to 5), listOf(Triple("a", "b", 3)))
        val text = learned.write()
        val again = LearnedWords().also { it.read(text) }
        assertEquals(body(text), body(again.write()))
        // Windows line ends and stray lines read like the rest.
        val crlf = LearnedWords().also { it.read(text.replace("\n", "\r\n") + "\nx\ty\n\tw\t1\t1\n") }
        assertEquals(body(text), body(crlf.write()))
        assertEquals(learned.completions("a"), again.completions("a"))
    }

    @Test fun aFullStoreDropsItsLeastUsedFirst() {
        val random = Random(8)
        val learned = LearnedWords()
        val once = ArrayList<String>(); val often = ArrayList<String>()
        val seen = HashSet<String>()
        while (seen.size < 21_000) {
            val w = buildString { repeat(4 + random.nextInt(4)) { append('a' + random.nextInt(26)) } }
            if (!seen.add(w)) continue
            if (seen.size % 4 == 0) { often.add(w); learned.use(w, 3) } else { once.add(w); learned.use(w, 1) }
        }
        // Over the 20,000 cap the single uses go first; every word used three times stays.
        val kept = learned.write().lines().filter { it.startsWith("w\t") }.map { it.split('\t')[1] }
        assertTrue("kept ${kept.size}", kept.size in often.size..20_000)
        assertTrue(kept.toSet().containsAll(often))
    }
}
