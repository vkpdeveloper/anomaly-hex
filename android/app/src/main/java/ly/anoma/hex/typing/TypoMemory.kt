package ly.anoma.hex.typing

/** Your own recurring slips: the letters you typed, and the word you ended with after fixing them
 * yourself (picking it from the strip, retyping the word, or keeping an autocorrection). A slip made
 * often ("teh" -> "the") is then corrected with confidence, even where the decoder alone is unsure
 * ([SpatialDecoder.decide]; only words the dictionary and your own words don't have are corrected).
 *
 * Each typed spelling keeps one word and a weight: a fix you made yourself counts [OWN], an
 * autocorrection you let stand 1, and it is trusted from [SURE]. A different word for the same slip
 * wears the old one down before it takes over. Only [slip]s are kept (a letter off, swapped, missing or
 * extra), never a completion or another word. Saved one slip per line; capped; thread-safe.
 *
 * An autocorrection you let stand is the keyboard's own guess, not yours, so alone it is only trusted
 * where the decoder offers no fix ([Habit.own] false); a fix you made yourself overrules the decoder. */
internal class TypoMemory {
    private class Entry(var word: String, var weight: Int, var own: Boolean)
    /** What a slip has become for you: [own] when you fixed it yourself at least once. */
    class Habit(val word: String, val own: Boolean)
    // Oldest first: an entry that is learned again moves to the end, and the oldest goes at the cap.
    private val entries = LinkedHashMap<String, Entry>()

    /** The word [typed] (lowercase) has become for you, once that is sure; else null. */
    @Synchronized fun fix(typed: String): Habit? = entries[typed]?.takeIf { it.weight >= SURE }?.let { Habit(it.word, it.own) }

    /** [typed] was ended as [word] with this [weight] of evidence ([OWN] or 1). False for anything that isn't a [slip]. */
    @Synchronized fun learn(typed: String, word: String, weight: Int): Boolean {
        val key = typed.lowercase()
        val spelled = word
        if (!slip(key, spelled.lowercase())) return false
        val known = entries.remove(key)
        entries[key] = when {
            known == null -> Entry(spelled, weight, weight >= OWN)
            known.word == spelled -> Entry(spelled, minOf(CAP, known.weight + weight), known.own || weight >= OWN)
            known.weight > weight -> Entry(known.word, known.weight - weight, known.own)
            else -> Entry(spelled, weight, weight >= OWN)
        }
        if (entries.size > MAX) entries.remove(entries.keys.first())
        return true
    }

    /** Takes back what [learn] added for [typed] -> [word], as an autocorrection undone at once. */
    @Synchronized fun unlearn(typed: String, word: String, weight: Int) {
        val key = typed.lowercase()
        val known = entries[key]?.takeIf { it.word.equals(word, ignoreCase = true) } ?: return
        known.weight -= weight
        if (known.weight <= 0) entries.remove(key)
    }

    /** [typed] -> [word] was undone: that swap is gone, whatever else [typed] or [word] mean. */
    @Synchronized fun remove(typed: String, word: String) {
        val key = typed.lowercase()
        if (entries[key]?.word.equals(word, ignoreCase = true)) entries.remove(key)
    }

    /** [typed] is a spelling you keep: nothing corrects it. */
    @Synchronized fun forget(typed: String) { entries.remove(typed.lowercase()) }
    @Synchronized fun clear() = entries.clear()
    @Synchronized fun size() = entries.size

    @Synchronized fun write(): String = buildString {
        append(HEADER).append('\n')
        for ((typed, e) in entries) append(typed).append('\t').append(e.word).append('\t').append(e.weight).append('\t').append(if (e.own) 1 else 0).append('\n')
    }

    /** What [write] wrote, in place of what is here. Anything that isn't a slip is skipped. */
    @Synchronized fun read(text: String) {
        val lines = text.lineSequence().iterator()
        if (!lines.hasNext() || lines.next() != HEADER) return
        entries.clear()
        for (line in lines) {
            val f = line.split('\t')
            val weight = f.getOrNull(2)?.toIntOrNull() ?: continue
            if (f.size == 4 && weight > 0 && slip(f[0], f[1].lowercase())) entries[f[0]] = Entry(f[1], minOf(weight, CAP), f[3] == "1")
        }
        while (entries.size > MAX) entries.remove(entries.keys.first())
    }

    companion object {
        const val FILE = "typo-memory.tsv"
        private const val HEADER = "hex-typos 1"
        /** What a fix you made yourself weighs; an autocorrection you kept weighs 1. */
        const val OWN = 2
        /** The weight a slip is corrected from. */
        const val SURE = 2
        private const val CAP = 8
        private const val MAX = 1_000

        /** Whether [word] is [typed] with a slip: lowercase, [typed] three letters or more and no word's
         * start, within one edit (two from six letters), a swap of neighbours counting as one. */
        fun slip(typed: String, word: String): Boolean {
            if (typed.length < 3 || typed == word || word.startsWith(typed) || !typed.all { it.isLetter() } ||
                ' ' in word || word.isEmpty()) return false
            val limit = if (maxOf(typed.length, word.length) >= 6) 2 else 1
            if (kotlin.math.abs(typed.length - word.length) > limit) return false
            return distance(typed, word) <= limit
        }

        /** Optimal string alignment: insert, delete, substitute, or swap two neighbours, each one edit. */
        fun distance(a: String, b: String): Int {
            var older = IntArray(b.length + 1)
            var last = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val row = IntArray(b.length + 1)
                row[0] = i
                for (j in 1..b.length) {
                    row[j] = minOf(last[j] + 1, row[j - 1] + 1, last[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) row[j] = minOf(row[j], older[j - 2] + 1)
                }
                older = last; last = row
            }
            return last[b.length]
        }
    }
}
