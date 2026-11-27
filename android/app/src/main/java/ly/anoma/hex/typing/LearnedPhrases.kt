package ly.anoma.hex.typing

/** The runs of words you write again and again ("let me know", "on my way"), so the strip can offer one as
 * a single chip: how often each run of two to seven words ([learn]) went into a text field, and, from the
 * word before the cursor (or the sentence's start), the run you most reliably write next ([offer]).
 *
 * A run is only counted once it has been seen twice: its first sighting is kept as a bare key in a window
 * of the last [WINDOW] words, so what a person writes once never takes room in [uses]. Runs are keyed in
 * lowercase, words joined by a space, with "^" for a sentence's start; a mark, line break or anything but
 * letters ends a run. Kept on the phone only; thread-safe. */
internal class LearnedPhrases {
    private class Use(var count: Int, var at: Int)
    private val uses = HashMap<String, Use>()
    // For each run (or word), the word that most often follows it in [uses]: [offer] walks these.
    private val best = HashMap<String, String>()
    // Runs seen once, from the last [WINDOW] words; a second sighting moves a run to [uses]. [steps] are
    // those words' runs, which is also what is saved of the window.
    private val window = HashSet<String>()
    private val steps = ArrayDeque<List<String>>()
    private var clock = 0

    /** The word that ends [before], the text before the cursor with the space or mark that ended it, was typed. */
    @Synchronized fun learn(before: String) {
        val tokens = tokens(before) ?: return
        count(tokens, 1)
        steps.addLast(tokens)
        if (steps.size > WINDOW) for (key in suffixes(steps.removeFirst())) window.remove(key)
        trim()
    }

    /** Takes back one [learn] of the same [before]: an autocorrection that was undone counts for nothing. */
    @Synchronized fun unlearn(before: String) {
        val tokens = tokens(before) ?: return
        count(tokens, -1)
    }

    /** The words you most reliably write after [context] (a lowercase word, or "^" at a sentence's start), as
     * many as follow each other surely enough: each run used at least [MIN_USES] times, each step at least
     * [STEP_SHARE] of the uses of what came before, and the steps together at least [WHOLE_SHARE]. [allowed]
     * says whether a word may be offered; the walk stops at the first that may not. At least two words, or
     * none. Reads only maps. */
    @Synchronized fun offer(context: String, allowed: (String) -> Boolean): List<String> {
        val out = ArrayList<String>(MAX_WORDS)
        var prefix = context
        var share = 1.0
        while (out.size < MAX_WORDS) {
            val child = best[prefix] ?: break
            if (!allowed(child)) break
            val run = "$prefix $child"
            val used = uses[run]?.count ?: break
            val step = used.toDouble() / maxOf(uses[prefix]?.count ?: 0, used)
            if (used < MIN_USES || step < STEP_SHARE || share * step < WHOLE_SHARE) break
            out.add(child); share *= step; prefix = run
        }
        return if (out.size >= 2) out else emptyList()
    }

    /** Drops every run with [word] in it. */
    @Synchronized fun forgetWord(word: String) {
        val lower = word.lowercase()
        uses.keys.removeAll { lower in it.split(' ') }
        window.removeAll { lower in it.split(' ') }
        steps.removeAll { lower in it }
        reindex()
    }

    @Synchronized fun clear() { uses.clear(); best.clear(); window.clear(); steps.clear() }

    /** One line per counted run (`c`, its count, the run) and per recent run of words (`r`, the words). */
    @Synchronized fun write(): String {
        val out = StringBuilder(uses.size * 32 + steps.size * 40 + 16)
        for ((run, u) in uses) out.append("c\t").append(u.count).append('\t').append(run).append('\n')
        for (s in steps) out.append("r\t").append(s.joinToString(" ")).append('\n')
        return out.toString()
    }

    /** What [write] wrote, added to what's here; a line it can't read is skipped. */
    @Synchronized fun read(text: String) {
        for (line in text.lineSequence()) {
            val f = line.split('\t')
            when {
                f.size == 3 && f[0] == "c" -> {
                    val n = f[1].toIntOrNull()?.takeIf { it > 0 } ?: continue
                    uses.getOrPut(f[2]) { Use(0, clock++) }.count += n
                }
                f.size == 2 && f[0] == "r" -> f[1].split(' ').takeIf { it.size in 1..MAX_TOKENS }?.let { steps.addLast(it) }
            }
        }
        while (steps.size > WINDOW) steps.removeFirst()
        for (s in steps) for (key in suffixes(s)) if (key !in uses) window.add(key)
        trim(); reindex()
    }

