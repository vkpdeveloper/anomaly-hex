package ly.anoma.hex.typing

/** Emoji for words: the strip's ([find]) and the emoji search's ([search]). [lists] are a layout's word
 * lists ([parse]: Unicode's English keywords, Hex's Hinglish and slang words), each emoji best first;
 * [searched] are every list the search reads, the layout's first; [names] are Unicode's names and
 * keywords for every emoji ([Named.parse]). Both put the ones you picked for a word first ([Picks]).
 * [suggests] is off for a layout whose words the lists don't know: it searches but offers none on the
 * strip. [shows] leaves out emoji the phone's font can't draw. */
internal class EmojiWords(
    private val lists: List<Map<String, List<String>>>,
    names: List<Named> = emptyList(),
    searched: List<Map<String, List<String>>> = lists,
    val suggests: Boolean = true,
    private val shows: (String) -> Boolean = { true },
) {
    /** [emoji] for [word], the form of the word that has them. */
    class Found(val word: String, val emoji: List<String>)

    /** An emoji with its English name's words ("red", "heart") and its other words, as Unicode gives them. */
    class Named(val emoji: String, val name: List<String>, val words: List<String>) {
        companion object {
            /** The names asset: comments start with #, then emoji, tab, name words, tab, other words. */
            fun parse(text: String): List<Named> = text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
                val parts = line.split('\t')
                fun words(i: Int) = parts.getOrNull(i).orEmpty().split(' ').filter { it.isNotEmpty() }
                Named(parts[0], words(1), words(2))
            }.toList()
        }
    }

    // Every word the search knows, each with its emoji and how well the word names them. The panel's
    // order (its index in [names]) breaks ties, so smileys come before rarer things.
    private class Hit(val emoji: String, val weight: Double, val order: Int)
    private val index = HashMap<String, MutableList<Hit>>()
    private val sorted: Array<String>
    /** Words of a name that few emoji share, which name one well enough to offer it on the strip. */
    private val strongNames = HashMap<String, MutableList<String>>()

    init {
        val order = HashMap<String, Int>()
        names.forEachIndexed { i, n -> order.putIfAbsent(n.emoji, i) }
        fun orderOf(emoji: String) = order[emoji] ?: names.size
        fun add(word: String, emoji: String, weight: Double) {
            val hits = index.getOrPut(word) { ArrayList(2) }
            val old = hits.indexOfFirst { it.emoji == emoji }
            if (old < 0) hits += Hit(emoji, weight, orderOf(emoji))
            else if (hits[old].weight < weight) hits[old] = Hit(emoji, weight, hits[old].order)
        }
        // Another language's lists count for less than the layout's own: on English, ":fir" is "fire"
        // before Hinglish "fir" (again), which still finds its emoji when nothing English does.
        searched.forEachIndexed { l, list ->
            val own = lists.any { it === list }
            for ((word, emoji) in list) emoji.forEachIndexed { i, e -> add(word, e, (if (own) LISTED - l * 0.5 else OTHER) - i * 0.4) }
        }
        val nameUses = HashMap<String, Int>()
        for (n in names) for (w in n.name) nameUses[w] = (nameUses[w] ?: 0) + 1
        for (n in names) {
            n.name.forEachIndexed { i, w -> add(w, n.emoji, NAMED - i * 0.2 - n.name.size * 0.1) }
            for (w in n.words) add(w, n.emoji, KEYWORD)
            if (n.name.size <= 2) for (w in n.name) if ((nameUses[w] ?: 0) <= STRONG_NAME_USES && w.length >= 3)
                strongNames.getOrPut(w) { ArrayList(1) } += n.emoji
        }
        sorted = index.keys.toTypedArray().also { it.sort() }
    }

    /** The emoji for the first of [words] that has any, in a form the lists know ("cats" finds "cat",
     * "lmaooo" "lmao"): your picks first, most picked first, then the lists', then an emoji the word
     * names on its own ("mango" 🥭, "india" 🇮🇳). One you picked from the emoji panel for a word the lists
     * don't give it shows once you've picked it [PANEL_PICKS] times, so a one-off doesn't stick.
     * Words of one letter find none here; the search finds them. */
    fun find(words: List<String>, picks: Picks, limit: Int = 2): Found? {
        if (!suggests) return null
        // A single letter ("k", "w") is a word only once it stands alone; on the strip it would be every
        // word's first key.
        for (typed in words) if (typed.length >= 2) for (word in variants(typed.lowercase())) {
            val listed = lists.flatMap { it[word].orEmpty() } + strongNames[word].orEmpty()
            val mine = picks.of(word).filter { (emoji, count) -> count >= PANEL_PICKS || emoji in listed }.map { it.first }
            val all = (mine + listed).distinct().filter(shows)
            if (all.isNotEmpty()) return Found(word, all.take(limit))
        }
        return null
    }

    /** The emoji search: the emoji for [query]'s words, best first. Each word finds the words that are it,
     * a form of it, a stretched spelling of it ("pyaarrr"), one slip from it ("hapy", from four letters),
     * or, for the last word, which it starts ("lau" finds "laugh"); a query of several words finds the emoji
     * all of them find ("red heart"). The emoji you picked for the query come first. */
    fun search(query: String, picks: Picks, limit: Int = SEARCH_LIMIT): List<String> {
        val words = queryWords(query)
        if (words.isEmpty()) return emptyList()
        var total: HashMap<String, Double>? = null
        val orders = HashMap<String, Int>()
        words.forEachIndexed { i, q ->
            val scores = scores(q, prefix = i == words.lastIndex, orders)
            total = total?.let { t -> HashMap<String, Double>().apply { for ((e, s) in t) scores[e]?.let { put(e, s + it) } } } ?: scores
        }
        val found = total.orEmpty().toMutableMap()
        val key = words.joinToString(" ")
        for ((emoji, count) in picks.of(key)) found[emoji] = (found[emoji] ?: 0.0) + PICKED + count
        return found.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { orders[it.key] ?: Int.MAX_VALUE })
            .map { it.key }.filter(shows).take(limit)
    }

    /** How well [q] finds each emoji: the best of the words it matches. */
    private fun scores(q: String, prefix: Boolean, orders: HashMap<String, Int>): HashMap<String, Double> {
        val scores = HashMap<String, Double>()
        fun take(word: String, factor: Double) {
            for (hit in index[word].orEmpty()) {
                val s = hit.weight * factor
                if (s > (scores[hit.emoji] ?: 0.0)) scores[hit.emoji] = s
                orders.putIfAbsent(hit.emoji, hit.order)
            }
        }
        val exact = variants(q)
        exact.forEachIndexed { i, v -> take(v, if (i == 0) 1.0 else FORM) }
        if (prefix && q.length >= 2) {
            var at = sorted.binarySearch(q).let { if (it < 0) -it - 1 else it + 1 }
            var seen = 0
            while (at < sorted.size && sorted[at].startsWith(q) && seen < PREFIX_WORDS) {
                // A shorter word is likelier the one meant: "lau" is "laugh" before "laundry".
                take(sorted[at], PREFIX * (0.7 + 0.3 * q.length / sorted[at].length))
                at++; seen++
            }
        }
        if (q.length >= SLIP_FROM) for (word in slips(q)) if (word !in exact) take(word, SLIP)
        return scores
    }

    /** The search's words that are one slip from [q]: a letter left out, added, changed or two swapped,
     * keeping its first letter, as a quick typo in the search box would be. */
    private fun slips(q: String): List<String> {
        val from = sorted.binarySearch(q.substring(0, 1)).let { if (it < 0) -it - 1 else it }
        val out = ArrayList<String>()
        var i = from
        while (i < sorted.size && sorted[i][0] == q[0]) {
            val w = sorted[i++]
            if (kotlin.math.abs(w.length - q.length) <= 1 && w.length >= SLIP_FROM - 1 && oneSlip(q, w)) out += w
        }
        return out
    }

    /** The emoji you picked for each word, as counts: the words used longest ago go first once there are too many. */
    class Picks {
        private val words = LinkedHashMap<String, LinkedHashMap<String, Int>>()

        /** [word]'s emoji, most picked first and, among equals, the latest first. */
        fun of(word: String): List<Pair<String, Int>> =
            words[word]?.entries?.reversed()?.sortedByDescending { it.value }?.map { it.key to it.value }.orEmpty()

        fun pick(word: String, emoji: String) {
            val counts = words.remove(word) ?: LinkedHashMap()
            counts[emoji] = (counts.remove(emoji) ?: 0) + 1
            while (counts.size > EMOJI_PER_WORD) counts.remove(counts.minBy { it.value }.key)
            words[word] = counts
            while (words.size > WORDS) words.remove(words.keys.first())
        }

        fun clear() = words.clear()

        /** One word per line: the word, a tab, then each emoji and its count, separated by spaces. */
        fun write() = words.entries.joinToString("\n") { (word, counts) ->
            word + "\t" + counts.entries.joinToString(" ") { "${it.key} ${it.value}" }
        }

        companion object {
            fun read(text: String) = Picks().apply {
                for (line in text.lineSequence()) {
                    val word = line.substringBefore('\t', "").takeIf { it.isNotEmpty() } ?: continue
                    val counts = LinkedHashMap<String, Int>()
                    for (pair in line.substringAfter('\t').split(' ').chunked(2)) {
                        val count = pair.getOrNull(1)?.toIntOrNull() ?: continue
                        if (pair[0].isNotEmpty() && count > 0) counts[pair[0]] = count
                    }
                    if (counts.isNotEmpty()) words[word] = counts
                }
            }
        }
    }

    companion object {
        const val PANEL_PICKS = 2
        /** The most emoji a search offers. */
        const val SEARCH_LIMIT = 40
        private const val WORDS = 1_000
        private const val EMOJI_PER_WORD = 8
        // How well a word finds an emoji: a list's word (Hex's picks and slang, Unicode's keywords) best,
        // then a word of its name, then its other keywords; a form, a prefix or a slip of the word less.
        private const val LISTED = 10.0
        private const val NAMED = 7.0
        private const val KEYWORD = 4.0
        private const val OTHER = 4.0
        private const val FORM = 0.9
        private const val PREFIX = 0.55
        private const val SLIP = 0.4
        private const val PICKED = 20.0
        private const val SLIP_FROM = 4
        private const val PREFIX_WORDS = 60
        private const val STRONG_NAME_USES = 3
        private val QUERY_WORD = Regex("""[a-z0-9]+(?:'[a-z]+)?""")

        /** A list asset: comments start with #, then one word per line, a tab, and its emoji separated by spaces. */
        fun parse(text: String): Map<String, List<String>> = text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") && '\t' in it }
            .associate { line -> line.substringBefore('\t') to line.substringAfter('\t').split(' ').filter { it.isNotEmpty() } }

        /** A search's words: lowercase, without marks, an underscore as a space (":red_heart"). */
        fun queryWords(query: String) = QUERY_WORD.findAll(query.lowercase().replace('’', '\'').replace('_', ' ')).map { it.value }.toList()

        /** The word, then the forms a plural or possessive comes from: "wishes" is "wish", "dogs" "dog". */
        fun forms(word: String): List<String> = buildList {
            add(word)
            if (word.length <= 3) return@buildList
            when {
                word.endsWith("'s") || word.endsWith("’s") -> add(word.dropLast(2))
                word.endsWith("es") -> { add(word.dropLast(2)); add(word.dropLast(1)) }
                word.endsWith("s") && !word.endsWith("ss") -> add(word.dropLast(1))
            }
        }

        /** [forms], then the same with letters held for emphasis cut back ("lmaooo" "lmao", "pyaarrr"
         * "pyaar" and "pyar", "sooo" "so"): a run of three or more as two, then as one. */
        fun variants(word: String): List<String> {
            val out = LinkedHashSet(forms(word))
            if (Regex("""(.)\1\1""").containsMatchIn(word)) for (cut in listOf(Regex("""(.)\1{2,}""").replace(word, "$1$1"),
                    Regex("""(.)\1{2,}""").replace(word, "$1"), Regex("""(.)\1+""").replace(word, "$1"))) out += forms(cut)
            return out.toList()
        }

        /** Whether [a] and [b] differ by one letter left out, added or changed, or two neighbours swapped. */
        fun oneSlip(a: String, b: String): Boolean {
            if (a == b) return false
            if (a.length == b.length) {
                val diff = a.indices.filter { a[it] != b[it] }
                return diff.size == 1 || diff.size == 2 && diff[1] == diff[0] + 1 && a[diff[0]] == b[diff[1]] && a[diff[1]] == b[diff[0]]
            }
            val (short, long) = if (a.length < b.length) a to b else b to a
            if (long.length != short.length + 1) return false
            var i = 0
            while (i < short.length && short[i] == long[i]) i++
            return short.regionMatches(i, long, i + 1, short.length - i)
        }

        /** The word just before the cursor, with at most one space after it, as an emoji would follow it; null after anything else. */
        fun wordBefore(before: String): String? {
            val text = before.removeSuffix(" ")
            val word = text.takeLastWhile { it.isLetter() || it == '\'' || it == '’' }.trimStart('\'', '’')
            if (word.isEmpty() || word.length > TypingWord.MAX_LENGTH) return null
            if (text.getOrNull(text.length - word.length - 1)?.isLetterOrDigit() == true) return null
            return word.lowercase()
        }

        /** Whether [text] ends in an emoji ("😀", "👍🏽", "❤️", "1️⃣", a flag), which ends a word as a space
         * does and can start the next one right after it. */
        fun endsWithEmoji(text: String): Boolean {
            if (text.isEmpty()) return false
            val cp = text.codePointBefore(text.length)
            // The emoji style selector, a keycap, a flag's closing tag, the emoji planes, and the older
            // symbols the emoji panel has (☕ ⚡ ✅ ⭐), which have no selector after them.
            return cp == 0xFE0F || cp == 0x20E3 || cp == 0xE007F || cp in 0x1F000..0x1FAFF ||
                cp in 0x2300..0x2BFF && Character.getType(cp) == Character.OTHER_SYMBOL.toInt()
        }

        /** The search typed after a colon right before the cursor (":fire", ":red_heart"), as Slack and
         * Discord read one: the colon starts the text or follows a space, and two letters or more follow
         * it, so ":)", ":p", "10:30" and "https:" stay as they are. Null when there's none. */
        fun colonQuery(before: String): String? {
            val m = COLON.find(before) ?: return null
            return m.groupValues[1]
        }
        private val COLON = Regex("""(?:^|\s):([A-Za-z][A-Za-z0-9'_]{1,30})$""")
    }
}
