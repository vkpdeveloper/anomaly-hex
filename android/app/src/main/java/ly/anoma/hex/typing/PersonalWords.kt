package ly.anoma.hex.typing

import android.content.ContentResolver
import android.provider.UserDictionary

/** A word list sorted by letter codes, searched letter by letter: the model's, or your own. */
internal interface Lexicon {
    val size: Int
    fun length(word: Int): Int
    /** The words in [lo, hi), which share their first [depth] letters, whose next letter is [code],
     * as (first shl 32) or end; 0 when none. */
    fun child(lo: Int, hi: Int, depth: Int, code: Int): Long
}

/** Your own words and text shortcuts from Android's personal dictionary (`UserDictionary.Words`),
 * as iOS reads `UILexicon`: the decoder reads taps as them (a word you added is a word, scored as a
 * moderately common one), the strip completes them, a word typed in lowercase takes the capitals you
 * gave it ("github" -> "GitHub") unless it is also an ordinary word ("will"), and a shortcut typed
 * offers its phrase ([shortcut]). [learned] are those of [words] that are yours only because you
 * wrote them ([LearnedWords.yours]): read as words like the rest, but not [added], so the strip ranks
 * them by how much you use them. Words in the model's alphabet only; nothing is written. */
internal class PersonalWords(private val model: TypingModel, words: Collection<String>, shortcuts: Map<String, String>,
                             hidden: (String) -> Boolean = { false }, learned: Collection<String> = emptyList()) : Lexicon {
    private val codes: Array<ByteArray>
    private val texts: Array<String>
    private val lookup = HashMap<String, Int>()
    // Lowercase shortcut -> phrase as stored.
    private val shortcuts = shortcuts.mapKeys { it.key.lowercase() }
    private val learned = learned.mapTo(HashSet()) { it.lowercase() }

    init {
        val byCodes = LinkedHashMap<String, Pair<ByteArray, String>>()
        for (word in words) {
            val lower = word.lowercase()
            if (hidden(lower)) continue
            if (lower.length !in 2..TypingWord.MAX_LENGTH || lower.any { !it.isLetter() && it != '\'' }) continue
            val encoded = ByteArray(lower.length)
            var ok = true
            for ((i, c) in lower.withIndex()) { val code = model.code(c); if (code == 0) { ok = false; break }; encoded[i] = code.toByte() }
            if (ok) byCodes.putIfAbsent(lower, encoded to word)
        }
        val sorted = byCodes.values.sortedWith { a, b -> compare(a.first, b.first) }
        codes = Array(sorted.size) { sorted[it].first }
        texts = Array(sorted.size) { sorted[it].second }
        for ((i, t) in texts.withIndex()) lookup[t.lowercase()] = i
    }

    val isEmpty get() = codes.isEmpty() && shortcuts.isEmpty()
    override val size get() = codes.size
    override fun length(word: Int) = codes[word].size
    private fun byte(word: Int, depth: Int) = if (depth < codes[word].size) codes[word][depth].toInt() and 255 else -1
    override fun child(lo: Int, hi: Int, depth: Int, code: Int): Long {
        var a = lo; var b = hi
        while (a < b) { val m = (a + b) ushr 1; if (byte(m, depth) < code) a = m + 1 else b = m }
        if (a == hi || byte(a, depth) != code) return 0
        var c = a + 1; var d = hi
        while (c < d) { val m = (c + d) ushr 1; if (byte(m, depth) <= code) c = m + 1 else d = m }
        return (a.toLong() shl 32) or c.toLong()
    }
    /** Your word with these letters, or -1. */
    fun index(spelling: String) = lookup[spelling.lowercase()] ?: -1
    /** Your spelling of a word, capitals and all. */
    fun text(word: Int) = texts[word]
    /** Whether `spelling` is one of your words or a shortcut's phrase, in any case. */
    fun owns(spelling: String) = index(spelling) >= 0 || shortcuts.values.any { it.equals(spelling, ignoreCase = true) }

    /** Your words that start with `prefix` and are longer, in your spelling. */
    fun completions(prefix: String): List<String> {
        last?.let { if (it.first == prefix) return it.second }
        // The words sharing the prefix's letters are one run of the sorted list.
        var lo = 0; var hi = codes.size
        for ((depth, c) in prefix.lowercase().withIndex()) {
            val code = model.code(c)
            val run = if (code == 0 || lo >= hi) 0L else child(lo, hi, depth, code)
            if (run == 0L) { lo = 0; hi = 0; break }
            lo = (run ushr 32).toInt(); hi = run.toInt()
        }
        val found = ArrayList<String>()
        for (i in lo until hi) if (texts[i].length > prefix.length) found.add(texts[i])
        // The strip asks twice per keystroke and again at its pause for the same prefix.
        return found.sortedBy { it.length }.also { last = prefix to it }
    }
    @Volatile private var last: Pair<String, List<String>>? = null
    /** The [completions] you added yourself (the personal dictionary, a contact, the computer's words), not [learned] ones. */
    fun added(prefix: String) = completions(prefix).let { all -> if (learned.isEmpty()) all else all.filter { it.lowercase() !in learned } }

    /** Your phrase for a shortcut typed as [typed], cased as Apple's keyboard writes it (#2153, #2173):
     * a shortcut typed with a leading capital, but not all capitals, capitalises the phrase's first
     * letter; stored capitals always stay. */
    fun shortcut(typed: String): String? {
        val stored = shortcuts[typed.lowercase()] ?: return null
        val letters = typed.filter { it.isLetter() }
        val leading = typed.first().isUpperCase() && (letters.length < 2 || letters != letters.uppercase())
        return if (leading && stored.first().isLowerCase()) stored.replaceFirstChar { it.uppercase() } else stored
    }

    companion object {
        private fun compare(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val d = (a[i].toInt() and 255) - (b[i].toInt() and 255)
                if (d != 0) return d
            }
            return a.size - b.size
        }
        private const val LIMIT = 5_000

        /** Whether a personal dictionary entry's locale ("en_US", "nb_NO", none for every language)
         * applies to the model's language; Bokmål also takes "no", and Hinglish English's and Hindi's. */
        fun applies(locale: String?, language: String) = locale.isNullOrEmpty() ||
            locale.substringBefore('_').lowercase().let {
                it == language || (language == "nb" && it == "no") || (language == "hien" && (it == "en" || it == "hi"))
            }

        /** The personal dictionary's words and shortcuts for the model's language (or every language),
         * with the words you've written that the model lacks ([learned], `LearnedWords.yours`) and the
         * paired computer's [replacements] and your [contactNames] ([of]).
         * Android lets only an enabled input method or spell checker read it, with no permission, and
         * answers anyone else with nothing; any failure reads as nothing. Read it off the main thread. */
        fun read(resolver: ContentResolver, model: TypingModel, learned: Collection<String> = emptyList(),
                 replacements: Replacements = Replacements.None, contactNames: Collection<String> = emptyList(),
                 forgotten: Forgotten? = null): PersonalWords {
            val dictionary = ArrayList<Pair<String, String>>()
            try {
                resolver.query(UserDictionary.Words.CONTENT_URI,
                    arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.SHORTCUT, UserDictionary.Words.LOCALE),
                    null, null, null)?.use { cursor ->
                    while (dictionary.size < LIMIT && cursor.moveToNext()) {
                        if (!applies(cursor.getString(2), model.language)) continue
                        val word = cursor.getString(0)?.trim().orEmpty()
                        if (word.isNotEmpty()) dictionary.add(word to cursor.getString(1)?.trim().orEmpty())
                    }
                }
            } catch (_: RuntimeException) {
                dictionary.clear()
            }
            return of(model, dictionary, learned, replacements, contactNames, forgotten)
        }

        /** Your words from every source: the personal dictionary's entries (a word and its shortcut, or ""
         * for none), at most [LIMIT] of them however many words you've written, then [learned], your
         * [contactNames] and the paired computer's [replacements]. A word in several keeps the first one's
         * capitals, and your own shortcut wins one the computer also has. A [forgotten] word is left out. */
        fun of(model: TypingModel, dictionary: List<Pair<String, String>>, learned: Collection<String> = emptyList(),
               replacements: Replacements = Replacements.None, contactNames: Collection<String> = emptyList(),
               forgotten: Forgotten? = null): PersonalWords {
            val words = ArrayList<String>()
            val shortcuts = HashMap<String, String>()
            for ((word, shortcut) in dictionary.take(LIMIT)) {
                if (shortcut.isNotEmpty() && !shortcut.equals(word, ignoreCase = true)) shortcuts[shortcut] = word
                else words.add(word)
            }
            val mine = shortcuts.keys.mapTo(HashSet()) { it.lowercase() }
            val added = (words + contactNames + replacements.words + shortcuts.values).mapTo(HashSet()) { it.lowercase() }
            return PersonalWords(model, words + learned + contactNames + replacements.words, replacements.shortcuts.filterKeys { it !in mine } + shortcuts,
                { forgotten?.contains(it) == true }, learned.filter { it.lowercase() !in added })
        }
    }
}
