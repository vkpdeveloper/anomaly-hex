package ly.anoma.hex.typing

/** The word you write after two words, counted ("see you" then "soon"), as [LearnedWords] counts one
 * word after another, each use fading with time ([Fade]). Lookups go straight from the two words to what
 * followed them, so a prediction or a score costs map reads however much is kept; the lightest triples go
 * first once [MAX_TRIPLES] is passed. Not thread-safe: [LearnedWords] guards it. */
internal class PersonalTrigrams {
    // first -> second -> the words that followed those two.
    private val after = HashMap<String, HashMap<String, Follows>>()
    /** How many triples are kept. */
    var size = 0
        private set

    fun learn(first: String, second: String, word: String, uses: Int = 1, weight: Double = 1.0) {
        val followers = after.getOrPut(first) { HashMap(4) }.getOrPut(second) { Follows() }
        if (followers.add(word, uses, weight)) size++
        if (size > MAX_TRIPLES) trim()
    }

    /** Takes back one use, worth [weight], of [word] after [first] then [second]. */
    fun unlearn(first: String, second: String, word: String, weight: Double) {
        val seconds = after[first] ?: return
        val followers = seconds[second] ?: return
        if (followers.take(word, weight)) size--
        if (followers.map.isEmpty()) { seconds.remove(second); if (seconds.isEmpty()) after.remove(first) }
    }

    /** What followed [first] then [second], or null. */
    fun follows(first: String, second: String): Follows? = after[first]?.get(second)

    /** What most heavily followed [first] then [second], used at least [min] times and still worth
     * [live] fresh uses ([real] reads a weight that way), starting with [prefix], heaviest first (at most
     * [limit]; allocation only for what it returns). */
    fun next(first: String, second: String, prefix: String = "", limit: Int = 3, min: Int = LearnedWords.WORD_USES, live: Double = 0.0,
             real: (Double) -> Double = { it }): List<String> {
        val followers = follows(first, second) ?: return emptyList()
        val best = ArrayList<Map.Entry<String, Use>>(limit + 1)
        for (e in followers.map.entries) {
            val use = e.value
            if (use.n < min || real(use.w) < live || !e.key.startsWith(prefix) || e.key.length == prefix.length && prefix.isNotEmpty()) continue
            var at = best.size
            while (at > 0 && LearnedWords.lighter(best[at - 1].value.w, use.w)) at--
            if (at < limit) { best.add(at, e); if (best.size > limit) best.removeAt(limit) }
        }
        return best.map { it.key }
    }

    fun clear() { after.clear(); size = 0 }

    /** Drops every triple [word] (lowercase) is part of. */
    fun forget(word: String) {
        after.remove(word)?.values?.forEach { size -= it.map.size }
        val outer = after.values.iterator()
        while (outer.hasNext()) {
            val seconds = outer.next()
            seconds.remove(word)?.let { size -= it.map.size }
            val inner = seconds.values.iterator()
            while (inner.hasNext()) {
                val followers = inner.next()
                if (followers.remove(word)) size--
                if (followers.map.isEmpty()) inner.remove()
            }
            if (seconds.isEmpty()) outer.remove()
        }
    }

    /** Brings every weight to a new scale. */
    fun scale(factor: Double) { for (seconds in after.values) for (f in seconds.values) f.scale(factor) }

    /** `t first second word uses weight` per triple, one per line, to append to [LearnedWords.write], the
     * weight in hundredths of a fresh use as [real] reads it; one that has faded away isn't written. */
    fun write(out: StringBuilder, real: (Double) -> Double = { it }) {
        for ((first, seconds) in after) for ((second, followers) in seconds) for ((word, use) in followers.map) {
            val hundredths = Math.round(real(use.w) * 100)
            if (hundredths <= 0) continue
            out.append("t\t").append(first).append('\t').append(second).append('\t').append(word).append('\t')
                .append(use.n).append('\t').append(hundredths).append('\n')
        }
    }

    // The faintest go first, down to nine tenths of the cap.
    private fun trim() {
        val weights = DoubleArray(size); var i = 0
        for (seconds in after.values) for (f in seconds.values) for (u in f.map.values) weights[i++] = u.w
        weights.sort()
        val floor = weights[size - MAX_TRIPLES * 9 / 10 - 1]
        val outer = after.values.iterator()
        size = 0
        while (outer.hasNext()) {
            val seconds = outer.next()
            val inner = seconds.values.iterator()
            while (inner.hasNext()) {
                val followers = inner.next()
                followers.dropUpTo(floor)
                if (followers.map.isEmpty()) inner.remove() else size += followers.map.size
            }
            if (seconds.isEmpty()) outer.remove()
        }
    }

    private companion object {
        const val MAX_TRIPLES = 60_000
    }
}