    private fun suffixes(tokens: List<String>) = (2..tokens.size).map { tokens.takeLast(it).joinToString(" ") }

    // The last word, and each run that ends in it, were seen once more ([delta] 1) or once less (-1).
    private fun count(tokens: List<String>, delta: Int) {
        word(tokens.last(), delta)
        if (tokens.size == 2 && tokens[0] == START) word(START, delta)
        for (run in suffixes(tokens)) {
            val u = uses[run]
            when {
                delta > 0 && u != null -> { u.count++; u.at = clock++; follow(run, u) }
                delta > 0 -> if (window.remove(run)) uses[run] = Use(2, clock++).also { follow(run, it) } else window.add(run)
                u != null -> if (--u.count < 2) { uses.remove(run); window.add(run) }
            }
        }
    }
    private fun word(word: String, delta: Int) {
        val u = uses[word]
        if (u == null) { if (delta > 0) uses[word] = Use(1, clock++); return }
        u.count += delta; u.at = clock++
        if (u.count <= 0) uses.remove(word)
    }

    // [run] gained a use: its last word may now be what follows the rest of it most often.
    private fun follow(run: String, u: Use) {
        val cut = run.lastIndexOf(' ')
        val prefix = run.substring(0, cut); val child = run.substring(cut + 1)
        val now = best[prefix]
        if (now == null || now == child || u.count > (uses["$prefix $now"]?.count ?: 0)) best[prefix] = child
    }
    private fun reindex() {
        best.clear()
        for ((run, u) in uses) if (' ' in run) follow(run, u)
    }

    // The least used (and oldest of those) go first once the counts are full.
    private fun trim() {
        if (uses.size <= MAX_USES) return
        val drop = uses.entries.sortedWith(compareBy({ it.value.count }, { it.value.at })).take(uses.size - MAX_USES * 9 / 10)
        for (e in drop) uses.remove(e.key)
        reindex()
    }

    companion object {
        /** The saved copy, in the app's files folder. */
        const val FILE = "learned-phrases.tsv"
        /** A sentence's start, as the word before the first. */
        const val START = "^"
        /** Words a phrase has, at most. */
        const val MAX_WORDS = 6
        private const val MAX_TOKENS = MAX_WORDS + 1
        private const val WINDOW = 3_000
        private const val MAX_USES = 20_000
        private const val MIN_USES = 4
        private const val STEP_SHARE = 0.6
        private const val WHOLE_SHARE = 0.5

        private fun learnable(word: String) = word.isNotEmpty() && word.all { it.isLetter() || it == '\'' } && word.length <= TypingWord.MAX_LENGTH

        /** The words that end [before] (the text before the cursor, right after the space or mark that ended
         * the last), oldest first, lowercase, with [START] first when they reach a sentence's start and
         * there is room; null when no word ends there. */
        fun tokens(before: String): List<String>? {
            var end = before.length
            while (end > 0 && !before[end - 1].isLetter()) {
                val c = before[end - 1]
                if (!c.isWhitespace() && !TypingWord.endsWord(c)) return null
                end--
            }
            if (end == before.length || end == 0) return null
            val text = before.substring(0, end).replace('’', '\'')
            val token = TypingWord.currentToken(text) ?: return null
            val last = token.trimEnd('\'').lowercase()
            var rest = text.dropLast(token.length)
            if (!learnable(last) || rest.lastOrNull()?.let { !it.isWhitespace() && it !in "([{\"“‘" } == true) return null
            val out = ArrayDeque<String>()
            out.addFirst(last)
            while (out.size < MAX_TOKENS) {
                val (word, earlier) = LearnedWords.wordBeforeGap(rest) ?: break
                val lower = word.lowercase()
                if (!learnable(lower)) break
                out.addFirst(lower); rest = earlier
            }
            if (out.size < MAX_TOKENS && TypingWord.startsSentence(rest)) out.addFirst(START)
            return out
        }
    }
}
