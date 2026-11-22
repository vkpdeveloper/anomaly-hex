package ly.anoma.hex.typing

/** Emoji for words, for the strip: a layout's lists ([parse]: Unicode's English keywords, Hex's Hinglish
 * words), each emoji best first, behind the ones you picked for that word before ([Picks]). [shows]
 * leaves out emoji the phone's font can't draw. */
internal class EmojiWords(private val lists: List<Map<String, List<String>>>, private val shows: (String) -> Boolean = { true }) {
    /** [emoji] for [word], the form of the word that has them. */
    class Found(val word: String, val emoji: List<String>)

    /** The emoji for the first of [words] that has any, in a form the lists know ("cats" finds "cat"): your
     * picks first, most picked first, then the lists'. One you picked from the emoji panel for a word the
     * lists don't give it shows once you've picked it [PANEL_PICKS] times, so a one-off doesn't stick. */
    fun find(words: List<String>, picks: Picks, limit: Int = 2): Found? {
        for (typed in words) for (word in forms(typed.lowercase())) {
            val listed = lists.flatMap { it[word].orEmpty() }
            val mine = picks.of(word).filter { (emoji, count) -> count >= PANEL_PICKS || emoji in listed }.map { it.first }
            val all = (mine + listed).distinct().filter(shows)
            if (all.isNotEmpty()) return Found(word, all.take(limit))
        }
        return null
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
        private const val WORDS = 1_000
        private const val EMOJI_PER_WORD = 8

        /** A list asset: comments start with #, then one word per line, a tab, and its emoji separated by spaces. */
        fun parse(text: String): Map<String, List<String>> = text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") && '\t' in it }
            .associate { line -> line.substringBefore('\t') to line.substringAfter('\t').split(' ').filter { it.isNotEmpty() } }

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

        /** The word just before the cursor, with at most one space after it, as an emoji would follow it; null after anything else. */
        fun wordBefore(before: String): String? {
            val text = before.removeSuffix(" ")
            val word = text.takeLastWhile { it.isLetter() || it == '\'' || it == '’' }.trimStart('\'', '’')
            if (word.isEmpty() || word.length > TypingWord.MAX_LENGTH) return null
            if (text.getOrNull(text.length - word.length - 1)?.isLetterOrDigit() == true) return null
            return word.lowercase()
        }
    }
}
