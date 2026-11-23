package ly.anoma.hex.typing

import kotlin.math.exp
import kotlin.math.ln

/** What you write, remembered, as Gboard's personal language model remembers it: how often each word,
 * and each word after one or two others, went into a text field, typed or swiped here or counted in the
 * paired computer's Hex History ([seed]), each use fading with time ([Fade]) and the app it was written
 * in kept apart too ([app], [AppHabits]). The decoder, swipe typing and the strip mix it into the model's
 * likelihoods ([mixed]), so words you use win; a word the model lacks becomes one of yours once you've
 * used it twice ([words]), so a name you write swipes; of two spellings of one word the one you write
 * wins ([preferred]). The email addresses you've typed are kept too ([addresses]). Kept on the phone
 * only; thread-safe. */
internal class LearnedWords(private val now: () -> Long = System::currentTimeMillis) {
    private class Seen(var typed: Int, var seeded: Int, var spelling: String) {
        /** What the typed and the seeded uses are worth now, as stored ([Fade]). */
        var tw = 0.0
        var sw = 0.0
        // Which models' word lists [yours] has looked this word up in (bit per model), and which lacked it.
        var asked = 0
        var lacked = 0
        /** Uses in all, however long ago: what makes a word yours. */
        val count get() = typed + seeded
        val weight get() = tw + sw
    }
    private val fade = Fade(now)
    private val words = HashMap<String, Seen>()
    // The words used at least [WORD_USES] times, in order, so [completions] reads one prefix's run of them
    // instead of every word; kept as counts change and rebuilt when they change wholesale.
    private val completable = java.util.TreeMap<String, Seen>()
    // Bumped by every change, so the strip's repeated asks for one prefix are answered from [lastCompletions].
    private var stamp = 0
    private class Completions(val stamp: Int, val prefix: String, val limit: Int, val result: List<String>)
    private var lastCompletions: Completions? = null
    // `Spellings.key` of the words asked about lately: the strip asks about the same few candidates at every key.
    private val keys = HashMap<String, String>()
    private fun spellingKey(lower: String): String {
        keys[lower]?.let { return it }
        if (keys.size >= MAX_KEYS) keys.clear()
        return Spellings.key(lower).also { keys[lower] = it }
    }
    // The words in [words] by `Spellings.key`, so [preferred] reads a word's few other spellings.
    private val spellings = HashMap<String, HashSet<String>>()
    // Addresses by their lowercase, as typed and how often.
    private class Address(var text: String, var uses: Int)
    private val typedAddresses = HashMap<String, Address>()
    // What followed each word, typed here and counted by the computer; one read of a word's followers.
    private val typedPairs = HashMap<String, Follows>()
    private var typedPairCount = 0
    private var seededPairs = HashMap<String, Follows>()
    private val triples = PersonalTrigrams()
    private val apps = AppHabits()
    /** The app being written in (its package), whose habits [learn] adds to and [mixed] reads; null for none. */
    @Volatile var app: String? = null
    // The weight of every word.
    private var total = 0.0
    /** Bumped when [words] changes, so the keyboard rereads its own words. */
    @Volatile var revision = 0
        private set

    /** [word] went in after [previous] (null after a mark or at a sentence's start, where every word
     * has a capital, so its spelling there teaches nothing), which went in after [beforePrevious]
     * (null when no word, or a mark, came before it). Returns whether [words] changed. */
    @Synchronized fun learn(word: String, previous: String?, sentenceStart: Boolean, beforePrevious: String? = null): Boolean {
        val lower = word.lowercase()
        if (!learnable(lower)) return false
        fade.tick()
        if (fade.rebaseDue) rebase()
        val unit = fade.unit
        val seen = entry(lower, lower)
        val was = seen.count >= WORD_USES
        seen.typed++; seen.tw += unit; total += unit; stamp++
        if (!was && seen.count >= WORD_USES) completable[lower] = seen
        if (!sentenceStart) seen.spelling = word
        val before = previous?.lowercase()?.takeIf(::learnable)
        if (before != null) {
            if (typedPairs.getOrPut(before) { Follows() }.add(lower, 1, unit)) typedPairCount++
            beforePrevious?.lowercase()?.takeIf(::learnable)?.let { triples.learn(it, before, lower, 1, unit) }
        }
        app?.let { apps.learn(it, lower, before, unit, now()) }
        trim()
        return (!was && seen.count >= WORD_USES).also { if (it) revision++ }
    }

