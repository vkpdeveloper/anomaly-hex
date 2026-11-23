package ly.anoma.hex.typing

import kotlin.math.exp
import kotlin.math.ln

/** What you write, remembered, as Gboard's personal language model remembers it: how often each word,
 * and each word after another, went into a text field, typed or swiped here or counted in the paired
 * computer's Hex History ([seed]). The decoder, swipe typing and the strip mix it into the model's
 * likelihoods ([mixed]), so words you use win; a word the model lacks becomes one of yours once you've
 * used it twice ([words]), so a name you write swipes; of two spellings of one word the one you write
 * wins ([preferred]). The email addresses you've typed are kept too ([addresses]). Kept on the phone
 * only; thread-safe. */
internal class LearnedWords {
    private class Seen(var typed: Int, var seeded: Int, var spelling: String) {
        val count get() = typed + seeded
    }
    private val words = HashMap<String, Seen>()
    // The words in [words] by `Spellings.key`, so [preferred] reads a word's few other spellings.
    private val spellings = HashMap<String, HashSet<String>>()
    // Addresses by their lowercase, as typed and how often.
    private class Address(var text: String, var uses: Int)
    private val typedAddresses = HashMap<String, Address>()
    private val typedPairs = HashMap<String, Int>()
    private var seededPairs = HashMap<String, Int>()
    private val triples = PersonalTrigrams()
    // The words that followed each word in the pairs, so [next] reads one word's few followers instead
    // of every pair; built on first use, kept up as pairs are typed, dropped when pairs change wholesale.
    private var followers: HashMap<String, HashSet<String>>? = null
    private var total = 0L
    /** Bumped when [words] changes, so the keyboard rereads its own words. */
    @Volatile var revision = 0
        private set

    /** [word] went in after [previous] (null after a mark or at a sentence's start, where every word
     * has a capital, so its spelling there teaches nothing), which went in after [beforePrevious]
     * (null when no word, or a mark, came before it). Returns whether [words] changed. */
    @Synchronized fun learn(word: String, previous: String?, sentenceStart: Boolean, beforePrevious: String? = null): Boolean {
        val lower = word.lowercase()
        if (!learnable(lower)) return false
        val seen = entry(lower, lower)
        val was = seen.count >= WORD_USES
        seen.typed++; total++
        if (!sentenceStart) seen.spelling = word
        val before = previous?.lowercase()?.takeIf(::learnable)
        before?.let { typedPairs.merge(pair(it, lower), 1, Int::plus); followers?.getOrPut(it) { HashSet(4) }?.add(lower) }
        if (before != null) beforePrevious?.lowercase()?.takeIf(::learnable)?.let { triples.learn(it, before, lower) }
        trim()
        return (!was && seen.count >= WORD_USES).also { if (it) revision++ }
    }

    /** The paired computer's counts, replacing the last ones it sent; typed counts stay. */
    @Synchronized fun seed(counts: List<Pair<String, Int>>, pairs: List<Triple<String, String, Int>>) {
        for (seen in words.values) { total -= seen.seeded; seen.seeded = 0 }
        for ((spelling, count) in counts) {
            val lower = spelling.lowercase()
            if (!learnable(lower) || count <= 0) continue
            val seen = entry(lower, spelling)
            if (seen.typed == 0) seen.spelling = spelling
            seen.seeded = count; total += count
        }
        words.entries.filter { it.value.count == 0 }.forEach { forget(it.key) }
        followers = null
        seededPairs = HashMap<String, Int>().also { m -> for ((a, b, n) in pairs) if (n > 0) m[pair(a.lowercase(), b.lowercase())] = n }
        revision++
    }

    @Synchronized fun clear() {
        words.clear(); spellings.clear(); typedPairs.clear(); seededPairs.clear(); followers = null; triples.clear(); typedAddresses.clear(); total = 0; revision++
    }

