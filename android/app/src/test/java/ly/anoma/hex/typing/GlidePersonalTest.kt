package ly.anoma.hex.typing

import java.io.File
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Your own words glide as tapping types them: a name only in your contacts or personal dictionary, a
 * word you've written, seeded from the computer, on a phone with thousands of each. */
class GlidePersonalTest {
    private val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    private val glide = GlideDecoder(model)
    private val g = Glides.grid()
    private val names = listOf("Vaibhav", "Zarvik", "Quorvane")

    private fun top(word: String, path: List<KeyPoint>, personal: PersonalWords?, learned: LearnedWords? = null, after: String = "") =
        glide.decode(path, g, model.context(after), false, personal, learned).map { it.text }

    @Test fun aNameOnlyInYourWordsGlides() {
        val personal = PersonalWords(model, names, emptyMap())
        for (name in names) for ((how, path) in Glides.variants(name, g)) for (after in listOf("", "hi", "thanks", "with")) {
            val found = top(name, path, personal, after = after)
            assertEquals("$name ($how) after '$after': $found", name, found.firstOrNull())
        }
    }

    @Test fun aNameGlidesAmongThousandsOfYourWords() {
        val (personal, learned) = crowd()
        for (name in names) for ((how, path) in Glides.variants(name, g)) for (after in listOf("", "hi", "thanks")) {
            val found = top(name, path, personal, learned, after)
            assertEquals("$name ($how) after '$after': $found", name, found.firstOrNull())
        }
    }

    @Test fun commonWordsStillWinWithThousandsOfYourWords() {
        val (personal, learned) = crowd()
        for (word in listOf("hello", "the", "keyboard", "would", "really", "about", "because", "people", "thanks", "going"))
            for ((how, path) in Glides.variants(word, g)) {
                val found = top(word, path, personal, learned)
                assertEquals("$word ($how): $found", word, found.firstOrNull())
            }
    }

    // Glide latency with a big personal list: 5,000 contact names and 20,000 words you've written the model lacks.
    @Test fun aBigPersonalListStaysFast() {
        val (personal, learned) = crowd()
        val words = (0 until model.size).filter { w -> model.length(w) >= 2 && model.text(w).all { it in 'a'..'z' } }
            .sortedBy { model.frequencyCost(it) }.take(300).map { model.text(it) } + names
        val paths = words.flatMap { w -> Glides.variants(w, g).map { it.second } }
        repeat(2) { for (p in paths) glide.decode(p, g, -1, false, personal, learned) } // Warm up the JIT.
        val times = paths.map { p -> System.nanoTime().also { glide.decode(p, g, -1, false, personal, learned) }.let { System.nanoTime() - it } / 1e6 }.sorted()
        val bare = paths.map { p -> System.nanoTime().also { glide.decode(p, g, -1, false, null) }.let { System.nanoTime() - it } / 1e6 }.sorted()
        println("glide with ${personal.size} of your words: median %.2f ms, p95 %.2f ms; without: median %.2f ms, p95 %.2f ms".format(
            times[times.size / 2], times[times.size * 95 / 100], bare[bare.size / 2], bare[bare.size * 95 / 100]))
        assertTrue("median ${times[times.size / 2]} ms", times[times.size / 2] < 4 * bare[bare.size / 2] + 1)
    }

    /** A phone with 5,000 contact names and 25,000 words you've written: 5,000 of the model's and 20,000 it lacks. */
    private fun crowd(): Pair<PersonalWords, LearnedWords> {
        val random = Random(7)
        val syllables = listOf("an", "ar", "be", "ka", "ri", "sh", "ta", "vi", "mo", "ne", "la", "de", "jo", "ha", "pr", "ya",
            "su", "ni", "ra", "ve", "ko", "zi", "el", "om", "ush", "it", "ay", "ee", "ch", "dh")
        fun made(parts: Int) = buildString { repeat(parts) { append(syllables[random.nextInt(syllables.size)]) } }
        val contacts = LinkedHashSet<String>()
        while (contacts.size < 5_000) contacts.add(made(2 + random.nextInt(3)).replaceFirstChar { it.uppercase() })
        val learned = LearnedWords()
        val common = (0 until model.size).filter { model.text(it).all { c -> c in 'a'..'z' } }.sortedBy { model.frequencyCost(it) }
            .take(5_000).map { model.text(it) }
        val written = LinkedHashSet<String>(common)
        while (written.size < 25_000) made(2 + random.nextInt(4)).let { if (model.index(it) < 0) written.add(it) }
        learned.seed(written.map { it to 2 + random.nextInt(50) }, emptyList())
        val theirs = learned.words().filter { model.index(it.lowercase()) < 0 }
        return PersonalWords(model, theirs + contacts + names, emptyMap()) to learned
    }
}
