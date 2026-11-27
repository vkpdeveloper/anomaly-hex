package ly.anoma.hex.fix

import kotlin.math.exp
import kotlin.math.ln

/** One change the fix button makes: [original] at [from] until [to] in the checked text becomes [replacement]. */
internal data class Fix(val from: Int, val to: Int, val original: String, val replacement: String)

/** The model's scores for a context, so tests can stand in for [WrongWordModel]. */
internal fun interface Scorer {
    fun run(left: IntArray, right: IntArray, rows: List<IntArray>): FloatArray
}

/** A port of tinyfix 0.1.1 (MIT, Caleb Fahlgren; github.com/cfahlgren1/tinyfix) for English text, with its
 * "balanced" settings. It finds misspellings from an 80,000-word list ("recieve"), and real words in the
 * wrong place ("form" for "from") by asking the wrong-word model which nearby word fits the words around
 * it best. Not thread safe: one fix at a time. */
internal class Tinyfix(scorer: Scorer, vocabulary: List<String>, confusions: List<String>, words: List<Pair<String, Double>>) {
    private val wrongWord = WrongWord(scorer, vocabulary, confusions)
    private val spelling = Spelling(words, wrongWord)

    companion object {
        /** Sure enough to change a misspelled word, with the model helping to choose. */
        private const val SPELLING_MIN_CONFIDENCE = 0.55
        private const val CONTEXT_BEFORE = 600
        private const val CONTEXT_AFTER = 200
        private val WORD = Regex("[A-Za-z']+")
        /** A fenced code block; an unclosed one runs to the end of the text. */
        private val FENCED_CODE = Regex("```[\\s\\S]*?(?:```|\\z)")
        /** Inline code; an unclosed one runs to the end of its line. */
        private val INLINE_CODE = Regex("(?md)`[^`\\n]*(?:`|$)")
        /** Parts of a web address, an email, a path, a tag or an identifier: not prose. */
        private val NOT_PROSE = Regex("[@/\\\\_#0-9]|\\.[A-Za-z0-9_]")

        /** The text with [fixes] made. */
        fun apply(text: String, fixes: List<Fix>): String {
            val out = StringBuilder(text)
            for (fix in fixes.sortedByDescending { it.from }) {
                check(text.substring(fix.from, fix.to) == fix.original) { "fix doesn't match the text" }
                out.replace(fix.from, fix.to, fix.replacement)
            }
            return out.toString()
        }

        /** The words.txt list: a word and its count on each line. */
        fun parseWords(text: String): List<Pair<String, Double>> = text.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) parts[0] to parts[1].toDouble() else null
        }.toList()

        fun lines(text: String) = text.split("\n").filter { it.isNotEmpty() }
    }

    /** Never change this word again, for example after the fix that changed it was undone. */
    fun ignore(word: String) = spelling.rememberUndo(word)

    /** The fixes for [text], in order and never overlapping. Words [keep] holds are never changed. */
    fun check(text: String, keep: (String) -> Boolean = { false }): List<Fix> {
        val code = (FENCED_CODE.findAll(text) + INLINE_CODE.findAll(text)).map { it.range.first..it.range.last + 1 }.toList()
        val fixes = ArrayList<Fix>()
        // The text up to the word being checked, with the fixes found so far already made: a later word
        // is judged against corrected text, as it would be while typing.
        var fixedSoFar = ""
        var consumed = 0
        for (match in WORD.findAll(text)) {
            val original = match.value
            val from = match.range.first
            val to = match.range.last + 1
            if (code.any { from < it.last && to > it.first }) continue
            if (keep(original) || spelling.wasUndone(original) || NOT_PROSE.containsMatchIn(tokenAround(text, from, to))) continue
            val before = (fixedSoFar + text.substring(consumed, from)).takeLast(CONTEXT_BEFORE)
            val replacement = spelling.correct(before, original) ?: run {
                val lineEnd = text.indexOf('\n', to).let { if (it == -1) text.length else it }
                wrongWord.check(before, original, text.substring(to, minOf(lineEnd, to + CONTEXT_AFTER)))
            }
            if (replacement == null || replacement == original) continue
            fixes += Fix(from, to, original, replacement)
            fixedSoFar = (fixedSoFar + text.substring(consumed, from)).takeLast(CONTEXT_BEFORE) + replacement
            consumed = to
        }
        return fixes
    }

    /** The run of non-space characters around a word: "john@form.com" for the "form" inside it. */
    private fun tokenAround(text: String, from: Int, to: Int): String {
        var start = from
        while (start > 0 && !Rules.isSpace(text[start - 1])) start--
        var end = to
        while (end < text.length && !Rules.isSpace(text[end])) end++
        return text.substring(start, end)
    }

    /** Misspellings: words not on the list, matched to listed words a slip or two away (tinyfix's `Fixer`). */
    private class Spelling(private val words: List<Pair<String, Double>>, private val scorer: WrongWord) {
        private val known = words.mapTo(HashSet()) { it.first }
        private val trie = WordTrie(words.map { it.first })
        private val totalCount = words.sumOf { it.second }
        private val undone = HashSet<String>()

        private class Candidate(val word: String, val cost: Double, val logFrequency: Double, var contextGain: Double = 0.0)

        fun rememberUndo(word: String) { undone += word.lowercase(); known += word.lowercase() }
        fun wasUndone(word: String) = word.lowercase() in undone

        private fun isKnown(word: String): Boolean {
            val lower = word.lowercase()
            return lower in known || Rules.isNewerWord(lower) || Rules.isBritishSpelling(lower) { it in known }
        }

        fun correct(textBefore: String, word: String): String? {
            val ruled = Rules.applyFixedRule(word)
            if (ruled != null && ruled != word && !wasUndone(word)) return ruled
            if (!Rules.isCheckable(word, textBefore) || isKnown(word)) return null
            val lower = word.lowercase()
            val ranked = trie.search(lower, if (lower.length <= 4) 1 else 2).map { index ->
                val (candidate, count) = words[index]
                Candidate(candidate, TypoCost.of(lower, candidate), ln(count / totalCount))
            }.sortedByDescending { it.logFrequency - it.cost }.take(MAX_CANDIDATES)
            if (ranked.isEmpty()) return null
            val scores = scorer.score(textBefore, listOf(word) + ranked.map { Rules.matchCase(word, it.word) })
            ranked.forEachIndexed { i, candidate -> candidate.contextGain = scores[i + 1] - scores[0] }
            return pick(ranked)?.let { Rules.matchCase(word, it) }
        }

        /** The best candidate, if it wins enough of the chance against the others and against keeping the word. */
        private fun pick(candidates: List<Candidate>): String? {
            val scores = candidates.map { -it.cost + FREQUENCY * it.logFrequency + CONTEXT * it.contextGain }
            var best = 0
            for (i in scores.indices) if (scores[i] > scores[best]) best = i
            val total = scores.sumOf { exp(it - scores[best]) } + exp(-KEEP_COST - scores[best])
            return if (1 / total >= SPELLING_MIN_CONFIDENCE) candidates[best].word else null
        }

        companion object {
            const val MAX_CANDIDATES = 12
            const val FREQUENCY = 0.5
            const val CONTEXT = 0.25
            const val KEEP_COST = 10.0
        }
    }
}