    /** Your words, spelled your way, used at least twice: the ones the keyboard treats as words. */
    @Synchronized fun words(): List<String> = words.values.filter { it.count >= WORD_USES }.map { it.spelling }

    /** Your spelling of a word you've used, or null. */
    @Synchronized fun spelling(word: String): String? = words[word.lowercase()]?.spelling

    /** The spelling you write of [word] (in lowercase) when you write another one of it far more: one that
     * [alike] calls the same word, used at least [PREFERRED_USES] times and [PREFERRED_SHARE] times as
     * often as [word], so a slip or two never turns it ("nhi" for one who writes it, not "nahi"). Null
     * when [word] is yours already or none is. */
    @Synchronized fun preferred(word: String, alike: (String, String) -> Boolean): String? {
        val lower = word.lowercase()
        var best: String? = null
        var uses = maxOf(PREFERRED_USES, PREFERRED_SHARE * (words[lower]?.count ?: 0)) - 1
        for (other in spellings[Spellings.key(lower)] ?: return null) {
            val count = words[other]?.count ?: continue
            if (other != lower && count > uses && alike(lower, other)) { best = other; uses = count }
        }
        return best
    }

    /** [address], a whole email address, went into a text field. */
    @Synchronized fun learnAddress(address: String) {
        val seen = typedAddresses.getOrPut(address.lowercase()) { Address(address, 0) }
        seen.text = address; seen.uses++
        if (typedAddresses.size > MAX_ADDRESSES)
            typedAddresses.entries.sortedBy { it.value.uses }.take(typedAddresses.size - MAX_ADDRESSES * 9 / 10)
                .forEach { typedAddresses.remove(it.key) }
    }

    /** The addresses you've typed that start with [prefix] (in any case) and are longer, most used first. */
    @Synchronized fun addresses(prefix: String, limit: Int = 3): List<String> {
        val lower = prefix.lowercase()
        return typedAddresses.entries.asSequence().filter { (k, _) -> k.length > lower.length && k.startsWith(lower) }
            .sortedByDescending { it.value.uses }.take(limit).map { it.value.text }.toList()
    }

    /** -ln P(word | previous) by your own counts alone, or infinity for a word you never used. */
    @Synchronized fun cost(word: String, previous: String?): Double {
        val lower = word.lowercase()
        val seen = words[lower] ?: return Double.POSITIVE_INFINITY
        if (seen.count == 0 || total <= 0) return Double.POSITIVE_INFINITY
        val alone = seen.count.toDouble() / total
        // The pair's share of the uses of the word before (the pairs alone when that word wasn't counted).
        val together = previous?.lowercase()?.let { prev ->
            val n = pairCount(prev, lower)
            if (n > 0) n.toDouble() / maxOf(n, words[prev]?.count ?: 0) else 0.0
        } ?: 0.0
        return -ln(if (together > 0) PAIR_SHARE * together + (1 - PAIR_SHARE) * alone else alone)
    }

    /** The model's cost for [word] after [previous] ([modelCost], infinity when it lacks the word) mixed
     * with yours: the two models' probabilities blended, so your words pull ahead without the
     * model's being lost. */
    fun mixed(modelCost: Double, word: String, previous: String?): Double {
        val yours = cost(word, previous)
        if (yours.isInfinite()) return modelCost
        return -ln(MODEL_SHARE * exp(-modelCost) + (1 - MODEL_SHARE) * exp(-yours))
    }

    /** Your words that start with [prefix] and are longer, most used first, those used at least twice. */
    @Synchronized fun completions(prefix: String, limit: Int = 3): List<String> {
        val lower = prefix.lowercase()
        return words.entries.asSequence().filter { (w, s) -> s.count >= WORD_USES && w.length > lower.length && w.startsWith(lower) }
            .sortedByDescending { it.value.count }.take(limit).map { it.value.spelling }.toList()
    }

