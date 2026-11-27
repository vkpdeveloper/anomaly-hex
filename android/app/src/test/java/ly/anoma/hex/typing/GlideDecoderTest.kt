package ly.anoma.hex.typing

import java.io.File
import java.util.Random
import ly.anoma.hex.keyboard.KeyboardMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.roundToInt

class GlideDecoderTest {
    private val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    private val glide = GlideDecoder(model)

    @Test fun commonWordsTopTheList() {
        val g = Glides.grid()
        for (word in listOf("hello", "the", "keyboard", "would", "really", "about", "because", "people", "thanks", "going")) {
            for ((name, path) in Glides.variants(word, g)) {
                val top = glide.decode(path, g, -1, false, null).map { it.text }
                assertEquals("$word ($name): $top", word, top.firstOrNull())
            }
        }
    }

    @Test fun wordsOnTheSameLineAreAllOffered() {
        val g = Glides.grid()
        for ((word, other) in listOf("tip" to "top", "top" to "tip")) {
            val top = glide.decode(Glides.path(word, g), g, -1, false, null).map { it.text }
            assertTrue("$word: $top", word in top && other in top)
        }
    }

    // The model's commonest words of two letters or more, glided four ways each: nearly all should be in
    // the strip, and most should be the word that goes in. Words that glide alike ("to", "too") are why
    // the first can't be every one.
    @Test fun theCommonestWordsGlide() {
        val g = Glides.grid()
        val words = (0 until model.size).filter { w -> model.length(w) >= 2 && model.text(w).all { it in 'a'..'z' } }
            .sortedBy { model.frequencyCost(it) }.take(1000).map { model.text(it) }
        var first = 0; var shown = 0; var total = 0
        val misses = ArrayList<String>()
        val start = System.nanoTime()
        for (word in words) for ((name, path) in Glides.variants(word, g)) {
            val found = glide.decode(path, g, -1, false, null)
            total++
            if (found.firstOrNull()?.text == word) first++
            if (found.any { it.text == word }) shown++
            else misses.add("$word/$name→" + found.joinToString(",") { "%s:%.1f".format(it.text, it.cost) })
        }
        val ms = (System.nanoTime() - start) / 1e6 / total
        println("glide: first $first/$total, in the strip $shown/$total, %.2f ms each".format(ms))
        println(misses.joinToString("\n"))
        assertTrue("first $first/$total", first >= total * .85)
        assertTrue("in the strip $shown/$total", shown >= total * .97)
    }

    // A finger turns before a far key: the a of "happening" missed by more than a key. The word is
    // still read, after the word before or alone, rather than a rare one the path happened to cross.
    @Test fun aKeyTheFingerTurnedShortOfStillReads() {
        val g = Glides.grid()
        for ((previous, word, moved) in listOf(
            Triple("is", "happening", 'a' to (1.2 to 0.0)),
            Triple("", "happening", 'a' to (1.2 to 0.0)),
            Triple("you", "have", 'a' to (1.2 to 0.0)),
            Triple("want", "another", 'a' to (1.2 to 0.0)),
            Triple("", "people", 'p' to (-1.2 to 0.0)),
            Triple("", "would", 'w' to (1.15 to 0.0)),
        )) {
            val path = Glides.path(word, Glides.moved(g, moved), smooth = .5 * g.pitchX)
            val found = glide.decode(path, g, model.context(previous), false, null).map { it.text }
            assertEquals("'$previous' $word: $found", word, found.firstOrNull())
        }
    }

