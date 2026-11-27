package ly.anoma.hex.typing

/** The words you removed from the strip with a long press ("Forget"), kept as Gboard keeps the ones you
 * remove from its personal dictionary: a word of the model or of your personal dictionary is never
 * suggested or autocorrected to again ([NeverSuggest.hide], [PersonalWords]), and a word you wrote is
 * not learned back from one slip. Keeping the word as typed against an autocorrection ([remove]) brings
 * it back. Saved as one word per line; thread-safe. */
internal class Forgotten {
    private val words = LinkedHashSet<String>()

    @Synchronized fun add(word: String): Boolean {
        val lower = word.lowercase()
        if (lower.isEmpty()) return false
        val added = words.add(lower)
        if (words.size > MAX) words.remove(words.first())
        return added
    }
    @Synchronized fun remove(word: String) = words.remove(word.lowercase())
    @Synchronized operator fun contains(word: String) = words.isNotEmpty() && word.lowercase() in words
    @Synchronized fun all(): List<String> = words.toList()
    @Synchronized fun clear() = words.clear()

    @Synchronized fun write(): String = words.joinToString("\n")
    /** What [write] wrote, added to what's here. */
    @Synchronized fun read(text: String) {
        for (line in text.lineSequence()) line.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
    }

    companion object {
        /** The saved copy, in the app's files folder. */
        const val FILE = "forgotten-words.txt"
        private const val MAX = 2_000
    }
}
