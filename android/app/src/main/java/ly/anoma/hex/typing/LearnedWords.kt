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
    // The words used at least [WORD_USES] times, in order, so [completions] reads one prefix's run of them
    // instead of every word; kept as counts change and rebuilt when they change wholesale.
    private val completable = java.util.TreeMap<String, Seen>()
    // Bumped by every change, so the strip's repeated asks for one prefix are answered from [lastCompletions].
    private var stamp = 0
    private class Completions(val stamp: Int, val prefix: String, val limit: Int, val result: List<String>)
    private var lastCompletions: Completions? = null
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
        seen.typed++; total++; stamp++
        if (!was && seen.count >= WORD_USES) completable[lower] = seen
        if (!sentenceStart) seen.spelling = word
        val before = previous?.lowercase()?.takeIf(::learnable)
        before?.let { typedPairs.merge(pair(it, lower), 1, Int::plus); followers?.getOrPut(it) { HashSet(4) }?.add(lower) }
        if (before != null) beforePrevious?.lowercase()?.takeIf(::learnable)?.let { triples.learn(it, before, lower) }
        trim()
        return (!was && seen.count >= WORD_USES).also { if (it) revision++ }
    }

    /** Takes back one [learn] of [word] after [previous] and [beforePrevious], as it was called: a word
     * that went in and was undone at once (an autocorrection you reverted) counts for nothing, so undoing
     * it again and again never makes it yours. Returns whether [words] changed. */
    @Synchronized fun unlearn(word: String, previous: String?, beforePrevious: String? = null): Boolean {
        val lower = word.lowercase()
        val seen = words[lower]?.takeIf { it.typed > 0 } ?: return false
        val was = seen.count >= WORD_USES
        seen.typed--; total--; stamp++
        if (seen.count < WORD_USES) completable.remove(lower)
        if (seen.count == 0) forget(lower)
        val before = previous?.lowercase()?.takeIf(::learnable)
        if (before != null) {
            val k = pair(before, lower)
            typedPairs[k]?.let { n -> if (n > 1) typedPairs[k] = n - 1 else { typedPairs.remove(k); followers = null } }
            beforePrevious?.lowercase()?.takeIf(::learnable)?.let { triples.unlearn(it, before, lower) }
        }
        return (was && seen.count < WORD_USES).also { if (it) revision++ }
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
        reindex()
        followers = null
        seededPairs = HashMap<String, Int>().also { m -> for ((a, b, n) in pairs) if (n > 0) m[pair(a.lowercase(), b.lowercase())] = n }
        revision++
    }

    /** Drops [word] and the pairs it is in, seeded or typed, so it stops counting and is no longer one of [words]. */
    @Synchronized fun forgetWord(word: String) {
        val lower = word.lowercase()
        words[lower]?.let { total -= it.count; forget(lower) }
        stamp++
        for (m in listOf(typedPairs, seededPairs)) m.keys.removeAll { it.substringBefore(' ') == lower || it.substringAfter(' ') == lower }
        followers = null; triples.forget(lower)
        revision++
    }

    @Synchronized fun clear() {
        words.clear(); completable.clear(); stamp++; spellings.clear(); typedPairs.clear(); seededPairs.clear(); followers = null; triples.clear(); typedAddresses.clear(); total = 0; revision++
    }

    /** Your words, spelled your way, used at least twice: the ones the keyboard treats as words. */
    @Synchronized fun words(): List<String> = words.values.filter { it.count >= WORD_USES }.map { it.spelling }

    /** Your [words] that [model]'s word list lacks: the ones read as your own words beside it ([PersonalWords]),
     * so taps and glides spell them. A word only its validity or capitals list knows ("kotlin", "Ravi")
     * is one of these too: those lists keep a typed word, but a glide walks word lists alone. */
    fun yours(model: TypingModel): List<String> = words().filter { model.index(it.lowercase()) < 0 }

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

    /** Your words that start with [prefix] and are longer, most used first (equally used ones alphabetically),
     * those used at least twice. */
    @Synchronized fun completions(prefix: String, limit: Int = 3): List<String> {
        lastCompletions?.let { if (it.stamp == stamp && it.limit == limit && it.prefix == prefix) return it.result }
        val lower = prefix.lowercase()
        // The words with this prefix are one run of [completable]; keep the [limit] most used, by insertion.
        val best = ArrayList<Seen>(limit + 1)
        for ((w, s) in completable.tailMap(lower, true)) {
            if (!w.startsWith(lower)) break
            if (w.length <= lower.length) continue
            val uses = s.count
            var at = best.size
            while (at > 0 && best[at - 1].count < uses) at--
            if (at < limit) { best.add(at, s); if (best.size > limit) best.removeAt(limit) }
        }
        return best.map { it.spelling }.also { lastCompletions = Completions(stamp, prefix, limit, it) }
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
    @Synchronized fun write(): String {
        // Sized up front: a full store is a couple of megabytes, and growing a builder copies it each time.
        val out = StringBuilder(words.size * 24 + typedAddresses.size * 40 + (typedPairs.size + seededPairs.size) * 28 + triples.size * 32 + 64)
        for (s in words.values) out.append("w\t").append(s.spelling).append('\t').append(s.typed).append('\t').append(s.seeded).append('\n')
        for (a in typedAddresses.values) out.append("e\t").append(a.text).append('\t').append(a.uses).append('\n')
        for ((k, n) in typedPairs) appendPair(out, 'p', k, n)
        for ((k, n) in seededPairs) appendPair(out, 's', k, n)
        triples.write(out)
        return out.toString()
    }
    // `tag first second count` for a pair kept as "first second".
    private fun appendPair(out: StringBuilder, tag: Char, key: String, count: Int) {
        val space = key.indexOf(' ')
        // A key with more spaces than the one between two words (a seeded pair with a spaced word) keeps every one as a tab.
        if (space != key.lastIndexOf(' ')) { out.append(tag).append('\t').append(key.replace(' ', '\t')).append('\t').append(count).append('\n'); return }
        out.append(tag).append('\t').append(key, 0, space).append('\t').append(key, space + 1, key.length).append('\t').append(count).append('\n')
    }

    /** What [write] wrote, added to what's here; a line it can't read is skipped. */
    @Synchronized fun read(text: String) {
        // Lines end at \n, \r\n or \r, as lineSequence reads them, split at their tabs in place: a full file
        // is some ninety thousand lines, and a list per line made it most of a second on a phone.
        var start = 0
        while (start < text.length) {
            var end = start
            while (end < text.length && text[end] != '\n' && text[end] != '\r') end++
            readLine(text, start, end)
            start = if (text.startsWith("\r\n", end)) end + 2 else end + 1
        }
        reindex()
        revision++
    }

    private fun readLine(text: String, start: Int, end: Int) {
        val cut = IntArray(4); var tabs = 0
        for (i in start until end) if (text[i] == '\t') { if (tabs == cut.size) return; cut[tabs++] = i }
        if (tabs < 2 || cut[0] - start != 1) return
        fun field(i: Int) = text.substring(if (i == 0) start else cut[i - 1] + 1, if (i == tabs) end else cut[i])
        when {
            tabs == 3 && text[start] == 'w' -> {
                val typed = field(2).toIntOrNull() ?: return; val seeded = field(3).toIntOrNull() ?: return
                val spelling = field(1)
                val lower = spelling.lowercase()
                if (!learnable(lower) || typed < 0 || seeded < 0) return
                val seen = entry(lower, spelling)
                seen.typed += typed; seen.seeded += seeded; total += typed + seeded
            }
            tabs == 3 && text[start] == 'p' -> field(3).toIntOrNull()?.let { typedPairs.merge(pair(field(1), field(2)), it, Int::plus); followers = null }
            tabs == 4 && text[start] == 't' -> {
                val first = field(1); val second = field(2); val word = field(3)
                if (learnable(first.lowercase()) && learnable(second.lowercase()) && learnable(word.lowercase())) triples.read(first, second, word, field(4))
            }
            tabs == 3 && text[start] == 's' -> field(3).toIntOrNull()?.let { seededPairs[pair(field(1), field(2))] = it; followers = null }
            tabs == 2 && text[start] == 'e' -> {
                val uses = field(2).toIntOrNull()?.takeIf { it > 0 } ?: return
                val address = field(1)
                if (!EmailAddresses.valid(address)) return
                typedAddresses.getOrPut(address.lowercase()) { Address(address, 0) }.uses += uses
            }
        }
    }

    // The least used go first once the lists are full: one-off words and pairs, then the rest.
    private fun trim() {
        if (words.size > MAX_WORDS) {
            val drop = leastUsed(words, words.size - MAX_WORDS * 9 / 10) { it.count }
            for (w in drop) { total -= words.getValue(w).count; forget(w) }
            stamp++
            typedPairs.keys.removeAll { k -> k.substringBefore(' ') !in words || k.substringAfter(' ') !in words }
            followers = null
        }
        if (typedPairs.size > MAX_PAIRS) {
            for (k in leastUsed(typedPairs, typedPairs.size - MAX_PAIRS * 9 / 10) { it }) typedPairs.remove(k)
            followers = null
        }
    }

    /** The [drop] keys of [map] with the fewest [uses], the earliest in its order among equal ones: the ones
     * sorting by uses (stably) would drop first, found from the sorted uses alone instead of by sorting every
     * entry, which was tens of milliseconds on a full store. */
    private inline fun <V> leastUsed(map: HashMap<String, V>, drop: Int, uses: (V) -> Int): List<String> {
        if (drop <= 0) return emptyList()
        val sorted = IntArray(map.size); var i = 0
        for (v in map.values) sorted[i++] = uses(v)
        sorted.sort()
        val floor = sorted[minOf(drop, sorted.size) - 1]
        var ties = drop - sorted.count { it < floor }
        val out = ArrayList<String>(drop)
        for ((k, v) in map) {
            val n = uses(v)
            if (n < floor) out.add(k) else if (n == floor && ties > 0) { out.add(k); ties-- }
        }
        return out
    }

    private fun reindex() {
        completable.clear(); stamp++
        for ((w, s) in words) if (s.count >= WORD_USES) completable[w] = s
    }

    private fun entry(lower: String, spelling: String) = words.getOrPut(lower) {
        spellings.getOrPut(Spellings.key(lower)) { HashSet(2) }.add(lower)
        Seen(0, 0, spelling)
    }
    private fun forget(lower: String) {
        words.remove(lower); completable.remove(lower)
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