    // Hurried glides, as a thumb draws them: the commonest words should still nearly all be offered.
    @Test fun hurriedGlidesStillOfferTheWord() {
        val g = Glides.grid()
        val words = (0 until model.size).filter { w -> model.length(w) >= 2 && model.text(w).all { it in 'a'..'z' } }
            .sortedBy { model.frequencyCost(it) }.take(300).map { model.text(it) }
        var first = 0; var shown = 0; var total = 0
        for (word in words) for (seed in 1L..3L) {
            val found = glide.decode(Glides.hurried(word, g, seed), g, -1, false, null).map { it.text }
            total++
            if (found.firstOrNull() == word) first++
            if (word in found) shown++
        }
        println("hurried glides: first $first/$total, in the strip $shown/$total")
        assertTrue("first $first/$total", first >= total * .67)
        assertTrue("in the strip $shown/$total", shown >= total * .85)
    }

    // A rare word is offered beside a common one only when the path fits it about as well: glided
    // cleanly, "world" has no "withhold" beside it, yet "withhold" glided cleanly is read.
    @Test fun rareWordsAreOfferedOnlyWhenThePathFitsThem() {
        val g = Glides.grid()
        for (word in listOf("world", "hello", "thanks", "another")) {
            val found = glide.decode(Glides.path(word, g), g, -1, false, null).map { it.text }
            assertEquals("$word: $found", word, found.firstOrNull())
            for (other in found.drop(1)) assertTrue("$word: $found", model.frequencyCost(model.index(other)) < RARE)
        }
        for (word in listOf("withhold", "erotic", "adjudge", "bestirring"))
            for ((name, path) in Glides.variants(word, g))
                assertEquals("$word ($name)", word, glide.decode(path, g, -1, false, null).firstOrNull()?.text)
        // The commonest words glided four ways: few glides offer a rare word beside them.
        val words = (0 until model.size).filter { w -> model.length(w) >= 2 && model.text(w).all { it in 'a'..'z' } }
            .sortedBy { model.frequencyCost(it) }.take(300).map { model.text(it) }
        var total = 0; var rare = 0
        for (word in words) for ((_, path) in Glides.variants(word, g)) {
            total++
            if (glide.decode(path, g, -1, false, null).drop(1).any { model.frequencyCost(model.index(it.text)) >= RARE }) rare++
        }
        println("glides of the commonest words offering a rare word: $rare/$total")
        assertTrue("rare $rare/$total", rare <= total * .1)
    }

    private companion object {
        /** About the 15,000th commonest word and rarer. */
        const val RARE = 100
    }
}

/** Synthetic glides over the letter keys as Hex lays them out on the OnePlus Nord (1272 px across at
 * 3.5 px per dp), in the rows' coordinates that `KeyPlaneView.geometry` measures. */
internal object Glides {
    private const val WIDTH = 1272.0
    private const val DENSITY = 3.5

    fun grid(rows: List<String> = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")): KeyGrid {
        val side = KeyboardMetrics.SIDE * DENSITY
        val columns = maxOf(rows[0].length, rows[1].length).toDouble()
        val cell = (WIDTH - 2 * side) / columns
        val rowHeight = (KeyboardMetrics.ROW * DENSITY).roundToInt()
        val keyHeight = (KeyboardMetrics.KEY_HEIGHT * DENSITY).roundToInt()
        val centreY = (rowHeight - keyHeight) / 2 + keyHeight / 2.0
        val centres = LinkedHashMap<Char, KeyPoint>()
        for ((r, line) in rows.withIndex()) {
            val shift = (columns - rows[2].length) / 2
            val (start, width) = if (r < 2) (columns - line.length) / 2 to 1.0 else shift to (columns - 2 * shift) / line.length
            for ((i, c) in line.withIndex())
                centres[c] = KeyPoint(side + (start + (i + .5) * width) * cell, r * rowHeight + centreY)
        }
        val q = centres.getValue(rows[0][0]); val w = centres.getValue(rows[0][1]); val a = centres.getValue(rows[1][0])
        val top = 3.0 * rowHeight + (rowHeight - keyHeight) / 2
        return KeyGrid(centres, w.x - q.x, a.y - q.y,
            KeyRect(side + 3.5 * cell + 8, top, side + 7.5 * cell - 8, top + KeyboardMetrics.LAST_KEY_HEIGHT * DENSITY))
    }