/** Correctly spelled words that don't fit the words around them (tinyfix's `WrongWordChecker`). */
internal class WrongWord(private val model: Scorer, private val vocabulary: List<String>, confusions: List<String>) {
    private companion object {
        const val LEFT = WrongWordModel.LEFT
        const val RIGHT = WrongWordModel.RIGHT
        const val MAX_NEIGHBORS = 8
        const val CANDIDATES = WrongWordModel.CANDIDATES
        const val PAD = 0
        const val UNKNOWN = 1
        const val NUMBER = 2
        const val START = 3
        const val NAME = 4
        const val SPECIAL_TOKENS = 5
        /** Known mix-ups like "their" and "there" are likely even when they're far apart on the keyboard. */
        const val MAX_KNOWN_MIXUP_COST = 1.5
        const val KNOWN_MIXUP_BONUS = 1.0
        /** For a word with known mix-ups, any other nearby word must clear a higher bar. */
        const val OTHER_WORD_PENALTY = 1.0
        /** A word differing only by an ending ("examples", "example") must clear a higher bar. */
        const val INFLECTION_PENALTY = 1.0
        /** tinyfix's "balanced" setting: a nearby word must win by this much, less its slip's cost. */
        const val THRESHOLD = 3.0
        const val COST_WEIGHT = 1.5
        val WORD = Regex("[a-z]+(?:'[a-z]+)?")
        val LISTED_WORD = Regex("[A-Za-z]+(?:'[A-Za-z]+)?")
    }

