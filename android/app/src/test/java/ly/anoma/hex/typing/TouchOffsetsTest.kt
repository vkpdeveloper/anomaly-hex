package ly.anoma.hex.typing

import java.io.File
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The personal tap model: a synthetic thumb that lands low and right of every key. */
class TouchOffsetsTest {
    private val grid = Glides.grid()
    private val dx = .3; private val dy = .3

    /** One word's taps: each key's centre, moved by the thumb's bias and some scatter. */
    private fun taps(word: String, random: Random, sd: Double = .3) = word.map { c ->
        val p = grid.centres.getValue(c)
        KeyPoint(p.x + (dx + random.nextGaussian() * sd) * grid.pitchX, p.y + (dy + random.nextGaussian() * sd) * grid.pitchY)
    }

    private fun words(count: Int, skip: Int) = (0 until model.size).filter { w ->
        model.length(w) in 4..8 && model.text(w).all { it in grid.centres }
    }.sortedBy { model.frequencyCost(it) }.drop(skip).take(count).map { model.text(it) }

    /** Words the keyboard gets wrong when [g] scores these taps, with each word read as it would be at its space. */
    private fun errors(g: KeyGrid, list: List<String>, seed: Long): Int {
        val random = Random(seed)
        val state = TypingWord(SpatialDecoder(model), true)
        var wrong = 0
        for (word in list) {
            state.reset("")
            for ((i, p) in taps(word, random).withIndex()) state.letter(word[i].toString(), p.x, p.y, g)
            val read = state.finish(' ')?.replacement?.trim() ?: state.token
            if (read != word) wrong++
        }
        return wrong
    }

    /** Letters whose nearest aimed key isn't the one meant: what a person sees as a wrong letter. */
    private fun letterErrors(g: KeyGrid, list: List<String>, seed: Long): Int {
        val random = Random(seed)
        var wrong = 0
        for (word in list) for ((i, p) in taps(word, random).withIndex()) {
            val nearest = g.aimed.minBy { (_, c) -> Math.hypot((p.x - c.x) / g.pitchX, (p.y - c.y) / g.pitchY) }.key
            if (nearest != word[i]) wrong++
        }
        return wrong
    }

    private fun trained(): TouchOffsets {
        val touch = TouchOffsets()
        val random = Random(7)
        for (word in words(600, 0)) touch.learn(word, taps(word, random), grid)
        return touch
    }

    @Test fun aBiasedThumbReadsBetterOnceTheKeysAreAimedWhereItLands() {
        val test = words(3000, 600)
        val before = errors(grid, test, 99)
        val after = errors(trained().personalised(grid), test, 99)
        val letters = test.sumOf { it.length }
        val lettersBefore = letterErrors(grid, test, 99)
        val lettersAfter = letterErrors(trained().personalised(grid), test, 99)
        println("touch offsets: word errors $before -> $after of ${test.size}; letter errors $lettersBefore -> $lettersAfter of $letters")
        assertTrue("$before -> $after", after < before * .85)
        assertTrue("$lettersBefore -> $lettersAfter", lettersAfter < lettersBefore * .7)
    }

    @Test fun anUnbiasedThumbMovesNothingMuch() {
        val touch = TouchOffsets()
        val random = Random(3)
        for (word in words(400, 0)) touch.learn(word, word.map { c ->
            val p = grid.centres.getValue(c)
            KeyPoint(p.x + random.nextGaussian() * .2 * grid.pitchX, p.y + random.nextGaussian() * .2 * grid.pitchY)
        }, grid)
        for (c in grid.centres.keys) assertTrue("$c", Math.hypot(touch.offset(c).x, touch.offset(c).y) < .08)
    }

    @Test fun nothingMovesBeforeEnoughTaps() {
        val touch = TouchOffsets()
        val random = Random(1)
        touch.learn("hello", taps("hello", random), grid)
        assertEquals(0.0, touch.offset('h').x, 0.0)
        assertEquals(grid.centres.getValue('h'), touch.personalised(grid).aimed.getValue('h'))
    }

    @Test fun anOffsetNeverExceedsTheCap() {
        val touch = TouchOffsets()
        // Every tap lands .7 pitch off, the farthest that still counts.
        repeat(100) {
            val p = grid.centres.getValue('e')
            touch.learn("e", listOf(KeyPoint(p.x + .7 * grid.pitchX, p.y)), grid)
        }
        val o = touch.offset('e')
        assertTrue(o.x <= TouchOffsets.MAX_OFFSET + 1e-9)
        assertEquals(TouchOffsets.MAX_OFFSET, Math.hypot(o.x, o.y), 1e-9)
    }

    @Test fun aTapOnAnotherKeyTeachesNothing() {
        val touch = TouchOffsets()
        val p = grid.centres.getValue('f')
        repeat(50) { touch.learn("d", listOf(KeyPoint(p.x, p.y)), grid) }
        assertEquals(0.0, touch.offset('d').x, 0.0)
    }

    @Test fun olderTapsFadeSoItFollowsYourGrip() {
        val touch = trained()
        val random = Random(5)
        val before = touch.offset('e').x
        assertTrue(before > .1)
        for (word in words(1500, 600)) touch.learn(word, word.map { c ->
            val p = grid.centres.getValue(c)
            KeyPoint(p.x - .2 * grid.pitchX, p.y + random.nextGaussian() * .1 * grid.pitchY)
        }, grid)
        assertTrue(touch.offset('e').x < 0)
    }

    @Test fun savedOffsetsComeBack() {
        val touch = trained()
        val again = TouchOffsets().also { it.read(touch.write()) }
        for (c in grid.centres.keys) assertEquals(touch.offset(c).x, again.offset(c).x, 1e-9)
        assertTrue(again.offset('e').x > .1)
    }

    @Test fun forgettingWipesEverything() {
        val touch = trained()
        val before = touch.version
        touch.clear()
        assertTrue(touch.version > before)
        for (c in grid.centres.keys) assertEquals(0.0, touch.offset(c).x, 0.0)
        assertEquals("hex-touch 1\n", touch.write())
    }

    @Test fun personalisingStaysFast() {
        val touch = trained()
        val g = touch.personalised(grid)
        val decoder = SpatialDecoder(model)
        val start = System.nanoTime()
        repeat(2000) { decoder.score(g, 300.0, 100.0) }
        val each = (System.nanoTime() - start) / 2000 / 1e6
        assertTrue("$each ms per tap", each < 1.0)
    }

    private companion object {
        val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    }
}
