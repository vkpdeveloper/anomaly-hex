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

    /** 2,000 learned words the model may lack plus 500 contact names, as a phone that's been used a year. */
    private fun crowd(model: TypingModel, learnedCount: Int = 2000, names: Int = 500): Pair<PersonalWords, LearnedWords> {
        val random = Random(7)
        val syllables = listOf("an", "ar", "be", "ka", "ri", "sh", "ta", "vi", "mo", "ne", "la", "de", "jo", "ha", "pr", "ya",
            "su", "ni", "ra", "ve", "ko", "zi", "el", "om", "ush", "it", "ay", "ee", "ch", "dh")
        fun made(parts: Int) = buildString { repeat(parts) { append(syllables[random.nextInt(syllables.size)]) } }
        val contacts = LinkedHashSet<String>()
        while (contacts.size < names) contacts.add(made(2 + random.nextInt(3)).replaceFirstChar { it.uppercase() })
        val common = (0 until model.size).filter { model.text(it).all { c -> c in 'a'..'z' } }.sortedBy { model.frequencyCost(it) }
            .take(learnedCount / 2).map { model.text(it) }
        val written = LinkedHashSet<String>(common)
        while (written.size < learnedCount) made(2 + random.nextInt(4)).let { if (model.index(it) < 0) written.add(it) }
        val learned = LearnedWords()
        learned.seed(written.map { it to 2 + random.nextInt(50) }, emptyList())
        val theirs = learned.words().filter { model.index(it.lowercase()) < 0 }
        return PersonalWords(model, theirs + contacts, emptyMap()) to learned
    }

    @Test fun glideBench() {
        assumeTrue(System.getenv("HEX_BENCH") == "1")
        val model = TypingModel.file(file, "en")
        val g = Glides.grid()
        val glide = GlideDecoder(model)
        val (personal, learned) = crowd(model)
        val letters = { w: Int -> model.text(w).all { it in 'a'..'z' } }
        val byCost = (0 until model.size).filter(letters).sortedBy { model.frequencyCost(it) }
        val common = byCost.filter { model.length(it) in 3..7 }.take(150).map { model.text(it) }
        val long = byCost.filter { model.length(it) >= 10 }.take(60).map { model.text(it) } +
            listOf("understanding", "communication", "responsibility", "internationalization")
        val out = ArrayList<Stats>()
        fun case(label: String, words: List<String>, withPersonal: Boolean) {
            val paths = words.flatMap { w -> Glides.variants(w, g).map { it.second } }
            val stats = Stats(label)
            val p = if (withPersonal) personal else null
            val l = if (withPersonal) learned else null
            repeat(3) { for (path in paths) glide.decode(path, g, -1, false, p, l) }
            repeat(2) { for (path in paths) measure(stats) { glide.decode(path, g, -1, false, p, l) } }
            out += stats
        }
        case("glide common words, plain", common, false)
        case("glide long words, plain", long, false)
        case("glide common, 2000+500 personal", common, true)
        case("glide long, 2000+500 personal", long, true)
        println("=== GLIDE BENCH ===")
        for (s in out) println(s.report())
        println("=== END ===")
    }

    /** `learn` over a year's typing, then a full store: 20,000 words, 50,000 pairs, 60,000 triples. */
    @Test fun learnedBench() {
        assumeTrue(System.getenv("HEX_BENCH") == "1")
        val random = Random(5)
        val syllables = listOf("an", "ar", "be", "ka", "ri", "sh", "ta", "vi", "mo", "ne", "la", "de", "jo", "ha", "pr", "ya", "su", "ni", "ra", "ve", "ko", "zi", "el", "om")
        fun made(): String {
            val base = buildString { repeat(2 + random.nextInt(4)) { append(syllables[random.nextInt(syllables.size)]) } }
            return if (random.nextInt(3) == 0) base + random.nextInt(100).toString().map { 'a' + it.digitToInt() }.joinToString("") else base
        }
        val vocab = LinkedHashSet<String>().also { while (it.size < 22_000) it.add(made()) }.toList()
        // A skewed pick so some words repeat and the pairs and triples have real counts.
        fun pick() = vocab[(vocab.size * Math.pow(random.nextDouble(), 2.2)).toInt().coerceAtMost(vocab.size - 1)]
        val out = ArrayList<Stats>()
        val learned = LearnedWords()
        val learn = Stats("LearnedWords.learn (growing to cap)"); val learnFull = Stats("LearnedWords.learn (store full)")
        var a = pick(); var b = pick()
        repeat(260_000) { i ->
            val w = pick()
            if (i < 150_000) learned.learn(w, b, false, a)
            else measure(if (i < 200_000) learn else learnFull) { learned.learn(w, b, false, a) }
            a = b; b = w
        }
        out += listOf(learn, learnFull)
        val counts = Stats("LearnedWords.completions"); val next = Stats("LearnedWords.next(prev)"); val next3 = Stats("LearnedWords.next(a,b,prefix)")
        val addresses = Stats("LearnedWords.addresses"); val learnAddr = Stats("LearnedWords.learnAddress")
        repeat(1000) { learned.learnAddress("user$it@example${it % 40}.com") }
        repeat(3) {
            for (i in 0 until 400) {
                val w = vocab[i * 7 % 3000]
                val prefix = w.take(1 + i % 3)
                measure(counts) { learned.completions(prefix) }
                measure(next) { learned.next(w) }
                measure(next3) { learned.next(vocab[i * 3 % 3000], w, if (i % 2 == 0) "" else w.take(1)) }
                measure(addresses) { learned.addresses("user" + i % 10) }
                measure(learnAddr) { learned.learnAddress("user${i % 1000}@example.com") }
            }
        }
        out += listOf(counts, next, next3, addresses, learnAddr)
        var text = ""
        val write = Stats("LearnedWords.write (full store)"); repeat(5) { measure(write) { text = learned.write() } }
        val read = Stats("LearnedWords.read (full file)"); repeat(5) { measure(read) { LearnedWords().read(text) } }
        out += listOf(write, read)
        val spellStats = Stats("LearnedWords.preferred")
        repeat(2000) { measure(spellStats) { learned.preferred(vocab[it % 5000]) { x, y -> x.length == y.length } } }
        out += spellStats
        println("=== LEARNED BENCH ===")
        println("file: ${text.length / 1024} KB, ${text.count { it == '\n' }} lines, words=${learned.words().size}")
        for (s in out) println(s.report())
        println("=== END ===")
    }

    @Test fun trigramBench() {
        assumeTrue(System.getenv("HEX_BENCH") == "1")
        val random = Random(9)
        val vocab = List(8000) { "w$it" }
        fun pick() = vocab[(vocab.size * Math.pow(random.nextDouble(), 2.0)).toInt().coerceAtMost(vocab.size - 1)]
        val t = PersonalTrigrams()
        val fill = Stats("PersonalTrigrams.learn (fill to cap)"); val over = Stats("PersonalTrigrams.learn (at cap, trims)")
        repeat(400_000) { i ->
            val x = pick(); val y = pick(); val z = pick()
            if (i < 100_000) t.learn(x, y, z) else measure(if (i < 200_000) fill else over) { t.learn(x, y, z) }
        }
        val next = Stats("PersonalTrigrams.next 60k phrases"); val nextPrefix = Stats("PersonalTrigrams.next with prefix")
        repeat(3) {
            for (i in 0 until 3000) {
                val x = vocab[i % 2000]; val y = vocab[(i * 7) % 2000]
                measure(next) { t.next(x, y) }
                measure(nextPrefix) { t.next(x, y, "w1") }
            }
        }
        val forget = Stats("PersonalTrigrams.forget"); repeat(5) { measure(forget) { t.forget("w${7000 + it}") } }
        val sb = StringBuilder(); val write = Stats("PersonalTrigrams.write"); repeat(3) { sb.setLength(0); measure(write) { t.write(sb) } }
        println("=== TRIGRAM BENCH ===")
        for (s in listOf(fill, over, next, nextPrefix, forget, write)) println(s.report())
        println("=== END ===")
    }

    @Test fun touchBench() {
        assumeTrue(System.getenv("HEX_BENCH") == "1")
        val random = Random(13)
        val t = TouchOffsets()
        val words = sentences.flatMap { it.split(' ') }.filter { it.length in 2..14 }
        val learn = Stats("TouchOffsets.learn (one word)"); val aim = Stats("TouchOffsets.personalised(grid)")
        val offset = Stats("TouchOffsets.offset"); val write = Stats("TouchOffsets.write"); val read = Stats("TouchOffsets.read")
        var text = ""
        repeat(8) { round ->
            for (w in words) {
                val touches = w.map { c -> val p = grid.centres.getValue(c); KeyPoint(p.x + random.nextGaussian() * 6 + 4, p.y + random.nextGaussian() * 6) }
                if (round >= 2) measure(learn) { t.learn(w, touches, grid) } else t.learn(w, touches, grid)
                measure(aim) { t.personalised(grid) }
                for (c in w) measure(offset) { t.offset(c) }
            }
        }
        repeat(50) { measure(write) { text = t.write() } }
        repeat(50) { measure(read) { TouchOffsets().read(text) } }
        println("=== TOUCH BENCH ===")
        for (s in listOf(learn, aim, offset, write, read)) println(s.report())
        println("=== END ===")
    }
}