    /** The words you most often write after [previous], at least twice each. */
    @Synchronized fun next(previous: String, limit: Int = 3): List<String> {
        val first = previous.lowercase()
        val following = (followers ?: buildFollowers().also { followers = it })[first] ?: return emptyList()
        return following.map { it to pairCount(first, it) }.filter { it.second >= WORD_USES }.sortedByDescending { it.second }.take(limit)
            .map { words[it.first]?.spelling ?: it.first }
    }

    /** The words you most often write after [first] then [second], then those after [second] alone, at
     * least twice each; with a [prefix], the ones that complete it. */
    @Synchronized fun next(first: String?, second: String, prefix: String = "", limit: Int = 3): List<String> {
        val lowerSecond = second.lowercase()
        val own = first?.let { triples.next(it.lowercase(), lowerSecond, prefix.lowercase(), limit) }.orEmpty()
        if (own.size >= limit) return own.map(::spelled)
        val pairs = if (prefix.isEmpty()) next(second, limit) else emptyList()
        return (own.map(::spelled) + pairs).distinct().take(limit)
    }
    private fun spelled(lower: String) = words[lower]?.spelling ?: lower

    /** One line per word, pair, triple and address, tab-separated: `w spelling typed seeded`, `p first second
     * typed`, `s first second seeded`, `t first second third typed`, `e address uses`. */
    @Synchronized fun write(): String = buildString {
        for (s in words.values) append("w\t").append(s.spelling).append('\t').append(s.typed).append('\t').append(s.seeded).append('\n')
        for (a in typedAddresses.values) append("e\t").append(a.text).append('\t').append(a.uses).append('\n')
        for ((k, n) in typedPairs) append("p\t").append(k.replace(' ', '\t')).append('\t').append(n).append('\n')
        for ((k, n) in seededPairs) append("s\t").append(k.replace(' ', '\t')).append('\t').append(n).append('\n')
        triples.write(this)
    }

    /** What [write] wrote, added to what's here; a line it can't read is skipped. */
    @Synchronized fun read(text: String) {
        for (line in text.lineSequence()) {
            val f = line.split('\t')
            when {
                f.size == 4 && f[0] == "w" -> {
                    val typed = f[2].toIntOrNull() ?: continue; val seeded = f[3].toIntOrNull() ?: continue
                    val lower = f[1].lowercase()
                    if (!learnable(lower) || typed < 0 || seeded < 0) continue
                    val seen = entry(lower, f[1])
                    seen.typed += typed; seen.seeded += seeded; total += typed + seeded
                }
                f.size == 4 && f[0] == "p" -> f[3].toIntOrNull()?.let { typedPairs.merge(pair(f[1], f[2]), it, Int::plus); followers = null }
                f.size == 5 && f[0] == "t" -> if (f.subList(1, 4).all { learnable(it.lowercase()) }) triples.read(f)
                f.size == 4 && f[0] == "s" -> f[3].toIntOrNull()?.let { seededPairs[pair(f[1], f[2])] = it; followers = null }
                f.size == 3 && f[0] == "e" -> {
                    val uses = f[2].toIntOrNull()?.takeIf { it > 0 } ?: continue
                    if (!EmailAddresses.valid(f[1])) continue
                    typedAddresses.getOrPut(f[1].lowercase()) { Address(f[1], 0) }.uses += uses
                }
            }
        }
        revision++
    }

    // The least used go first once the lists are full: one-off words and pairs, then the rest.
    private fun trim() {
        if (words.size > MAX_WORDS) {
            val drop = words.entries.sortedBy { it.value.count }.take(words.size - MAX_WORDS * 9 / 10)
            for ((w, s) in drop) { forget(w); total -= s.count }
            typedPairs.keys.removeAll { k -> k.substringBefore(' ') !in words || k.substringAfter(' ') !in words }
            followers = null
        }
        if (typedPairs.size > MAX_PAIRS) {
            val drop = typedPairs.entries.sortedBy { it.value }.take(typedPairs.size - MAX_PAIRS * 9 / 10).map { it.key }
            for (k in drop) typedPairs.remove(k)
            followers = null
        }
    }

