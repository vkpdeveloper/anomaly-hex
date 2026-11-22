package ly.anoma.hex.typing

import ly.anoma.hex.fix.Fix

/** Hex's spell checker over one language's words: the words of a text Hex doesn't know, each with the
 * spellings the keyboard's strip would offer for it. Not thread safe. */
internal class SpellCheck(decoder: SpatialDecoder, addsCapitals: Boolean, suggestSwearWords: Boolean) {
    /** The word at [from] until [to]; [suggestions] is null when Hex knows it, else what to offer, best first.
     * A [wrongWord] is one Hex knows that the sentence says should be another ("form" for "from"). */
    data class Word(val from: Int, val to: Int, val suggestions: List<String>?, val wrongWord: Boolean = false)

    private val word = TypingWord(decoder, spatial = true).also {
        it.addsCapitals = addsCapitals; it.suggestSwearWords = suggestSwearWords
    }

    /** [wrongWords], given the words to leave alone (those Hex doesn't know: they're misspelled already),
     * finds the known words the sentence says should be others: tinyfix's fixes ([ly.anoma.hex.fix.Tinyfix.check]). */
    fun check(text: String, wrongWords: ((keep: (String) -> Boolean) -> List<Fix>)? = null): List<Word> {
        val words = WORD.findAll(text).map { m ->
            val from = m.range.first
            val token = m.value.trimEnd('\'', '’')
            val to = from + token.length
            Word(from, to, if (checked(text, from, to, token)) misspelled(token, text.substring(maxOf(0, from - 40), from)) else null)
        }.toList()
        if (wrongWords == null) return words
        val unknown = words.filter { it.suggestions != null }.mapTo(HashSet()) { text.substring(it.from, it.to) }
        val fixes = wrongWords { it in unknown }.associateBy { it.from }
        return words.map { w ->
            val fix = fixes[w.from]?.takeIf { it.to == w.to && w.suggestions == null && checked(text, w.from, w.to, it.original) }
            if (fix == null) w else w.copy(suggestions = listOf(fix.replacement), wrongWord = true)
        }
    }

    private fun misspelled(token: String, before: String): List<String>? {
        word.reread(token, before)
        if (!word.unknown()) return null
        val strip = word.strip(autocorrect = true).filter { it.kind != Suggestion.Kind.Typed }
        // The correction space would make first.
        return strip.sortedBy { if (it.kind == Suggestion.Kind.Fix) 0 else 1 }.map { it.text }
    }

    companion object {
        private val WORD = Regex("\\p{L}[\\p{L}'’]*")

        /** Prose only: no single letters, acronyms or camelCase, and no part of an address, handle,
         * path, number or identifier ("john@exmaple.com", "#tbt", "v2beta"). */
        fun checked(text: String, from: Int, to: Int, token: String): Boolean {
            if (token.length < 2 || token.length > TypingWord.MAX_LENGTH) return false
            if (token.drop(1).any { it.isUpperCase() }) return false
            val prev = text.getOrNull(from - 1)
            val next = text.getOrNull(to)
            if (prev != null && (prev.isDigit() || prev in "@#/\\_.:" )) return false
            if (next != null && (next.isDigit() || next in "@/\\_")) return false
            if (next == '.' && text.getOrNull(to + 1)?.isLetterOrDigit() == true) return false
            return true
        }
    }
}
