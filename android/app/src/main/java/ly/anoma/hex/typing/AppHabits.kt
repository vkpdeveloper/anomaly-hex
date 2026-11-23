package ly.anoma.hex.typing

/** How you write in each app, on top of what you write everywhere ([LearnedWords]): chat is not email or a
 * search box. Per app, the words and the word after another, each use fading with time ([Fade]); the
 * [MAX_APPS] apps used last are kept and each is bounded, so the whole stays small. [LearnedWords] reads
 * an app's counts as one more level of its blend, so an app seen little leaves the shared counts as they
 * were. Not thread-safe: [LearnedWords] guards it. */
internal class AppHabits {
    class App(val name: String) {
        val words = HashMap<String, Use>()
        val pairs = HashMap<String, Follows>()
        /** The weight of every word in [words]. */
        var total = 0.0
        var last = 0L
        var pairCount = 0

        fun learn(word: String, previous: String?, weight: Double) {
            words.getOrPut(word) { Use(0, 0.0) }.let { it.n++; it.w += weight }
            total += weight
            if (previous != null && pairs.getOrPut(previous) { Follows() }.add(word, 1, weight)) pairCount++
            if (words.size > MAX_WORDS || pairCount > MAX_PAIRS) trim()
        }

        fun unlearn(word: String, previous: String?, weight: Double) {
            words[word]?.let {
                val taken = minOf(weight, it.w)
                it.n--; it.w -= taken; total -= taken
                if (it.n <= 0 || it.w <= 0) { total -= it.w; words.remove(word) }
            }
            val follows = pairs[previous ?: return] ?: return
            if (follows.take(word, weight)) { pairCount--; if (follows.map.isEmpty()) pairs.remove(previous) }
        }

        fun forget(word: String) {
            words.remove(word)?.let { total -= it.w }
            pairs.remove(word)?.let { pairCount -= it.map.size }
            val it = pairs.values.iterator()
            while (it.hasNext()) { val f = it.next(); if (f.remove(word)) pairCount--; if (f.map.isEmpty()) it.remove() }
        }

        fun scale(factor: Double) {
            for (u in words.values) u.w *= factor
            total *= factor
            for (f in pairs.values) f.scale(factor)
        }

        // The faintest go first, down to nine tenths of each bound.
        private fun trim() {
            if (words.size > MAX_WORDS) {
                val weights = words.values.map { it.w }.sorted()
                val floor = weights[words.size - MAX_WORDS * 9 / 10 - 1]
                words.values.removeAll { it.w <= floor }
                total = words.values.sumOf { it.w }
                pairs.keys.removeAll { it !in words }
                for (f in pairs.values) f.map.keys.removeAll { it !in words }
                recount()
            }
            if (pairCount > MAX_PAIRS) {
                val weights = pairs.values.flatMap { f -> f.map.values.map { it.w } }.sorted()
                val floor = weights[pairCount - MAX_PAIRS * 9 / 10 - 1]
                for (f in pairs.values) f.dropUpTo(floor)
                pairs.values.removeAll { it.map.isEmpty() }
                recount()
            }
        }
        private fun recount() { pairCount = pairs.values.sumOf { it.map.size } }
    }

    private val apps = HashMap<String, App>()

    /** The counts of [name], or null for an app never written in. */
    fun of(name: String?): App? = name?.let { apps[it] }

    /** [word] after [previous] went into [name]'s text field at [now], worth [weight]. */
    fun learn(name: String, word: String, previous: String?, weight: Double, now: Long) {
        val app = apps.getOrPut(name) { App(name) }
        app.last = now
        app.learn(word, previous, weight)
        if (apps.size > MAX_APPS) apps.remove(apps.values.minBy { it.last }.name)
    }

    fun unlearn(name: String, word: String, previous: String?, weight: Double) { apps[name]?.unlearn(word, previous, weight) }

    fun forget(word: String) { for (app in apps.values) app.forget(word) }

    fun clear() = apps.clear()

    fun scale(factor: Double) { for (app in apps.values) app.scale(factor) }

    /** `a app lastUsed`, `A app word weight` and `B app first second weight` lines, weights in hundredths of
     * a fresh use as [real] reads them. */
    fun write(out: StringBuilder, real: (Double) -> Double) {
        for (app in apps.values) {
            out.append("a\t").append(app.name).append('\t').append(app.last).append('\n')
            for ((word, use) in app.words) {
                val hundredths = Math.round(real(use.w) * 100)
                if (hundredths > 0) out.append("A\t").append(app.name).append('\t').append(word).append('\t').append(hundredths).append('\n')
            }
            for ((first, follows) in app.pairs) for ((second, use) in follows.map) {
                val hundredths = Math.round(real(use.w) * 100)
                if (hundredths > 0) out.append("B\t").append(app.name).append('\t').append(first).append('\t').append(second).append('\t').append(hundredths).append('\n')
            }
        }
    }

    fun readApp(name: String, last: Long) {
        val app = apps.getOrPut(name) { App(name) }
        app.last = maxOf(app.last, last)
        if (apps.size > MAX_APPS) apps.remove(apps.values.minBy { it.last }.name)
    }
    fun readWord(name: String, word: String, weight: Double) {
        val app = apps[name] ?: return
        app.words.getOrPut(word) { Use(0, 0.0) }.let { it.n++; it.w += weight }
        app.total += weight
    }
    fun readPair(name: String, first: String, second: String, weight: Double) {
        val app = apps[name] ?: return
        if (app.pairs.getOrPut(first) { Follows() }.add(second, 1, weight)) app.pairCount++
    }

    companion object {
        const val MAX_APPS = 8
        const val MAX_WORDS = 2_000
        const val MAX_PAIRS = 5_000
    }
}
