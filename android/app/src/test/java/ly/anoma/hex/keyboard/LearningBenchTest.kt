package ly.anoma.hex.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import java.util.Random
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.Glides
import ly.anoma.hex.typing.KeyGrid
import ly.anoma.hex.typing.KeyPoint
import ly.anoma.hex.typing.KeyRect
import ly.anoma.hex.typing.LearnedWords
import ly.anoma.hex.typing.PersonalWords
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences
import ly.anoma.hex.typing.TypingWord
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * How well the keyboard learns a person's writing. Skipped unless the environment has HEX_LEARN_BENCH=1:
 * `HEX_LEARN_BENCH=1 ./gradlew :app:testDebugUnitTest --tests '*LearningBenchTest*' -i` (see android/README.md).
 *
 * Each author of the public Enron sent mail (`android/scripts/learn-corpus.py` cleans it into
 * `$TMPDIR/hex-learn-bench/authors`) is typed by a seeded simulated thumb through the real [KeyboardEngine] and
 * English model: the first 80% of their messages teach a warm keyboard, then the rest is typed on that keyboard
 * and on one that learns nothing (a field that asks for no personalised learning). The thumb taps each key's
 * centre plus Gaussian noise plus the author's fixed grip offset, reads the strip before every key (taps the
 * intended word or phrase when it is offered), ends words with space or their mark, and takes back a
 * correction that broke a word it typed right with one delete. Taps only: no glides, no other backspacing.
 *
 * The engine calls it makes, which a change to the engine keeps working: [KeyboardEngine.startInput],
 * `opened`, `configureTyping`, `letter` (after `likelierLetter`), `space`, `type` (marks, apostrophes),
 * `pickSuggestion`, `delete`, `shiftDown`/`shiftUp`, and the strip it reads from [KeyboardSurface.suggestions].
 */
class LearningBenchTest {
    private val seed = env("HEX_LEARN_BENCH_SEED")?.toLong() ?: 20260101L
    private val words = env("HEX_LEARN_BENCH_WORDS")?.toInt() ?: 30_000
    private val sdX = env("HEX_LEARN_BENCH_SD")?.toDouble() ?: 0.22
    private val label = env("HEX_LEARN_BENCH_LABEL") ?: "run"
    /** HEX_LEARN_BENCH_DEBUG=1 lists every word that did not come out as intended. */
    private val debug = env("HEX_LEARN_BENCH_DEBUG") == "1"