    /** Takes back one [learn] of [word] after [previous] and [beforePrevious], as it was called: a word
     * that went in and was undone at once (an autocorrection you reverted) counts for nothing, so undoing
     * it again and again never makes it yours. Returns whether [words] changed. */
    @Synchronized fun unlearn(word: String, previous: String?, beforePrevious: String? = null): Boolean {
        val lower = word.lowercase()
        val seen = words[lower]?.takeIf { it.typed > 0 } ?: return false
        fade.tick()
        val unit = fade.unit
        val was = seen.count >= WORD_USES
        val taken = minOf(unit, seen.tw)
        seen.typed--; seen.tw -= taken; total -= taken; stamp++
        if (seen.count < WORD_USES) completable.remove(lower)
        if (seen.count == 0) { total -= seen.weight; forget(lower) }
        val before = previous?.lowercase()?.takeIf(::learnable)
        if (before != null) {
            typedPairs[before]?.let { f ->
                if (f.take(lower, unit)) { typedPairCount--; if (f.map.isEmpty()) typedPairs.remove(before) }
            }
            beforePrevious?.lowercase()?.takeIf(::learnable)?.let { triples.unlearn(it, before, lower, unit) }
        }
        app?.let { apps.unlearn(it, lower, before, unit) }
        return (was && seen.count < WORD_USES).also { if (it) revision++ }
    }

    /** The paired computer's counts, replacing the last ones it sent; typed counts stay. They count as
     * used now, and fade from here if the computer stops sending. */
    @Synchronized fun seed(counts: List<Pair<String, Int>>, pairs: List<Triple<String, String, Int>>) {
        fade.tick()
        val unit = fade.unit
        for (seen in words.values) { total -= seen.sw; seen.seeded = 0; seen.sw = 0.0 }
        for ((spelling, count) in counts) {
            val lower = spelling.lowercase()
            if (!learnable(lower) || count <= 0) continue
            val seen = entry(lower, spelling)
            if (seen.typed == 0) seen.spelling = spelling
            total -= seen.sw
            seen.seeded = count; seen.sw = count * unit; total += seen.sw
        }
        words.entries.filter { it.value.count == 0 }.forEach { forget(it.key) }
        reindex()
        seededPairs = HashMap<String, Follows>().also { m ->
            for ((a, b, n) in pairs) if (n > 0) m.getOrPut(a.lowercase()) { Follows() }.add(b.lowercase(), n, n * unit)
        }
        revision++
    }

    /** Drops [word] and the pairs it is in, seeded or typed, so it stops counting and is no longer one of [words]. */
    @Synchronized fun forgetWord(word: String) {
        val lower = word.lowercase()
        words[lower]?.let { total -= it.weight; forget(lower) }
        stamp++
        for (m in listOf(typedPairs, seededPairs)) {
            m.remove(lower)
            val it = m.values.iterator()
            while (it.hasNext()) { val f = it.next(); f.remove(lower); if (f.map.isEmpty()) it.remove() }
        }
        typedPairCount = typedPairs.values.sumOf { it.map.size }
        triples.forget(lower); apps.forget(lower)
        revision++
    }

    @Synchronized fun clear() {
        words.clear(); completable.clear(); stamp++; spellings.clear(); typedPairs.clear(); typedPairCount = 0; seededPairs.clear()
        triples.clear(); apps.clear(); typedAddresses.clear(); total = 0.0; revision++
    }

    /** Your words, spelled your way, used at least twice: the ones the keyboard treats as words. */
    @Synchronized fun words(): List<String> = words.values.filter { it.count >= WORD_USES }.map { it.spelling }

