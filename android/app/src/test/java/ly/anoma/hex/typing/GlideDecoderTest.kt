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

    /** The same word glided cleanly, with rounded corners, a little off its keys, and past its end. */
    fun variants(word: String, g: KeyGrid) = listOf(
        "clean" to path(word, g),
        "rounded" to path(word, g, smooth = .9 * g.pitchX),
        "sloppy" to path(word, g, smooth = .5 * g.pitchX, jitter = .15, seed = word.hashCode().toLong()),
        "overshoot" to path(word, g, smooth = .4 * g.pitchX, overshoot = .3),
    )
}