    @Test fun bench() {
        assumeTrue("set HEX_LEARN_BENCH=1 to run", env("HEX_LEARN_BENCH") == "1")
        val root = File(env("HEX_LEARN_BENCH_DIR") ?: File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"), "hex-learn-bench").path)
        val samples = authors(root)
        assumeTrue("no writing samples: run android/scripts/learn-corpus.py --out $root (needs network, python3)", samples.isNotEmpty())
        val limit = env("HEX_LEARN_BENCH_AUTHORS")?.toInt() ?: samples.size
        val results = LinkedHashMap<String, Pair<Tally, Tally>>()
        val started = System.nanoTime()
        for ((index, sample) in samples.take(limit).withIndex()) {
            val (learn, test) = split(sample.second)
            val warm = Typist(index, learns = true)
            val cold = Typist(index, learns = false)
            warm.type(learn, Tally(), baseId = 0)
            val w = Tally().also { it.timing = true }; val c = Tally().also { it.timing = true }
            warm.type(test, w, baseId = learn.size)
            cold.type(test, c, baseId = learn.size)
            results[sample.first] = w to c
            println("%-12s learn %5d words, test %5d words: savings %5.1f%% (cold %5.1f%%) in %ds".format(sample.first,
                learn.sumOf { it.count { ch -> ch == ' ' } + 1 }, w.words, w.savings(), c.savings(), (System.nanoTime() - started) / 1_000_000_000))
        }
        val overall = Tally().also { all -> for ((w, _) in results.values) all += w }
        val overallCold = Tally().also { all -> for ((_, c) in results.values) all += c }
        val rows = LinkedHashMap<String, Map<String, Double>>()
        for ((name, pair) in results) rows[name] = metrics(pair.first, pair.second)
        rows["overall"] = metrics(overall, overallCold)
        print(table(rows, overall))
        val out = File(root, "$label.json")
        out.writeText(json(rows))
        println("written ${out.path} (${(System.nanoTime() - started) / 1_000_000_000}s)")
        env("HEX_LEARN_BENCH_BASE")?.let { print(compare(File(it).readText(), rows)) }
    }

    // --- the data

    private fun authors(root: File): List<Pair<String, List<String>>> {
        val dir = File(root, "authors")
        if (dir.listFiles { f -> f.name.endsWith(".txt") }.isNullOrEmpty()) {
            val script = File("../scripts/learn-corpus.py")
            if (script.exists()) runCatching {
                ProcessBuilder("python3", "-I", script.absolutePath, "--out", root.path).inheritIO().start().waitFor()
            }
        }
        return dir.listFiles { f -> f.name.endsWith(".txt") }.orEmpty().sortedBy { it.name }
            .map { it.name.removeSuffix(".txt") to it.readLines().filter { l -> l.isNotBlank() } }
    }

    /** Messages in time order up to [words] words: the first 80% teach, the rest are typed. */
    private fun split(messages: List<String>): Pair<List<String>, List<String>> {
        val taken = ArrayList<String>(); var n = 0
        for (m in messages) { if (n >= words) break; taken += m; n += m.count { it == ' ' } + 1 }
        val all = n
        var seen = 0
        val cut = taken.indexOfFirst { m -> seen += m.count { it == ' ' } + 1; seen >= all * 0.8 } + 1
        return taken.subList(0, cut) to taken.subList(cut, taken.size)
    }

    // --- the simulated typist on a real engine

    private class Tally {
        var timing = false
        var chars = 0L; var keys = 0L; var words = 0L; var exact = 0L; var exactCase = 0L
        var broke = 0L; var fixed = 0L; var undone = 0L
        var taps = 0L; var rawMiss = 0L; var readMiss = 0L
        var nextWords = 0L; var next1 = 0L; var nextAny = 0L
        var picksNext = 0L; var picksComplete = 0L
        var nanos = LongArray(1 shl 16); var n = 0
        fun time(ns: Long) { if (n == nanos.size) nanos = nanos.copyOf(n * 2); nanos[n++] = ns }
        operator fun plusAssign(o: Tally) {
            chars += o.chars; keys += o.keys; words += o.words; exact += o.exact; exactCase += o.exactCase
            broke += o.broke; fixed += o.fixed; undone += o.undone; taps += o.taps; rawMiss += o.rawMiss; readMiss += o.readMiss
            nextWords += o.nextWords; next1 += o.next1; nextAny += o.nextAny; picksNext += o.picksNext; picksComplete += o.picksComplete
            for (i in 0 until o.n) time(o.nanos[i])
        }
        fun savings() = if (chars == 0L) 0.0 else 100.0 * (1 - keys.toDouble() / chars)
        fun micros(q: Double): Double {
            if (n == 0) return 0.0
            val s = nanos.copyOf(n).also { it.sort() }
            return if (q < 0) s.average() / 1000.0 else s[minOf(n - 1, (q * n).toInt())] / 1000.0
        }
    }

    private class BenchField : KeyboardTextTarget {
        var text = ""
        private var caret = 0
        fun reset() { text = ""; caret = 0 }
        override val connected = true
        override fun before(length: Int) = text.substring(maxOf(0, caret - length), caret)
        override fun after(length: Int) = text.substring(caret, minOf(text.length, caret + length))
        override fun selected() = ""
        override fun capsMode(mask: Int) = if (TypingWord.startsSentence(before(caret))) mask else 0
        override fun commit(text: String): Boolean { this.text = this.text.substring(0, caret) + text + this.text.substring(caret); caret += text.length; return true }
        override fun deleteBefore(length: Int): Boolean { val n = minOf(length, caret); text = text.removeRange(caret - n, caret); caret -= n; return true }
        override fun deleteCodePointsBefore(count: Int) = deleteBefore(count)
        override fun deleteAround(before: Int, after: Int): Boolean {
            val a = minOf(after, text.length - caret); text = text.removeRange(caret, caret + a); return deleteBefore(before)
        }
        override fun beginBatch() {}
        override fun endBatch() {}
        override fun setSelection(start: Int, end: Int): Boolean { caret = start; return true }
        override fun performEditorAction(id: Int) = false
        override fun defaultEditorAction() = false
        override fun sendKey(code: Int) {}
        override fun all() = FieldState(text, caret, caret)
    }

    private inner class Typist(private val author: Int, learns: Boolean) {
        private val field = BenchField()
        private val base: KeyGrid = Glides.grid()
        private val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
        private val prefs = TypingPreferences(Prefs.context()).also { it.autoCapitalize = true }
        private var strip = emptyList<Suggestion>()
        private var now = 0L
        // The keyboard rebuilds its personal words on a worker thread: here they are queued and finished between
        // keys, outside the timing, so the next key sees them as it would a moment later on the phone.
        private val background = ArrayDeque<Runnable>()
        private val engine = KeyboardEngine(field, prefs, { Spoken.English }, { SpatialDecoder(model) },
            readPersonal = { m, mine -> PersonalWords(m, mine, emptyMap()) }, learned = LearnedWords(), background = { background.add(it) },
            clock = { now })
        private val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = if (learns) 0 else EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            initialSelStart = 0; initialSelEnd = 0
        }
        // The grip habit: a fixed miss of 0.15 to 0.25 key pitches in a seeded direction.
        private val grip = Random(seed * 31 + author).let { r ->
            val angle = r.nextDouble() * 2 * Math.PI; val length = 0.15 + 0.1 * r.nextDouble()
            KeyPoint(Math.cos(angle) * length, Math.sin(angle) * length)
        }
        private val spaceBar = base.space!!
        private val density = 3.5
        private val face = base.centres.mapValues { (_, c) ->
            KeyRect(c.x - (base.pitchX - 16) / 2, c.y - 142 / 2.0, c.x + (base.pitchX - 16) / 2, c.y + 142 / 2.0)
        }
        private val areas = base.centres.mapValues { (_, c) ->
            KeyRect(c.x - base.pitchX / 2, c.y - base.pitchY / 2, c.x + base.pitchX / 2, c.y + base.pitchY / 2)
        }

        init {
            engine.surface = object : KeyboardSurface {
                override fun showPage(page: KeyboardPage) {}
                override fun suggestions(slots: List<Suggestion>) { strip = slots }
                override fun undo(offer: UndoOffer?) {}
            }
        }

        fun type(messages: List<String>, t: Tally, baseId: Int) {
            for ((i, message) in messages.withIndex()) {
                field.reset(); strip = emptyList()
                engine.startInput(info); engine.opened(); engine.configureTyping()
                while (background.isNotEmpty()) background.removeFirst().run()
                val tokens = message.split(' ')
                t.chars += message.length
                var at = 0
                while (at < tokens.size) at += word(tokens, at, baseId + i, t)
            }
        }

        private inline fun key(t: Tally, block: () -> Unit) {
            now += 400; t.keys++
            if (t.timing) { val s = System.nanoTime(); block(); t.time(System.nanoTime() - s) } else block()
            while (background.isNotEmpty()) background.removeFirst().run()
        }

        private fun bare(token: String) = token.trimEnd(',', '.', '?', '!')

        /** Types the word at [at] (or the phrase the strip offers there); returns how many tokens it used. */
        private fun word(tokens: List<String>, at: Int, id: Int, t: Tally): Int {
            val start = field.text.length
            val shown = strip.filter { it.kind != Suggestion.Kind.Emoji }
            val intended = bare(tokens[at])
            t.nextWords++
            if (shown.firstOrNull()?.text.equals(intended, ignoreCase = true)) t.next1++
            if (shown.any { it.text.equals(intended, ignoreCase = true) }) t.nextAny++
            val phrase = shown.firstNotNullOfOrNull { s ->
                val parts = s.text.split(' ')
                if (at + parts.size <= tokens.size && parts.indices.all { j -> bare(tokens[at + j]) == parts[j] && (j == parts.size - 1 || tokens[at + j] == parts[j]) }) s.text to parts.size else null
            }
            if (phrase != null) {
                key(t) { engine.pickSuggestion(phrase.first) }; t.picksNext++
                return picked(tokens, at, phrase.second, start, t)
            }
            val marks = tokens[at].substring(intended.length)
            val more = at + 1 < tokens.size
            for ((j, ch) in intended.withIndex()) {
                if (j > 0 && intended.length - j >= (if (marks.isEmpty()) 1 else 2) &&
                    strip.any { it.kind != Suggestion.Kind.Typed && it.kind != Suggestion.Kind.Emoji && it.text == intended }) {
                    key(t) { engine.pickSuggestion(intended) }; t.picksComplete++
                    return picked(tokens, at, 1, start, t)
                }
                letter(ch, id, at, j, t)
            }
            val typed = field.text.substring(start)
            if (marks.isEmpty()) {
                space(t)
                var committed = field.text.substring(start).trimEnd()
                if (!committed.equals(intended, true) && typed.equals(intended, true)) {
                    t.broke++
                    // One delete takes the correction back; the word stays as typed, so space ends it again.
                    key(t) { engine.delete() }; space(t); t.undone++
                    committed = field.text.substring(start).trimEnd()
                } else if (committed.equals(intended, true) && !typed.equals(intended, true)) t.fixed++
                score(tokens[at], committed, t)
            } else {
                for (m in marks) key(t) { engine.type(m.toString()) }
                val committed = field.text.substring(start).trim()
                if (!committed.equals(tokens[at], true) && typed.equals(intended, true)) t.broke++
                else if (committed.equals(tokens[at], true) && !typed.equals(intended, true)) t.fixed++
                if (more) space(t)
                score(tokens[at], committed, t)
            }
            return 1
        }

        /** A pick put [n] tokens in with a space; marks that belong after them take its place. */
        private fun picked(tokens: List<String>, at: Int, n: Int, start: Int, t: Tally): Int {
            val last = tokens[at + n - 1]
            val marks = last.substring(bare(last).length)
            for (m in marks) key(t) { engine.type(m.toString()) }
            if (marks.isNotEmpty() && at + n < tokens.size) space(t)
            val got = field.text.substring(start).trim().split(' ')
            for (j in 0 until n) score(tokens[at + j], got.takeIf { it.size == n }?.get(j), t)
            return n
        }

        private fun score(wanted: String, got: String?, t: Tally) {
            t.words++
            if (debug && got != wanted) println("  miss: wanted \"$wanted\" got \"$got\"")
            if (got != null && got.equals(wanted, true)) t.exact++
            if (got == wanted) t.exactCase++
        }

        private fun space(t: Tally) {
            val noise = Random(mix(seed, author.toLong(), now))
            val p = KeyPoint(spaceBar.let { (it.left + it.right) / 2 } + noise.nextGaussian() * 0.3 * base.pitchX, spaceBar.centreY + noise.nextGaussian() * 0.25 * base.pitchY)
            key(t) { engine.space(p, engine.touch.personalised(base)) }
        }

        private fun letter(ch: Char, id: Int, at: Int, j: Int, t: Tally) {
            val lower = ch.lowercaseChar()
            val centre = base.centres[lower]
            if (centre == null) { key(t) { engine.type(ch.toString()) }; return }
            val upper = ch.isUpperCase()
            if (engine.uppercase != upper) key(t) { engine.shiftDown(); engine.shiftUp() }
            val noise = Random(mix(seed, author.toLong(), id.toLong() * 1_000_003 + at * 1009L + j))
            val x = centre.x + (grip.x + noise.nextGaussian() * sdX) * base.pitchX
            val y = centre.y + (grip.y + noise.nextGaussian() * sdX * 0.9) * base.pitchY
            val hit = base.centres.minBy { (_, c) -> Math.hypot((c.x - x) / base.pitchX, (c.y - y) / base.pitchY) }.key
            t.taps++; if (hit != lower) t.rawMiss++
            val grid = engine.touch.personalised(base)
            val near = nearby(hit, x, y, upper)
            var read = hit
            key(t) {
                if (near.isNotEmpty()) read = engine.likelierLetter(if (upper) hit.uppercaseChar() else hit, near, base.centres.keys).lowercaseChar()
                engine.letter(if (upper) read.uppercase() else read.toString(), x, y, grid)
            }
            if (read != lower) t.readMiss++
        }

        /** The neighbours the view offers [KeyboardEngine.likelierLetter] for a touch that fell in the gap beside its
         * keycap, within [TypingWord.REACH] dp (here 3.5 px to the dp), as `KeyPlaneView.likelier` finds them: none
         * for a touch on the keycap, judged where the touch is once your habitual miss on the key is taken off. */
        private fun nearby(hit: Char, x: Double, y: Double, upper: Boolean): Map<Char, Double> {
            val miss = engine.touch.offset(hit)
            val aimed = KeyPoint(x - miss.x * base.pitchX, y - miss.y * base.pitchY)
            val own = face.getValue(hit)
            if (aimed.x in own.left..own.right && aimed.y in own.top..own.bottom) return emptyMap()
            val others = areas.filterKeys { it != hit }.mapKeys { if (upper) it.key.uppercaseChar() else it.key }
            return nearKeys(aimed, own, others, TypingWord.REACH * density).mapValues { it.value / density }
        }
    }