    /** A finger's path through each letter's key centre, a point every few pixels as touch events come.
     * [smooth] rounds the corners as a quick finger does (a moving average over that many pixels);
     * [jitter] moves each key's point by that share of a key at random; [overshoot] runs past the end. */
    fun path(word: String, g: KeyGrid, smooth: Double = 0.0, jitter: Double = 0.0, overshoot: Double = 0.0, seed: Long = 1): List<KeyPoint> {
        val random = Random(seed)
        val keys = ArrayList<KeyPoint>()
        for (c in word.lowercase()) {
            val p = g.centres[c] ?: continue
            if (keys.isNotEmpty() && keys.last() == p) continue
            keys.add(p)
        }
        val corners = keys.map { KeyPoint(it.x + random.nextGaussian() * jitter * g.pitchX, it.y + random.nextGaussian() * jitter * g.pitchY) }
            .toMutableList()
        if (overshoot > 0 && corners.size >= 2) {
            val a = corners[corners.size - 2]; val b = corners.last()
            val l = hypot(b.x - a.x, b.y - a.y)
            corners.add(KeyPoint(b.x + (b.x - a.x) / l * overshoot * g.pitchX, b.y + (b.y - a.y) / l * overshoot * g.pitchX))
        }
        val dense = ArrayList<KeyPoint>()
        dense.add(corners[0])
        for (i in 1 until corners.size) {
            val a = corners[i - 1]; val b = corners[i]
            val steps = maxOf(1, (hypot(b.x - a.x, b.y - a.y) / 6).toInt())
            for (s in 1..steps) dense.add(KeyPoint(a.x + (b.x - a.x) * s / steps, a.y + (b.y - a.y) * s / steps))
        }
        if (smooth <= 0) return dense
        val half = maxOf(1, (smooth / 6 / 2).toInt())
        return dense.indices.map { i ->
            val lo = maxOf(0, i - half); val hi = minOf(dense.lastIndex, i + half)
            val span = minOf(i - lo, hi - i)
            val from = i - span; val to = i + span
            KeyPoint((from..to).sumOf { dense[it].x } / (to - from + 1), (from..to).sumOf { dense[it].y } / (to - from + 1))
        }
    }

    /** [g] with some keys moved by (across, down) keys: a path drawn on it misses those keys on [g] as a
     * finger that turns short of them does. */
    fun moved(g: KeyGrid, vararg moves: Pair<Char, Pair<Double, Double>>): KeyGrid {
        val centres = LinkedHashMap(g.centres)
        for ((c, by) in moves) centres[c] = centres.getValue(c).let { KeyPoint(it.x + by.first * g.pitchX, it.y + by.second * g.pitchY) }
        return KeyGrid(centres, g.pitchX, g.pitchY, g.space)
    }

    /** A hurried glide as a thumb draws one: corners rounded over a key and a half, each key's point
     * well off it, the whole path a little off the keys and a little past its end. */
    fun hurried(word: String, g: KeyGrid, seed: Long): List<KeyPoint> {
        val random = Random(seed * 31 + word.hashCode())
        val dx = random.nextGaussian() * .18 * g.pitchX; val dy = random.nextGaussian() * .18 * g.pitchY
        return path(word, g, smooth = 1.5 * g.pitchX, jitter = .42, overshoot = random.nextDouble() * .3, seed = seed + word.hashCode())
            .map { KeyPoint(it.x + dx, it.y + dy) }
    }

    /** The same word glided cleanly, with rounded corners, a little off its keys, and past its end. */
    fun variants(word: String, g: KeyGrid) = listOf(
        "clean" to path(word, g),
        "rounded" to path(word, g, smooth = .9 * g.pitchX),
        "sloppy" to path(word, g, smooth = .5 * g.pitchX, jitter = .15, seed = word.hashCode().toLong()),
        "overshoot" to path(word, g, smooth = .4 * g.pitchX, overshoot = .3),
    )
}
