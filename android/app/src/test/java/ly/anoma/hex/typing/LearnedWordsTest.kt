package ly.anoma.hex.typing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnedWordsTest {
    @Test fun theWordThatJustEndedAndTheOneBeforeIt() {
        assertEquals(LearnedWords.Finished("Vaibhav", "hi", false), LearnedWords.finished("Say hi Vaibhav "))
        assertEquals(LearnedWords.Finished("there", null, false), LearnedWords.finished("Hi, there."))
        assertEquals(LearnedWords.Finished("Hello", null, true), LearnedWords.finished("Done. Hello "))
        assertEquals(LearnedWords.Finished("don't", "I", false), LearnedWords.finished("so I don’t "))
        // Not words to learn: a link, a handle, a word run into digits.
        assertNull(LearnedWords.finished("see x.com "))
        assertNull(LearnedWords.finished("ping @vkp "))
        assertNull(LearnedWords.finished("v2 "))
        assertNull(LearnedWords.finished("hello"))
    }

    @Test fun aWordBecomesYoursOnTheSecondUse() {
        val learned = LearnedWords()
        assertTrue(!learned.learn("Vaibhav", "hi", false))
        assertTrue(learned.words().isEmpty())
        assertTrue(learned.learn("Vaibhav", "hi", false))
        assertEquals(listOf("Vaibhav"), learned.words())
        // A sentence's capital teaches no spelling.
        learned.learn("Zorp", null, true); learned.learn("Zorp", null, true)
        assertEquals("zorp", learned.spelling("ZORP"))
    }

    @Test fun yourWordsPullAheadOfTheModelsWithoutReplacingIt() {
        val learned = LearnedWords()
        repeat(5) { learned.learn("hex", "the", false) }
        repeat(5) { learned.learn("build", null, false) }
        val model = 12.0
        assertTrue(learned.mixed(model, "hex", "the") < model)
        assertTrue(learned.mixed(model, "hex", "the") < learned.mixed(model, "hex", null))
        assertEquals(model, learned.mixed(model, "never", null), 0.0)
        assertEquals(listOf("hex"), learned.next("the"))
        assertEquals(listOf("hex"), learned.completions("he"))
    }

    @Test fun theComputersCountsReplaceTheLastOnesAndSurviveASave() {
        val learned = LearnedWords()
        learned.learn("typed", null, false)
        learned.seed(listOf("Anomaly" to 4), listOf(Triple("hex", "app", 3)))
        assertEquals(setOf("Anomaly"), learned.words().toSet())
        learned.seed(listOf("Orukeet" to 2), emptyList())
        assertEquals(setOf("Orukeet"), learned.words().toSet())
        val copy = LearnedWords().apply { read(learned.write()) }
        assertEquals(learned.write().lines().sorted(), copy.write().lines().sorted())
        assertTrue(copy.cost("typed", null).isFinite())
        copy.clear()
        assertTrue(copy.words().isEmpty())
    }
}
