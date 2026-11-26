package ly.anoma.hex.typing

import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The next-word model in the strip: its words come first between words and for a 1–3 letter word, and
// the strip never waits for them.
class NextWordsTest {
    private val english = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    private val hinglish = TypingModel.file(File("src/main/assets/typing/hien.hextm"), "hien")
    private val neural by lazy { NextWordModel.file(File("src/main/assets/typing/next.hexnn")) }
    private val grid = Glides.grid()

    private fun writing(model: TypingModel, next: NextWords?) = TypingWord(SpatialDecoder(model), true).also { it.next = next }
    private fun now() = NextWords({ neural }, Executor { it.run() })
    private fun TypingWord.typed(before: String, text: String) = apply {
        reset(before)
        for (c in text) grid.centres.getValue(c).let { letter(c.toString(), it.x, it.y, grid) }
    }

    @Test fun betweenWordsTheModelsWordsComeFirst() {
        assertEquals(listOf("ka", "se", "ki"), writing(hinglish, now()).predictions("bhai kal "))
        assertEquals(listOf("advice", "recommendation", "tip"), writing(english, now()).predictions("thanks for the "))
        // Without the model the word pairs answer, as before.
        assertTrue(writing(english, null).predictions("thanks for the ").isNotEmpty())
    }

    @Test fun aShortWordIsCompletedFromTheTextBefore() {
        val words = writing(english, now()).typed("can you send me the ", "sc").strip().map { it.text }
        assertTrue(words.toString(), "script" in words)
        val hindi = writing(hinglish, now()).typed("bhai kal ", "sh").strip().map { it.text }
        assertTrue(hindi.toString(), "shaadi" in hindi || "shaam" in hindi)
    }

    // A worker that hasn't answered yet leaves the strip to the word pairs; once it answers, [NextWords.ready]
    // fires and the same question gets the model's words.
    @Test fun theStripNeverWaitsForTheModel() {
        val queued = ArrayList<Runnable>()
        val next = NextWords({ neural }, Executor { queued.add(it) })
        var readied = 0
        next.ready = { readied++ }
        val typing = writing(english, next)
        val before = typing.predictions("thanks for the ")
        assertEquals(1, queued.size)
        // Asking again while it is worked out doesn't queue it twice.
        typing.predictions("thanks for the ")
        assertEquals(1, queued.size)
        queued.removeAt(0).run()
        assertEquals(1, readied)
        val after = typing.predictions("thanks for the ")
        assertEquals(listOf("advice", "recommendation", "tip"), after)
        assertTrue(before != after)
    }

    // A strip worked out before the model answered says so, so the keyboard can wait a moment for its
    // words; a word too long to ask the model, or a model that can't be read, never keeps it waiting.
    @Test fun aStripKnowsWhenTheModelsWordsAreStillComing() {
        val queued = ArrayList<Runnable>()
        val typing = writing(english, NextWords({ neural }, Executor { queued.add(it) }))
        typing.predictions("thanks for the ")
        assertTrue(typing.awaitingModel)
        queued.removeAt(0).run()
        typing.predictions("thanks for the ")
        assertFalse(typing.awaitingModel)
        typing.typed("thanks for the ", "advi").strip()
        assertFalse(typing.awaitingModel)
        val broken = writing(english, NextWords({ throw java.io.IOException("unreadable") }, Executor { it.run() }))
        broken.predictions("thanks for the ")
        assertFalse(broken.awaitingModel)
        assertTrue(broken.predictions("thanks for the ").isNotEmpty())
    }

    // Only the newest question is worked out: the older one waiting behind it is dropped.
    @Test fun olderQuestionsAreDropped() {
        val queued = ArrayList<Runnable>()
        var loads = 0
        val next = NextWords({ loads++; neural }, Executor { queued.add(it) })
        next.words(listOf("thanks", "for"), "")
        next.words(listOf("thanks", "for", "the"), "")
        for (r in queued) r.run()
        queued.clear()
        assertEquals(6, next.words(listOf("thanks", "for", "the"), "")?.size)
        assertNull(next.words(listOf("thanks", "for"), ""))
        assertEquals(1, queued.size)
        assertEquals(1, loads)
    }

    @Test fun contextReadsTheTextAsTheModelWasTrained() {
        assertEquals(listOf("hey", ",", "it's", "here", "."), NextWords.context("Hey, it’s https://x.y/z here @bob #tag."))
        val long = (1..55).joinToString(" ") { "w$it" }
        // Past 40 words the first kept moves on 20 at a time.
        assertEquals("w21", NextWords.context(long).first())
    }
}
