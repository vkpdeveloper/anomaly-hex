package ly.anoma.hex.typing

import java.io.File

/** How much the strip and autocorrect help, as plain counts the engine bumps in fields that learn (never a
 * password, incognito or no-suggestions field). Numbers only: nothing typed is kept. Written beside the
 * learned words and forgotten with them; one writer (the main thread) and one reader (the save), so plain
 * volatile fields do. */
internal class TypingStats {
    @Volatile var words = 0L
    /** Letters, spaces and marks typed. */
    @Volatile var keys = 0L
    /** Characters a tapped suggestion put in beyond the tap itself. */
    @Volatile var saved = 0L
    /** Suggestions tapped while a word was being typed. */
    @Volatile var completions = 0L
    /** Suggestions tapped right after a space or mark: next-word guesses. */
    @Volatile var predictions = 0L
    @Volatile var corrected = 0L
    @Volatile var undone = 0L
    @Volatile var backspaces = 0L

    fun write() = "$HEADER\nwords\t$words\nkeys\t$keys\nsaved\t$saved\ncompletions\t$completions\npredictions\t$predictions\n" +
        "corrected\t$corrected\nundone\t$undone\nbackspaces\t$backspaces\n"

    fun read(text: String) {
        val lines = text.lineSequence().iterator()
        if (!lines.hasNext() || lines.next() != HEADER) return
        for (line in lines) {
            val f = line.split('\t')
            val n = f.getOrNull(1)?.toLongOrNull()?.takeIf { it >= 0 } ?: continue
            when (f[0]) {
                "words" -> words = n; "keys" -> keys = n; "saved" -> saved = n; "completions" -> completions = n
                "predictions" -> predictions = n; "corrected" -> corrected = n; "undone" -> undone = n; "backspaces" -> backspaces = n
            }
        }
    }

    fun clear() {
        words = 0; keys = 0; saved = 0; completions = 0; predictions = 0; corrected = 0; undone = 0; backspaces = 0
    }

    /** What to tell the person once there is enough to go on, or null. */
    fun summary(): String? {
        if (words < MIN_WORDS) return null
        val parts = ArrayList<String>()
        val typed = keys + completions + predictions + saved
        if (saved > 0 && typed > 0) parts += "Suggestions saved you ${percent(saved, typed)}% of keystrokes."
        if (corrected >= MIN_CORRECTIONS) parts += "${percent(undone, corrected)}% of autocorrections were undone."
        return parts.joinToString(" ").ifEmpty { null }
    }

    private fun percent(part: Long, whole: Long) = Math.round(100.0 * part / whole)

    companion object {
        const val FILE = "typing-stats.tsv"
        private const val HEADER = "hex-stats 1"
        private const val MIN_WORDS = 200
        private const val MIN_CORRECTIONS = 20

        /** [summary] of the saved counts in [files], read off the main thread's way: a small file. */
        fun summary(files: File): String? = runCatching {
            File(files, FILE).takeIf { it.exists() }?.let { TypingStats().apply { read(it.readText()) }.summary() }
        }.getOrNull()
    }
}
