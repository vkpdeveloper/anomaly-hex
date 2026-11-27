package ly.anoma.hex.typing

/** The words already in the text field being typed in (the name in the greeting, the topic of the
 * message), which are likelier to come again in it: the decoders take [bonus] off their cost and the strip
 * completes from them ([completions]). Nothing is saved: the words come from the text around the caret when
 * a field starts and from each word typed after, and go when the field changes or empties, so it works in
 * any field that has suggestions, learning or not; the keyboard never fills one for a password field. */
internal class FieldWords {
    private class Seen(val spelling: String, val at: Int)
    private val words = LinkedHashMap<String, Seen>()
    private var clock = 0

    @Synchronized fun clear() { words.clear() }

    /** Every word of [text] that is long enough to say anything about the message. */
    @Synchronized fun seed(text: CharSequence) {
        var start = -1
        for (i in 0..text.length) {
            val c = if (i < text.length) text[i] else ' '
            if (c.isLetter() || (c == '\'' && start >= 0)) { if (start < 0) start = i; continue }
            if (start >= 0) { addLocked(text.substring(start, i)); start = -1 }
        }
    }

    @Synchronized fun add(word: String) = addLocked(word)
    private fun addLocked(word: String) {
        val spelling = word.trimEnd('\'')
        val w = spelling.lowercase()
        if (w.length < MIN_LENGTH || w.length > TypingWord.MAX_LENGTH) return
        words.remove(w); words[w] = Seen(spelling, clock++)
        if (words.size > MAX) words.remove(words.keys.first())
    }

    /** How much less [word] costs here, in nats, when it is already in the field. Called for many words
     * of one glide, so a field with nothing in it answers at once. */
    @Synchronized fun bonus(word: String): Double {
        if (words.isEmpty()) return 0.0
        return if (word in words || (word.any(Char::isUpperCase) && word.lowercase() in words)) BONUS else 0.0
    }

    /** The newest word of the field that starts with [prefix] and is longer, as it was written there, or null. */
    @Synchronized fun completion(prefix: String): String? {
        if (words.isEmpty()) return null
        val lower = prefix.lowercase()
        var best: Seen? = null
        for ((w, seen) in words) if (w.length > lower.length && w.startsWith(lower) && (best == null || seen.at > best.at)) best = seen
        return best?.spelling
    }

    companion object {
        private const val MIN_LENGTH = 4
        private const val MAX = 400
        private const val BONUS = 2.0
    }
}
