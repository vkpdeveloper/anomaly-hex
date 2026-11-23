package ly.anoma.hex.typing

/** The word you write after two words, counted ("see you" then "soon"), as [LearnedWords] counts one
 * word after another. Lookups go straight from the two words to what followed them, so a prediction
 * costs a map read however much is kept; the least used triples go first once [MAX_TRIPLES] is
 * passed. Not thread-safe: [LearnedWords] guards it. */
internal class PersonalTrigrams {
    // "first second" -> the words that followed those two, and how often.
    private val after = HashMap<String, HashMap<String, Int>>()
    /** How many triples are kept. */
    var size = 0
        private set

    fun learn(first: String, second: String, word: String, uses: Int = 1) {
        val followers = after.getOrPut(context(first, second)) { HashMap(4) }
        if (followers.merge(word, uses, Int::plus) == uses) size++
        if (size > MAX_TRIPLES) trim()
    }

    /** Takes back one use of [word] after [first] then [second]. */
    fun unlearn(first: String, second: String, word: String) {
        val k = context(first, second)
        val followers = after[k] ?: return
        val n = followers[word] ?: return
        if (n > 1) { followers[word] = n - 1; return }
        followers.remove(word); size--
        if (followers.isEmpty()) after.remove(k)
    }

    /** What most often followed [first] then [second], used at least [min] times and starting with
     * [prefix], most used first (at most [limit]; allocation only for what it returns). */
    fun next(first: String, second: String, prefix: String = "", limit: Int = 3, min: Int = LearnedWords.WORD_USES): List<String> {
        val followers = after[context(first, second)] ?: return emptyList()
        val best = ArrayList<Map.Entry<String, Int>>(limit + 1)
        for (e in followers.entries) {
            if (e.value < min || !e.key.startsWith(prefix) || e.key.length == prefix.length && prefix.isNotEmpty()) continue
            var at = best.size
            while (at > 0 && best[at - 1].value < e.value) at--
            if (at < limit) { best.add(at, e); if (best.size > limit) best.removeAt(limit) }
        }
        return best.map { it.key }
    }

    fun clear() { after.clear(); size = 0 }

    /** Drops every triple [word] (lowercase) is part of. */
    fun forget(word: String) {
        val it = after.entries.iterator()
        while (it.hasNext()) {
            val (k, followers) = it.next()
            // The context's first or second word, compared in place: this visits every triple kept.
            val space = k.indexOf(' ')
            if (space == word.length && k.startsWith(word) || k.length - space - 1 == word.length && k.endsWith(word)) {
                size -= followers.size; it.remove(); continue
            }
            if (followers.remove(word) != null) size--
            if (followers.isEmpty()) it.remove()
        }
    }

    /** `t first second word uses` per triple, one per line, to append to [LearnedWords.write]. */
    fun write(out: StringBuilder) {
        for ((k, followers) in after) {
            val space = k.indexOf(' ')
            for ((word, n) in followers) {
                out.append("t\t").append(k, 0, space).append('\t').append(k, space + 1, k.length).append('\t').append(word).append('\t').append(n).append('\n')
            }
        }
    }

    /** One line of what [write] wrote, added to what's here; false when [f] isn't a triple line. */
    fun read(f: List<String>): Boolean {
        if (f.size != 5 || f[0] != "t") return false
        read(f[1], f[2], f[3], f[4])
        return true
    }

    /** The fields of a triple line, [count] as written; one that isn't a positive number is skipped. */
    fun read(first: String, second: String, word: String, count: String) {
        val n = count.toIntOrNull()?.takeIf { it > 0 } ?: return
        learn(first, second, word, n)
    }

    // The singles go first, then the least used of the rest, down to nine tenths of the cap.
    private fun trim() {
        val counts = IntArray(MAX_COUNT + 1)
        for (followers in after.values) for (n in followers.values) counts[minOf(n, MAX_COUNT)]++
        val keep = MAX_TRIPLES * 9 / 10
        var floor = 1; var kept = size
        while (floor < MAX_COUNT && kept > keep) { kept -= counts[floor]; floor++ }
        val it = after.values.iterator()
        while (it.hasNext()) {
            val followers = it.next()
            val before = followers.size
            followers.values.removeAll { n -> n < floor }
            size -= before - followers.size
            if (followers.isEmpty()) it.remove()
        }
    }

    private companion object {
        const val MAX_TRIPLES = 60_000
        const val MAX_COUNT = 64
        fun context(first: String, second: String) = "$first $second"
    }
}