    private val ids = HashMap<String, Int>()
    private val targets = ArrayList<Int>()
    private val trie: WordTrie
    private val neighbors = HashMap<Int, List<Int>>()
    private val knownMixups = HashMap<Int, List<Int>>()

    init {
        vocabulary.forEachIndexed { index, token ->
            ids[token] = index
            if (index >= SPECIAL_TOKENS && token.length >= 2 && WORD.matches(token)) targets += index
        }
        trie = WordTrie(targets.map { vocabulary[it] })
        for (line in confusions) {
            val tokens = line.split(" ").map { ids[it] }
            val typed = tokens.first() ?: continue
            knownMixups[typed] = tokens.drop(1).filterNotNull()
        }
    }

    private class Nearby(val word: String, val margin: Double, val cost: Double, val isKnownMixup: Boolean)

    private fun tokenId(token: String) = when {
        token == "<name>" -> NAME
        token.first() in '0'..'9' -> NUMBER
        else -> ids[token] ?: UNKNOWN
    }

    private fun nearbyIds(id: Int): List<Int> = neighbors.getOrPut(id) {
        val oneEdit = trie.search(vocabulary[id], 1).map { targets[it] }.filter { it != id }.sorted()
        LinkedHashSet<Int>().apply { addAll(knownMixups[id].orEmpty()); addAll(oneEdit) }.take(MAX_NEIGHBORS)
    }

    private fun compare(textBefore: String, word: String, textAfter: String, onlyKnownMixups: Boolean): List<Nearby>? {
        val lower = word.lowercase()
        val typed = ids[lower] ?: return null
        if (typed < SPECIAL_TOKENS) return null
        val nearby = if (onlyKnownMixups) knownMixups[typed].orEmpty() else nearbyIds(typed)
        if (nearby.isEmpty()) return null
        val scores = runModel(textBefore, textAfter, listOf(listOf(typed) + nearby))
        val mixups = knownMixups[typed].orEmpty()
        return nearby.mapIndexed { index, id ->
            val nearbyWord = vocabulary[id]
            val cost = TypoCost.of(lower, nearbyWord)
            val isKnownMixup = id in mixups
            Nearby(nearbyWord, (scores[index + 1] - scores[0]).toDouble(), if (isKnownMixup) minOf(cost, MAX_KNOWN_MIXUP_COST) else cost, isKnownMixup)
        }
    }

    /** How well each spelling candidate fits after [textBefore]; the first word is the misspelling itself. */
    fun score(textBefore: String, words: List<String>): DoubleArray {
        val wordIds = listOf(UNKNOWN) + words.drop(1).map { ids[it.lowercase()] ?: UNKNOWN }
        val rows = wordIds.chunked(MAX_NEIGHBORS).map { listOf(PAD) + it }
        val scores = runModel(textBefore, "", rows)
        return DoubleArray(wordIds.size) { scores[(it / MAX_NEIGHBORS) * CANDIDATES + it % MAX_NEIGHBORS + 1].toDouble() }
    }

    private fun runModel(textBefore: String, textAfter: String, rows: List<List<Int>>): FloatArray {
        val before = tokenize(textBefore, true).map(::tokenId)
        val left = if (before.size >= LEFT) before.takeLast(LEFT) else pad(listOf(START) + before, LEFT)
        val right = pad(tokenize(textAfter, false).take(RIGHT).map(::tokenId).reversed(), RIGHT)
        return model.run(left.toIntArray(), right.toIntArray(), rows.map { row -> IntArray(CANDIDATES) { row.getOrElse(it) { PAD } } })
    }

    private fun pad(ids: List<Int>, length: Int) = List(length - ids.size) { PAD } + ids