    // --- the report

    private fun mix(a: Long, b: Long, c: Long): Long {
        var h = a * -7046029254386353131L + b
        h = (h xor (h ushr 30)) * -4658895280553007687L + c
        h = (h xor (h ushr 27)) * -7723592293110705685L
        return h xor (h ushr 31)
    }

    private fun pct(a: Long, b: Long) = if (b == 0L) 0.0 else 100.0 * a / b

    private fun metrics(w: Tally, c: Tally): Map<String, Double> = linkedMapOf(
        "words" to w.words.toDouble(),
        "savings" to w.savings(), "savingsCold" to c.savings(), "savingsGain" to w.savings() - c.savings(),
        "accuracy" to pct(w.exact, w.words), "accuracyCold" to pct(c.exact, c.words),
        "accuracyGain" to pct(w.exact, w.words) - pct(c.exact, c.words),
        "accuracyCase" to pct(w.exactCase, w.words), "accuracyCaseCold" to pct(c.exactCase, c.words),
        "brokeIt" to pct(w.broke, w.words), "brokeItCold" to pct(c.broke, c.words),
        "fixedIt" to pct(w.fixed, w.words), "fixedItCold" to pct(c.fixed, c.words),
        "nextFirst" to pct(w.next1, w.nextWords), "nextFirstCold" to pct(c.next1, c.nextWords),
        "nextAny" to pct(w.nextAny, w.nextWords), "nextAnyCold" to pct(c.nextAny, c.nextWords),
        "pickedNext" to pct(w.picksNext, w.words), "pickedCompletion" to pct(w.picksComplete, w.words),
        "undone" to pct(w.undone, w.words),
        "rawTapErrors" to pct(w.rawMiss, w.taps), "readTapErrors" to pct(w.readMiss, w.taps),
        "usMean" to w.micros(-1.0), "usP99" to w.micros(.99), "usMeanCold" to c.micros(-1.0), "usP99Cold" to c.micros(.99),
    )