    /** Your [words] that [model]'s word list lacks: the ones read as your own words beside it ([PersonalWords]),
     * so taps and glides spell them. A word only its validity or capitals list knows ("kotlin", "Ravi")
     * is one of these too: those lists keep a typed word, but a glide walks word lists alone. */
    @Synchronized fun yours(model: TypingModel): List<String> {
        // Whether a model lacks a word is asked of its word list once per word and model, then read from the
        // word: the search is most of a store's cost, and the engine asks again at every new word of yours.
        val bit = modelSlot(model).takeIf { it >= 0 }?.let { 1 shl it }
        val out = ArrayList<String>()
        for ((lower, s) in words) {
            if (s.count < WORD_USES) continue
            val lacks = when {
                bit == null -> model.index(lower) < 0
                s.asked and bit != 0 -> s.lacked and bit != 0
                else -> (model.index(lower) < 0).also { s.asked = s.asked or bit; if (it) s.lacked = s.lacked or bit }
            }
            if (lacks) out.add(s.spelling)
        }
        return out
    }
    // The models [yours] has been asked about, by bit in [Seen.asked]; a model past the 32nd is just asked each time.
    private val models = ArrayList<TypingModel>()
    private fun modelSlot(model: TypingModel): Int {
        val at = models.indexOf(model)
        if (at >= 0) return at
        return if (models.size < Int.SIZE_BITS) { models.add(model); models.lastIndex } else -1
    }

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
        for (other in spellings[spellingKey(lower)] ?: return null) {
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
    fun cost(word: String, previous: String?, beforePrevious: String? = null) =
        mixed(Double.POSITIVE_INFINITY, word, previous, beforePrevious)

    /** The model's cost for [word] after [previous] and [beforePrevious] ([modelCost], infinity when it
     * lacks the word) mixed with yours: the model's probability taken through each level of what you
     * wrote, from how often you write the word, to in this app, to after [previous], to after both
     * words, each level's say growing with the evidence behind it ([Interpolation.blend]). A word you
     * never used keeps [modelCost]; a little of your writing leaves the model's say nearly whole, and
     * a lot of it lets your habits win. */
    @Synchronized fun mixed(modelCost: Double, word: String, previous: String?, beforePrevious: String? = null): Double {
        val lower = word.lowercase()
        val seen = words[lower] ?: return modelCost
        val c = context(previous, beforePrevious)
        var p = if (modelCost.isInfinite()) 0.0 else exp(-modelCost)
        p = Interpolation.blend(p, seen.weight, total, total * c.inv, words.size, WORD_K, WORD_PRIOR)
        c.app?.let { p = Interpolation.blend(p, it.words[lower]?.w ?: 0.0, it.total, it.total * c.inv, it.words.size, APP_WORD_K, WORD_PRIOR) }
        if (c.typed != null || c.seeded != null) {
            val uses = (c.typed?.map?.get(lower)?.w ?: 0.0) + (c.seeded?.map?.get(lower)?.w ?: 0.0)
            val sum = (c.typed?.sum ?: 0.0) + (c.seeded?.sum ?: 0.0)
            p = Interpolation.blend(p, uses, sum, sum * c.inv, (c.typed?.map?.size ?: 0) + (c.seeded?.map?.size ?: 0), PAIR_K, PAIR_PRIOR)
        }
        c.appPair?.let { p = Interpolation.blend(p, it.map[lower]?.w ?: 0.0, it.sum, it.sum * c.inv, it.map.size, APP_PAIR_K, PAIR_PRIOR) }
        c.triple?.let { p = Interpolation.blend(p, it.map[lower]?.w ?: 0.0, it.sum, it.sum * c.inv, it.map.size, TRIPLE_K, TRIPLE_PRIOR) }
        return if (p > 0) -ln(p) else modelCost
    }

    // What the levels above the word read for one context: the same for every candidate of one decode, so
    // it is found once and kept until the next change (or another context).
    private class Context(val typed: Follows?, val seeded: Follows?, val triple: Follows?,
                          val app: AppHabits.App?, val appPair: Follows?, val inv: Double)
    private var context: Context? = null
    private var contextStamp = -1
    private var contextPrevious: String? = null
    private var contextBefore: String? = null
    private var contextApp: String? = null

    private fun context(previous: String?, beforePrevious: String?): Context {
        val name = app
        context?.let { if (contextStamp == stamp && contextPrevious == previous && contextBefore == beforePrevious && contextApp == name) return it }
        fade.tick()
        val first = previous?.lowercase()
        val second = beforePrevious?.lowercase()
        val habits = apps.of(name)
        return Context(
            typed = first?.let { typedPairs[it] }, seeded = first?.let { seededPairs[it] },
            triple = if (first != null && second != null) triples.follows(second, first) else null,
            app = habits, appPair = first?.let { habits?.pairs?.get(it) }, inv = 1 / fade.unit,
        ).also { context = it; contextStamp = stamp; contextPrevious = previous; contextBefore = beforePrevious; contextApp = name }
    }

    /** Your words that start with [prefix] and are longer, heaviest first (equally heavy ones alphabetically),
     * those used at least twice. */
    @Synchronized fun completions(prefix: String, limit: Int = 3): List<String> {
        lastCompletions?.let { if (it.stamp == stamp && it.limit == limit && it.prefix == prefix) return it.result }
        val lower = prefix.lowercase()
        // The words with this prefix are one run of [completable]; keep the [limit] heaviest, by insertion.
        val best = ArrayList<Seen>(limit + 1)
        for ((w, s) in completable.tailMap(lower, true)) {
            if (!w.startsWith(lower)) break
            if (w.length <= lower.length) continue
            val weight = s.weight
            var at = best.size
            while (at > 0 && lighter(best[at - 1].weight, weight)) at--
            if (at < limit) { best.add(at, s); if (best.size > limit) best.removeAt(limit) }
        }
        return best.map { it.spelling }.also { lastCompletions = Completions(stamp, prefix, limit, it) }
    }

    /** The words you most often write after [previous], at least twice each and not faded. */
    @Synchronized fun next(previous: String, limit: Int = 3): List<String> {
        fade.tick()
        val first = previous.lowercase()
        val typed = typedPairs[first]; val seeded = seededPairs[first]
        if (typed == null && seeded == null) return emptyList()
        // The heaviest [limit], by insertion: a word has only a few followers, and this runs at every space.
        val best = arrayOfNulls<String>(limit); val weights = DoubleArray(limit); var size = 0
        fun offer(word: String, uses: Int, weight: Double) {
            if (uses < WORD_USES || fade.real(weight) < LIVE) return
            var at = size
            while (at > 0 && lighter(weights[at - 1], weight)) at--
            if (at >= limit) return
            for (j in minOf(size, limit - 1) downTo at + 1) { best[j] = best[j - 1]; weights[j] = weights[j - 1] }
            best[at] = word; weights[at] = weight
            if (size < limit) size++
        }
        typed?.map?.forEach { (w, u) -> val s = seeded?.map?.get(w); offer(w, u.n + (s?.n ?: 0), u.w + (s?.w ?: 0.0)) }
        seeded?.map?.forEach { (w, u) -> if (typed?.map?.containsKey(w) != true) offer(w, u.n, u.w) }
        return List(size) { spelled(best[it]!!) }
    }

    /** The words you most often write after [first] then [second], then those after [second] alone, at
     * least twice each; with a [prefix], the ones that complete it. */
    @Synchronized fun next(first: String?, second: String, prefix: String = "", limit: Int = 3): List<String> {
        fade.tick()
        val lowerSecond = second.lowercase()
        val own = first?.let { triples.next(it.lowercase(), lowerSecond, prefix.lowercase(), limit, WORD_USES, LIVE, fade::real) }.orEmpty()
        if (own.size >= limit) return own.map(::spelled)
        val pairs = if (prefix.isEmpty()) next(second, limit) else emptyList()
        return (own.map(::spelled) + pairs).distinct().take(limit)
    }
    private fun spelled(lower: String) = words[lower]?.spelling ?: lower

    /** One line per word, pair, triple, app and address, tab-separated, after the `h time` the weights are
     * as of: `w spelling typed seeded typedWeight seededWeight`, `p first second typed weight`,
     * `s first second seeded weight`, `t first second third typed weight`, `e address uses`, `a app last`,
     * `A app word weight` and `B app first second weight`; weights in hundredths of a fresh use, so the
     * faded ones round to nothing and aren't written. */
    @Synchronized fun write(): String {
        fade.tick()
        // Sized up front: a full store is a couple of megabytes, and growing a builder copies it each time.
        val out = StringBuilder(words.size * 32 + typedAddresses.size * 40 + (typedPairCount + seededPairs.size) * 36 + triples.size * 40 + 64)
        out.append("h\t").append(fade.time).append('\n')
        val header = out.length
        for (s in words.values) {
            val typed = hundredths(s.tw); val seeded = hundredths(s.sw)
            if (typed + seeded <= 0) continue
            out.append("w\t").append(s.spelling).append('\t').append(s.typed).append('\t').append(s.seeded).append('\t').append(typed).append('\t').append(seeded).append('\n')
        }
        for (a in typedAddresses.values) out.append("e\t").append(a.text).append('\t').append(a.uses).append('\n')
        appendPairs(out, 'p', typedPairs)
        appendPairs(out, 's', seededPairs)
        triples.write(out, fade::real)
        apps.write(out, fade::real)
        return if (out.length == header) "" else out.toString()
    }
    private fun hundredths(weight: Double) = Math.round(fade.real(weight) * 100)
    private fun appendPairs(out: StringBuilder, tag: Char, pairs: HashMap<String, Follows>) {
        for ((first, follows) in pairs) for ((second, use) in follows.map) {
            val weight = hundredths(use.w)
            if (weight > 0) out.append(tag).append('\t').append(first).append('\t').append(second).append('\t').append(use.n).append('\t').append(weight).append('\n')
        }
    }

    /** What [write] wrote, added to what's here; a line it can't read is skipped. A file from before weights
     * has bare counts and no time: its uses count as made now. */
    @Synchronized fun read(text: String) {
        fade.tick()
        var savedAt = fade.time
        // Lines end at \n, \r\n or \r, as lineSequence reads them, split at their tabs in place: a full file
        // is some ninety thousand lines, and a list per line made it most of a second on a phone.
        var start = 0
        while (start < text.length) {
            var end = start
            while (end < text.length && text[end] != '\n' && text[end] != '\r') end++
            savedAt = readLine(text, start, end, savedAt)
            start = if (text.startsWith("\r\n", end)) end + 2 else end + 1
        }
        reindex()
        revision++
    }

    // Returns the time the weights read from here on are as of.
    private fun readLine(text: String, start: Int, end: Int, savedAt: Long): Long {
        val cut = IntArray(6); var tabs = 0
        for (i in start until end) if (text[i] == '\t') { if (tabs == cut.size) return savedAt; cut[tabs++] = i }
        if (tabs < 1 || cut[0] - start != 1) return savedAt
        fun field(i: Int) = text.substring(if (i == 0) start else cut[i - 1] + 1, if (i == tabs) end else cut[i])
        // A weight: hundredths of a fresh use as written. A file from before weights has bare counts instead.
        fun weight(i: Int): Double? = field(i).toLongOrNull()?.let { fade.stored(it / 100.0, savedAt) }
        fun bare(count: Int) = fade.stored(count.toDouble(), fade.time)
        fun alive(weight: Double) = fade.real(weight) >= FADED
        when (text[start]) {
            'h' -> if (tabs == 1) return field(1).toLongOrNull()?.coerceAtMost(fade.time) ?: savedAt
            'w' -> if (tabs == 3 || tabs == 5) {
                val typed = field(2).toIntOrNull() ?: return savedAt; val seeded = field(3).toIntOrNull() ?: return savedAt
                val spelling = field(1)
                val lower = spelling.lowercase()
                if (!learnable(lower) || typed < 0 || seeded < 0) return savedAt
                val tw = if (tabs == 3) bare(typed) else weight(4) ?: return savedAt
                val sw = if (tabs == 3) bare(seeded) else weight(5) ?: return savedAt
                if (!alive(tw + sw)) return savedAt
                val seen = entry(lower, spelling)
                seen.typed += typed; seen.seeded += seeded; seen.tw += tw; seen.sw += sw; total += tw + sw
            }
            'p', 's' -> if (tabs == 3 || tabs == 4) {
                val n = field(3).toIntOrNull()?.takeIf { it > 0 } ?: return savedAt
                val w = if (tabs == 3) bare(n) else weight(4)?.takeIf(::alive) ?: return savedAt
                if (text[start] == 'p') {
                    if (typedPairs.getOrPut(field(1)) { Follows() }.add(field(2), n, w)) typedPairCount++
                } else seededPairs.getOrPut(field(1)) { Follows() }.add(field(2), n, w)
            }
            't' -> if (tabs == 4 || tabs == 5) {
                val first = field(1); val second = field(2); val word = field(3)
                val n = field(4).toIntOrNull()?.takeIf { it > 0 } ?: return savedAt
                val w = if (tabs == 4) bare(n) else weight(5)?.takeIf(::alive) ?: return savedAt
                if (learnable(first.lowercase()) && learnable(second.lowercase()) && learnable(word.lowercase())) triples.learn(first, second, word, n, w)
            }
            'e' -> if (tabs == 2) {
                val uses = field(2).toIntOrNull()?.takeIf { it > 0 } ?: return savedAt
                val address = field(1)
                if (!EmailAddresses.valid(address)) return savedAt
                typedAddresses.getOrPut(address.lowercase()) { Address(address, 0) }.uses += uses
            }
            'a' -> if (tabs == 2) field(2).toLongOrNull()?.let { apps.readApp(field(1), it) }
            'A' -> if (tabs == 3) weight(3)?.takeIf(::alive)?.let { apps.readWord(field(1), field(2), it) }
            'B' -> if (tabs == 4) weight(4)?.takeIf(::alive)?.let { apps.readPair(field(1), field(2), field(3), it) }
        }
        return savedAt
    }

    // The faintest go first once the lists are full: one-off words and pairs, then the rest.
    private fun trim() {
        if (words.size > MAX_WORDS) {
            val drop = lightest(words, words.size - MAX_WORDS * 9 / 10) { it.weight }
            for (w in drop) { total -= words.getValue(w).weight; forget(w) }
            stamp++
            for (f in typedPairs.values) f.retain { it in words }
            typedPairs.entries.removeAll { it.key !in words || it.value.map.isEmpty() }
            typedPairCount = typedPairs.values.sumOf { it.map.size }
        }
        if (typedPairCount > MAX_PAIRS) {
            val weights = DoubleArray(typedPairCount); var i = 0
            for (f in typedPairs.values) for (u in f.map.values) weights[i++] = u.w
            weights.sort()
            val floor = weights[typedPairCount - MAX_PAIRS * 9 / 10 - 1]
            for (f in typedPairs.values) f.dropUpTo(floor)
            typedPairs.values.removeAll { it.map.isEmpty() }
            typedPairCount = typedPairs.values.sumOf { it.map.size }
        }
    }

    /** The [drop] keys of [map] with the lightest [weight], the earliest in its order among equal ones: the
     * ones sorting by weight (stably) would drop first, found from the sorted weights alone instead of by
     * sorting every entry, which was tens of milliseconds on a full store. */
    private inline fun <V> lightest(map: HashMap<String, V>, drop: Int, weight: (V) -> Double): List<String> {
        if (drop <= 0) return emptyList()
        val sorted = DoubleArray(map.size); var i = 0
        for (v in map.values) sorted[i++] = weight(v)
        sorted.sort()
        val floor = sorted[minOf(drop, sorted.size) - 1]
        var ties = drop - sorted.count { it < floor }
        val out = ArrayList<String>(drop)
        for ((k, v) in map) {
            val w = weight(v)
            if (w < floor) out.add(k) else if (w == floor && ties > 0) { out.add(k); ties-- }
        }
        return out
    }

    // Weights have grown past what a double keeps well: start the scale over at now.
    private fun rebase() {
        val factor = fade.rebase()
        for (s in words.values) { s.tw *= factor; s.sw *= factor }
        total *= factor
        for (f in typedPairs.values) f.scale(factor)
        for (f in seededPairs.values) f.scale(factor)
        triples.scale(factor); apps.scale(factor)
        stamp++
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

    companion object {
        /** The saved copy, in the app's files folder. */
        const val FILE = "learned-words.tsv"
        /** Uses before a word the model lacks counts as a word: one use may be a slip. */
        const val WORD_USES = 2
        private const val MAX_WORDS = 20_000
        private const val MAX_PAIRS = 50_000
        private const val MAX_ADDRESSES = 1_000
        private const val MAX_KEYS = 4_096
        /** Uses, and times as many as the word itself, before your spelling of a word takes its place. */
        const val PREFERRED_USES = 3
        const val PREFERRED_SHARE = 4
        // What a pair or a triple must still be worth, in fresh uses, to be offered, and what any count
        // must be worth to be kept at all.
        private const val LIVE = 0.5
        private const val FADED = 0.01
        // Each level's blend ([Interpolation.blend]): K, how many different followers outweigh a use, and the
        // prior, the model's own worth in uses. A word's share of all you write needs about a thousand
        // words to mean anything; a word's followers only a handful.
        private const val WORD_K = 1.0
        private const val APP_WORD_K = 2.0
        private const val PAIR_K = 1.0
        private const val APP_PAIR_K = 2.0
        private const val TRIPLE_K = 1.0
        private const val WORD_PRIOR = 1000.0
        private const val PAIR_PRIOR = 5.0
        private const val TRIPLE_PRIOR = 3.0

        /** Whether [a] weighs less than [b] by more than rounding: uses in one tick are equal. */
        fun lighter(a: Double, b: Double) = a < b * (1 - 1e-6)
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
