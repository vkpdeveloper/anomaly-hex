package ly.anoma.hex.typing

import java.io.File
import java.util.Random
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Latency of the typing hot paths on the real English model, in microseconds. Skipped unless the
 * environment has HEX_BENCH=1:
 * `HEX_BENCH=1 ./gradlew :app:testDebugUnitTest --tests '*KeyboardBenchTest*' -i`. */
class KeyboardBenchTest {
    private val file = File("src/main/assets/typing/en.hextm")
    private val grid = KeyGrid(buildMap {
        for ((row, letters) in listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").withIndex())
            for ((i, c) in letters.withIndex()) put(c, KeyPoint(201 + (i - (letters.length - 1) / 2.0) * 39.5, 66.5 + 56 * row))
    }, 39.5, 56.0, KeyRect(64.76, 212.0, 303.1, 257.0))
    private val space = KeyPoint(183.93, 234.5)

    private val sentences = listOf(
        "hey are you coming to the meeting tomorrow", "i think we should move the deadline to friday",
        "thanks so much for sending that over yesterday", "can you please check the latest version of the document",
        "the weather is really nice today so lets go outside", "i dont know what happened to my phone last night",
        "we need to talk about the budget before the end of the month", "she said that she would call me back later this afternoon",
        "did you see the game last night it was incredible", "remember to bring your passport and the tickets",
        "the quick brown fox jumps over the lazy dog", "i will be there in about ten minutes",
        "could you send me the address of the restaurant", "everything works perfectly after the update",
        "my teh keyboard is definately better than before", "wheres the nearest pharmacy i need some medicine",
        "happy birthday hope you have a wonderful day", "please let me know if you have any questions about the project",
        "unfortunately the flight was delayed by several hours", "i am working on the presentation for next week",
        "good morning how did you sleep", "the restaurant was fantastic and the service was excellent",
        "lets meet at the station around seven oclock", "we should probably get started before everyone arrives",
    )

    private class Stats(val name: String) {
        private var samples = LongArray(1 shl 16); private var n = 0
        var bytes = 0L
        fun add(nanos: Long) { if (n == samples.size) samples = samples.copyOf(n * 2); samples[n++] = nanos }
        fun report(): String {
            val s = samples.copyOf(n); s.sort()
            fun p(q: Double) = if (n == 0) 0.0 else s[minOf(n - 1, (q * n).toInt())] / 1000.0
            val kb = if (n == 0) 0.0 else bytes / 1024.0 / n
            return "%-36s n=%-6d mean=%8.1f p50=%9.1f p95=%9.1f p99=%9.1f max=%9.1f us  alloc=%8.1f KB/op".format(name, n, if (n == 0) 0.0 else s.average() / 1000.0, p(.5), p(.95), p(.99), p(1.0), kb)
        }
    }

    // android.jar hides java.lang.management from the compile classpath, so reach it by reflection.
    private val mxBean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
    private val allocated = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
    private fun allocatedBytes(id: Long) = allocated.invoke(mxBean, id) as Long
    private inline fun <T> measure(stats: Stats, block: () -> T): T {
        val id = Thread.currentThread().id
        val a = allocatedBytes(id)
        val t = System.nanoTime()
        val r = block()
        stats.add(System.nanoTime() - t)
        stats.bytes += allocatedBytes(id) - a
        return r
    }

    private fun personal(model: TypingModel, learned: Int, names: Int): PersonalWords {
        val random = Random(7)
        val words = ArrayList<String>()
        repeat(learned) { words.add(model.text(random.nextInt(model.words)).let { it + "x".repeat(random.nextInt(2)) + if (random.nextBoolean()) "" else "s" }) }
        val letters = "abcdefghijklmnopqrstuvwxyz"
        repeat(names) { words.add(buildString { repeat(4 + random.nextInt(5)) { append(letters[random.nextInt(26)]) } }.replaceFirstChar { it.uppercase() }) }
        return PersonalWords(model, words, mapOf("omw" to "on my way", "myem" to "me@example.com"))
    }

    private fun run(label: String, decoder: SpatialDecoder, rounds: Int, record: Boolean, out: MutableList<Stats>) {
        val key = Stats("$label keystroke (likelier+letter+strip)"); val decide = Stats("$label strip(autocorrect) at pause")
        val finish = Stats("$label finish at space"); val predict = Stats("$label predictions after space")
        val full = Stats("$label worst keystroke incl. pause work")
        val w = TypingWord(decoder, true)
        val random = Random(3)
        repeat(rounds) {
            for (sentence in sentences) {
                var before = ""
                w.reset("")
                for (c in sentence) {
                    if (c == ' ') {
                        measure(decide) { w.strip(true) }
                        val fix = measure(finish) { w.finish(' ', space, grid) }
                        before = if (fix != null) before.dropLast(fix.original.length) + fix.replacement else before
                        before += " "
                        w.reset(before)
                        measure(predict) { w.predictions(before, capitalize = false) }
                        continue
                    }
                    if (c == '\'') { w.explicit("'"); before += c; continue }
                    val p = grid.centres.getValue(c)
                    val x = p.x + random.nextGaussian() * 7; val y = p.y + random.nextGaussian() * 9
                    val near = grid.centres.entries.filter { it.key != c && Math.abs(it.value.x - x) < 30 && Math.abs(it.value.y - y) < 30 }
                        .associate { it.key to Math.hypot(it.value.x - x, it.value.y - y) / 2.0 }
                    measure(key) {
                        w.likelierKey(before, c, near, grid.centres.keys)
                        w.letter(c.toString(), x, y, grid)
                        w.strip(false)
                    }
                    before += c
                }
                w.strip(true)
                w.finish('.')
            }
        }
        if (record) out += listOf(key, decide, finish, predict)
    }

    @Test fun bench() {
        assumeTrue(System.getenv("HEX_BENCH") == "1")
        val out = ArrayList<Stats>()
        val loadStats = Stats("model load (file map + init)")
        repeat(3) { TypingModel.file(file, "en") }
        repeat(8) { measure(loadStats) { TypingModel.file(file, "en") } }
        out += loadStats
        val model = TypingModel.file(file, "en")
        val plain = SpatialDecoder(model)
        run("plain", plain, 3, false, out) // warm up the JIT
        run("plain", plain, 6, true, out)

        val own = SpatialDecoder(model)
        val build = Stats("PersonalWords build 2000+500")
        repeat(3) { personal(model, 2000, 500) }
        own.personal = measure(build) { personal(model, 2000, 500) }
        out += build
        run("personal", own, 2, false, out)
        run("personal", own, 6, true, out)

        // The decoder alone, on every word of the sentences with taps scattered like a real thumb.
        val isolated = Stats("decide() alone, misses and hits")
        val random = Random(11)
        val words = sentences.flatMap { it.split(' ') }.filter { it.length in 2..32 }
        repeat(6) { round ->
            for (word in words) {
                val taps = word.map { c -> val p = grid.centres.getValue(c); own.score(grid, p.x + random.nextGaussian() * 9, p.y + random.nextGaussian() * 11) }
                if (round >= 2) measure(isolated) { own.decide(word, taps, -1, false) } else own.decide(word, taps, -1, false)
            }
        }
        out += isolated
        // Cold: the first strip for each of the sentences' words right after loading.
        val coldModel = TypingModel.file(file, "en")
        val cold = Stats("cold first keystrokes (new model)")
        val coldWord = TypingWord(SpatialDecoder(coldModel), true)
        for (sentence in sentences.take(6)) {
            coldWord.reset("")
            for (c in sentence) {
                if (c == ' ') { coldWord.finish(' ', space, grid); coldWord.reset(""); continue }
                val p = grid.centres.getValue(c)
                measure(cold) { coldWord.letter(c.toString(), p.x, p.y, grid); coldWord.strip(false) }
            }
        }
        out += cold
        println("=== KEYBOARD BENCH ===")
        for (s in out) println(s.report())
        println("=== END ===")
    }
}