    private fun table(rows: Map<String, Map<String, Double>>, overall: Tally): String = buildString {
        val head = "%-12s %6s | %-13s | %-13s | %-11s | %-11s | %-9s | %-11s | %6s | %-15s | %-15s".format("author", "words",
            "keystroke sav", "word accuracy", "ac broke", "ac fixed", "next-word", "picked", "undone", "us per key warm", "us per key cold")
        val sub = "%-12s %6s | %6s %6s | %6s %6s | %5s %5s | %5s %5s | %4s %4s | %5s %5s | %6s | %7s %7s | %7s %7s".format("", "",
            "warm", "cold", "warm", "cold", "warm", "cold", "warm", "cold", "@1", "any", "next", "compl", "%", "mean", "p99", "mean", "p99")
        appendLine(head); appendLine(sub); appendLine("-".repeat(head.length))
        for ((name, m) in rows) {
            if (name == "overall") appendLine("-".repeat(head.length))
            appendLine("%-12s %6.0f | %6.1f %6.1f | %6.1f %6.1f | %5.2f %5.2f | %5.2f %5.2f | %4.1f %4.1f | %5.1f %5.1f | %6.2f | %7.1f %7.1f | %7.1f %7.1f".format(
                name, m["words"], m["savings"], m["savingsCold"], m["accuracy"], m["accuracyCold"], m["brokeIt"], m["brokeItCold"],
                m["fixedIt"], m["fixedItCold"], m["nextFirst"], m["nextAny"], m["pickedNext"], m["pickedCompletion"], m["undone"],
                m["usMean"], m["usP99"], m["usMeanCold"], m["usP99Cold"]))
        }
        appendLine()
        val m = rows.getValue("overall")
        appendLine("overall (warm): savings %.2f%%, personalisation gain +%.2f points of savings, +%.2f points of word accuracy; case-exact accuracy %.2f%% (cold %.2f%%)".format(
            m["savings"], m["savingsGain"], m["accuracyGain"], m["accuracyCase"], m["accuracyCaseCold"]))
        appendLine("raw tap error rate (nearest key is not the intended one): %.2f%%; after the likelier-letter reading: %.2f%%; %d taps, %d keystrokes, %d characters, %d words"
            .format(m["rawTapErrors"], m["readTapErrors"], overall.taps, overall.keys, overall.chars, overall.words))
    }