    /** Short words and words with an apostrophe are left to the list of known mix-ups ("to" and "too"). */
    private fun hasKnownMixups(word: String, textBefore: String): Boolean {
        val id = ids[word.lowercase()]
        if (id == null || id !in knownMixups || !LISTED_WORD.matches(word)) return false
        if (word.length > 1 && word == word.uppercase()) return false
        return word[0] == word[0].lowercaseChar() || Rules.isSentenceStart(textBefore)
    }

    private fun value(item: Nearby, typed: String, typedHasMixups: Boolean): Double {
        val value = item.margin - COST_WEIGHT * item.cost
        if (item.isKnownMixup) return value + KNOWN_MIXUP_BONUS
        var penalty = if (typedHasMixups) OTHER_WORD_PENALTY else 0.0
        if (differsOnlyInEnding(typed, item.word)) penalty += INFLECTION_PENALTY
        return value - penalty
    }

    /** A nearby word that fits the sentence clearly better than the one typed, with its casing. */
    fun check(textBefore: String, word: String, textAfter: String): String? {
        val onlyKnownMixups = when {
            Rules.isCheckable(word, textBefore) -> false
            hasKnownMixups(word, textBefore) -> true
            else -> return null
        }
        val nearby = compare(textBefore, word, textAfter, onlyKnownMixups) ?: return null
        val typedHasMixups = nearby.any { it.isKnownMixup }
        val best = nearby.map { it to value(it, word.lowercase(), typedHasMixups) }.sortedByDescending { it.second }.first()
        if (best.second < THRESHOLD) return null
        return Rules.matchCase(word, best.first.word)
    }
}

private val INFLECTION_ENDINGS = setOf("", "s", "es", "d", "ed", "ing", "r", "er", "ly")
private const val MIN_STEM = 3
private val SENTENCE_OPENERS = setOf(".", "!", "?", ":", "\"")

/** True for pairs like "services" and "service" or "continued" and "continues". */
internal fun differsOnlyInEnding(first: String, second: String): Boolean {
    var shared = 0
    while (shared < minOf(first.length, second.length) && first[shared] == second[shared]) shared++
    for (stem in shared downTo MIN_STEM) {
        if (first.substring(stem) in INFLECTION_ENDINGS && second.substring(stem) in INFLECTION_ENDINGS) return true
    }
    return false
}

/** tinyfix's tokenizer: words lowercased, a capitalized word mid-sentence as one name token, numbers,
 * line breaks as sentence ends, and every other character that is not a space or a word character on its
 * own (one UTF-16 unit, as JavaScript's regex sees it). */
internal fun tokenize(text: String, startsSentence: Boolean): List<String> {
    val s = text.replace('’', '\'')
    val tokens = ArrayList<String>()
    fun letter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
    fun digit(c: Char) = c in '0'..'9'
    var i = 0
    while (i < s.length) {
        val c = s[i]
        val raw: String
        when {
            letter(c) -> {
                var j = i
                while (j < s.length && letter(s[j])) j++
                if (j + 1 < s.length && s[j] == '\'' && letter(s[j + 1])) { j++; while (j < s.length && letter(s[j])) j++ }
                raw = s.substring(i, j); i = j
            }
            digit(c) -> {
                var j = i
                while (j < s.length && digit(s[j])) j++
                while (j + 1 < s.length && (s[j] == '.' || s[j] == ',') && digit(s[j + 1])) { j++; while (j < s.length && digit(s[j])) j++ }
                raw = s.substring(i, j); i = j
            }
            c == '\n' -> {
                if (tokens.isNotEmpty() && tokens.last() !in SENTENCE_OPENERS) tokens += "."
                i++; continue
            }
            Rules.isSpace(c) || c == '_' -> { i++; continue }
            else -> { raw = c.toString(); i++ }
        }
        val previous = tokens.lastOrNull()
        val atSentenceStart = if (previous == null) startsSentence else previous in SENTENCE_OPENERS
        val isPronoun = raw == "I" || raw.startsWith("I'")
        tokens += if (raw[0] in 'A'..'Z' && !atSentenceStart && !isPronoun) "<name>" else raw.lowercase()
    }
    return tokens
}
