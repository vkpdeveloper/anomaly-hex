package ly.anoma.hex.keyboard

import ly.anoma.hex.typing.KeyPoint
import ly.anoma.hex.typing.KeyRect
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Per-press work the key plane used to repeat and now does once or not at all. Gated by HEX_BENCH=1;
 * prints p50/p95/p99 in microseconds and bytes allocated per operation. */
class KeyboardDrawBenchTest {
    // The unit tests compile against android.jar, which has no java.lang.management: reach it by reflection.
    private val threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
    private val allocated = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
    private fun allocatedBytes() = allocated.invoke(threads, Thread.currentThread().id) as Long
    private val letters = KeyboardLetters.rows("en").joinToString("").filter { it.isLetter() }

    private fun measure(name: String, op: (Int) -> Any?) {
        repeat(200_000) { op(it) }
        val micros = DoubleArray(400)
        val before = allocatedBytes()
        for (i in micros.indices) {
            val start = System.nanoTime()
            repeat(500) { op(it) }
            micros[i] = (System.nanoTime() - start) / 1000.0 / 500
        }
        val bytes = (allocatedBytes() - before) / (micros.size * 500L)
        micros.sort()
        fun p(q: Double) = micros[(q * (micros.size - 1)).toInt()]
        println("BENCH %-34s p50=%.3fus p95=%.3fus p99=%.3fus alloc=%dB/op".format(name, p(.5), p(.95), p(.99), bytes))
    }

    @Test fun heldOptionsPerPress() {
        assumeTrue(System.getenv("HEX_BENCH") == "1")
        // Before: each key down rebuilt the option lists (and the list around them) for the pop-up.
        measure("held options, rebuilt") { listOf(LetterFeedback.holds(letters[it % letters.length], "en", null)) }
        // After: the lists are made on the first press of each case and read from then on.
        val cache = letters.associateWith { listOf(LetterFeedback.holds(it, "en", null)) }
        measure("held options, cached") { cache.getValue(letters[it % letters.length]) }
    }

    @Test fun neighbourAreasPerTap() {
        assumeTrue(System.getenv("HEX_BENCH") == "1")
        val rects = letters.associateWith { KeyRect(it.code * 3.0, 0.0, it.code * 3.0 + 40, 90.0) }
        val face = KeyRect(100.0, 0.0, 140.0, 90.0)
        val onKey = KeyPoint(120.0, 40.0)
        // Before: every tap built the other keys' areas, and nearKeys then answered none for a tap on its keycap.
        measure("neighbour areas, built per tap") {
            val areas = HashMap<Char, KeyRect>()
            for ((c, r) in rects) areas[c] = KeyRect(r.left, r.top, r.right, r.bottom)
            nearKeys(onKey, face, areas, 12.0)
        }
        // After: a tap on its own keycap returns before any of that.
        measure("neighbour areas, skipped on keycap") {
            if (onKey.x in face.left..face.right && onKey.y in face.top..face.bottom) null else HashMap<Char, KeyRect>()
        }
    }

    @Test fun glideMovesPerEvent() {
        assumeTrue(System.getenv("HEX_BENCH") == "1")
        val key = Any()
        val trail = GlideTrail(0, 1f)
        // Each round of the measure starts a new drag, so the trail's arrays stay the size of one drag.
        fun restart(i: Int) { if (i == 0) { trail.cancel(); trail.down(key, 0f, 0f, 0, true, 40.0) } }
        // Before: every move event of a drag made a point object for the trail to read.
        measure("glide move, KeyPoint") { restart(it); trail.add(key, KeyPoint(it % 300.0, 10.0), it.toLong()) }
        // After: the trail takes the coordinates.
        measure("glide move, floats") { restart(it); trail.add(key, (it % 300).toFloat(), 10f, it.toLong()) }
    }
}