    private fun json(rows: Map<String, Map<String, Double>>) = buildString {
        appendLine("{\"label\":\"$label\",\"seed\":$seed,\"words\":$words,\"sd\":$sdX,")
        appendLine(rows.entries.joinToString(",\n") { (name, m) ->
            "\"$name\":{" + m.entries.joinToString(",") { "\"${it.key}\":${"%.4f".format(java.util.Locale.ROOT, it.value)}" } + "}"
        })
        appendLine("}")
    }

    /** Deltas of this run against a JSON an earlier run wrote (`HEX_LEARN_BENCH_BASE`), overall and per author. */
    private fun compare(base: String, rows: Map<String, Map<String, Double>>): String = buildString {
        val before = Regex("\"([^\"]+)\":\\{([^{}]*)\\}").findAll(base).associate { m ->
            m.groupValues[1] to Regex("\"(\\w+)\":(-?[0-9.]+)").findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2].toDouble() }
        }
        appendLine(); appendLine("against ${env("HEX_LEARN_BENCH_BASE")}")
        val keys = listOf("savings", "accuracy", "accuracyCase", "brokeIt", "fixedIt", "nextFirst", "nextAny", "savingsGain", "usMean", "usP99")
        appendLine("%-12s ".format("") + keys.joinToString(" ") { "%12s".format(it) })
        for ((name, m) in rows) {
            val b = before[name] ?: continue
            appendLine("%-12s ".format(name) + keys.joinToString(" ") { k -> "%12s".format("%+.2f".format(java.util.Locale.ROOT, ((m[k] ?: 0.0) - (b[k] ?: 0.0)).let { if (Math.abs(it) < 0.005) 0.0 else it })) })
        }
    }

    private fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }
}
