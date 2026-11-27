package ly.anoma.hex.typing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The Hinglish layout: English's words with Hindi written in Latin letters, glided and typed.
class HinglishTest {
    private val model = TypingModel.file(File("src/main/assets/typing/hien.hextm"), "hien")
    private val english = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    private val glide = GlideDecoder(model)

    private fun top(model: TypingModel, word: String, previous: String? = null): List<List<String>> {
        val g = Glides.grid()
        val decoder = GlideDecoder(model)
        val context = previous?.let { model.context(it) } ?: -1
        return Glides.variants(word, g).map { (_, path) -> decoder.decode(path, g, context, false, null).map { it.text } }
    }

    @Test fun hindiWordsGlide() {
        for (word in listOf("kya", "khush", "nahi", "hai", "theek", "yaar", "kal", "kaise", "bhai")) {
            for (found in top(model, word)) assertEquals("$word: $found", word, found.firstOrNull())
            // English has none of them.
            assertTrue(word, english.index(word) < 0)
        }
    }

    @Test fun englishWordsStillGlide() {
        for (word in listOf("hello", "the", "keyboard", "would", "really", "about", "because", "people", "thanks", "going"))
            for (found in top(model, word)) assertEquals("$word: $found", word, found.firstOrNull())
    }

    // Spellings that glide alike (a doubled letter is one key) are all offered, the commonest first.
    @Test fun spellingsThatGlideAlikeAreAllOffered() {
        for (found in top(model, "acha")) assertEquals(found.toString(), setOf("accha", "acha", "achha"), found.take(3).toSet())
    }

    // The word before picks between words that glide alike, Hindi or English.
    @Test fun theWordBeforeDecides() {
        for ((previous, word) in listOf("kya" to "hai", "theek" to "hai", "kaise" to "ho", "kal" to "subah", "thank" to "you"))
            for (found in top(model, word, previous)) assertEquals("$previous $word: $found", word, found.firstOrNull())
    }

    // A finger that turns before the a still glides the Hindi word, after the word before or alone.
    @Test fun aHindiWordTheFingerTurnedShortOfStillReads() {
        val g = Glides.grid()
        val short = Glides.moved(g, 'a' to (1.2 to 0.0))
        for ((previous, word) in listOf("kya" to "hai", "" to "nahi", "bas" to "kaam")) {
            val found = glide.decode(Glides.path(word, short, smooth = .5 * g.pitchX), g, model.context(previous), false, null).map { it.text }
            assertEquals("'$previous' $word: $found", word, found.firstOrNull())
        }
    }

    // Hurried glides of the commonest Hindi words: nearly all are still offered.
    @Test fun hurriedHindiGlidesStillOfferTheWord() {
        val g = Glides.grid()
        val words = (0 until model.size).filter { w -> model.length(w) >= 2 && model.text(w).all { it in 'a'..'z' } && english.index(model.text(w)) < 0 }
            .sortedBy { model.frequencyCost(it) }.take(100).map { model.text(it) }
        var shown = 0; var total = 0
        for (word in words) for (seed in 1L..3L) {
            total++
            if (glide.decode(Glides.hurried(word, g, seed), g, -1, false, null).any { it.text == word }) shown++
        }
        println("hurried Hindi glides: in the strip $shown/$total")
        assertTrue("in the strip $shown/$total", shown >= total * .8)
    }

    // Hindi has no one spelling in Latin letters: each common one is a word.
    @Test fun everyCommonSpellingIsAWord() {
        for (word in "nahi nahin nai nhi acha accha achha theek thik yaar yar kyu kyun khush khushi".split(' '))
            assertTrue(word, model.index(word) >= 0)
    }

    // Glided as in English: the commonest thousand English words are nearly all still the first word.
    @Test fun theCommonestEnglishWordsGlide() {
        val g = Glides.grid()
        val words = (0 until english.size).filter { w -> english.length(w) >= 2 && english.text(w).all { it in 'a'..'z' } }
            .sortedBy { english.frequencyCost(it) }.take(1000).map { english.text(it) }
        var first = 0; var englishFirst = 0; var total = 0
        val englishGlide = GlideDecoder(english)
        for (word in words) for ((_, path) in Glides.variants(word, g)) {
            total++
            if (glide.decode(path, g, -1, false, null).firstOrNull()?.text == word) first++
            if (englishGlide.decode(path, g, -1, false, null).firstOrNull()?.text == word) englishFirst++
        }
        println("English words glided: Hinglish first $first/$total, English first $englishFirst/$total")
        assertTrue("first $first/$total, English $englishFirst", first >= englishFirst - total * .03)
    }

    private val grid = KeyGrid(buildMap {
        for ((row, letters) in listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").withIndex())
            for ((i, c) in letters.withIndex()) put(c, KeyPoint(201 + (i - (letters.length - 1) / 2.0) * 39.5, 66.5 + 56 * row))
    }, 39.5, 56.0)
    private fun typed(model: TypingModel, text: String, aimed: String = text) = TypingWord(SpatialDecoder(model), true).apply {
        for ((i, c) in text.withIndex()) {
            val p = grid.centres.getValue(aimed[i])
            letter(c.toString(), p.x, p.y, grid)
        }
    }

    @Test fun hindiWordsAreLeftAsTyped() {
        for (word in listOf("kya", "hai", "nahi", "nhi", "yaar", "acha", "accha", "theek", "kal", "khush", "mujhe", "kuch"))
            assertNull(word, typed(model, word).replacement())
    }

    @Test fun typosAreFixedInBothLanguages() {
        // A slip onto the next key: "nahu" aimed at "nahi", "thw" at "the".
        assertEquals("nahi", typed(model, "nahu", "nahi").replacement())
        assertEquals("the", typed(model, "thw", "the").replacement())
        // English's own rules still apply.
        assertEquals("I'm", typed(model, "im").replacement())
        assertEquals("don't", typed(model, "dont").replacement())
        assertEquals("I", typed(model, "i").replacement())
    }

    // Names take their capital; Hindi words English writes as names ("maine", "I have") don't.
    @Test fun capitals() {
        assertEquals("Delhi", typed(model, "delhi").replacement())
        assertEquals("Maine", typed(english, "maine").replacement())
        assertNull(typed(model, "maine").replacement())
        assertEquals("Monday", typed(model, "monday").replacement())
        // A listed slur the posts write as a name is left as typed, never given a capital.
        assertNull(typed(model, "paki").replacement())
    }
}