    private fun entry(lower: String, spelling: String) = words.getOrPut(lower) {
        spellings.getOrPut(Spellings.key(lower)) { HashSet(2) }.add(lower)
        Seen(0, 0, spelling)
    }
    private fun forget(lower: String) {
        words.remove(lower)
        val key = Spellings.key(lower)
        spellings[key]?.let { if (it.remove(lower) && it.isEmpty()) spellings.remove(key) }
    }

    private fun buildFollowers() = HashMap<String, HashSet<String>>().also { index ->
        for (m in listOf(typedPairs, seededPairs)) for (k in m.keys) {
            val space = k.indexOf(' ')
            index.getOrPut(k.substring(0, space)) { HashSet(4) }.add(k.substring(space + 1))
        }
    }

    private fun pairCount(first: String, second: String): Int {
        val k = pair(first, second)
        return (typedPairs[k] ?: 0) + (seededPairs[k] ?: 0)
    }

    companion object {
        /** The saved copy, in the app's files folder. */
        const val FILE = "learned-words.tsv"
        /** Uses before a word the model lacks counts as a word: one use may be a slip. */
        const val WORD_USES = 2
        private const val MAX_WORDS = 20_000
        private const val MAX_PAIRS = 50_000
        private const val MAX_ADDRESSES = 1_000
        /** Uses, and times as many as the word itself, before your spelling of a word takes its place. */
        const val PREFERRED_USES = 3
        const val PREFERRED_SHARE = 4
        // How much of the blend is the model's, and how much of yours is the word after the one before.
        private const val MODEL_SHARE = 0.7
        private const val PAIR_SHARE = 0.6

        private fun pair(first: String, second: String) = "$first $second"
        private fun learnable(lower: String) = lower.length in 1..TypingWord.MAX_LENGTH &&
            lower.all { it.isLetter() || it == '\'' } && lower.any { it.isLetter() }

        /** The word that ends [before] (the text before the cursor, right after a space, mark or line
         * break went in), the word before it, and the one before that, when nothing but a space
         * separates them, and whether it starts a sentence; null when no word ends there. */
        fun finished(before: String): Finished? {
            var end = before.length
            while (end > 0 && !before[end - 1].isLetter()) {
                val c = before[end - 1]
                if (!c.isWhitespace() && !TypingWord.endsWord(c)) return null
                end--
            }
            if (end == before.length) return null
            val text = before.substring(0, end).replace('’', '\'')
            val word = TypingWord.currentToken(text)?.trimEnd('\'') ?: return null
            if (word.isEmpty()) return null
            val rest = text.dropLast(TypingWord.currentToken(text)!!.length)
            // A word joined to more than letters ("v2", "a@b", "x.com") isn't one to learn.
            if (rest.lastOrNull()?.let { !it.isWhitespace() && it !in "([{\"“‘" } == true) return null
            val first = wordBeforeGap(rest)
            val second = first?.let { wordBeforeGap(it.second) }
            return Finished(word, first?.first, TypingWord.startsSentence(rest), second?.first)
        }

        /** The word right before [text]'s one trailing space, with the text before that word: null
         * for no space or two, a mark, or a word joined to more than letters. */
        fun wordBeforeGap(text: String): Pair<String, String>? {
            if (text.takeLastWhile { it == ' ' }.length != 1) return null
            val head = text.dropLast(1)
            val token = TypingWord.currentToken(head)?.takeIf { it.isNotEmpty() } ?: return null
            val start = head.dropLast(token.length)
            if (start.lastOrNull()?.let { it.isWhitespace() || it in "([{\"“‘" } == false) return null
            return token.trimEnd('\'') to start
        }
    }
    data class Finished(val word: String, val previous: String?, val sentenceStart: Boolean, val beforePrevious: String? = null)
}
