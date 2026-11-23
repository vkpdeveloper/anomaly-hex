package ly.anoma.hex.keyboard

import ly.anoma.hex.typing.LearnedWords

/** What a fixed dictation taught, as the computer takes it (`POST /phone/v1/corrections`): the text
 * around the insertion as it was dictated, the dictated words, and the same text after the fix.
 * [words] are the new words the fix put where dictated ones were, each with whether it starts a
 * sentence, for the phone's own [LearnedWords]. */
internal data class DictationFix(val baseline: String, val inserted: String, val edited: String, val words: List<Pair<String, Boolean>>)

/** Follows one dictation in its field for a while. [begin] takes the field's text with the dictation in
 * it, [sample] each later reading, and [finish] says what the person fixed: nothing when the field was
 * left alone, or the edits fell outside the dictated words. Held in memory only, and gone with [drop]. */
internal class DictationWatch(private val clock: () -> Long) {
    private var inserted = ""
    private var baseline = ""
    private var latest = ""
    private var startedAt = 0L
    var active = false
        private set

    /** Starts following [inserted] in [field], the text now holding it; nothing when it isn't in there. */
    fun begin(inserted: String, field: String) {
        drop()
        val words = inserted.trim()
        if (words.isEmpty() || !field.contains(words)) return
        this.inserted = words; baseline = field; latest = field; startedAt = clock(); active = true
    }

    fun sample(field: String) { if (active) latest = field }

    fun expired() = active && clock() - startedAt >= WATCH_MS

    /** Ends the watch with what the last reading says was fixed. */
    fun finish(): DictationFix? {
        if (!active) return null
        val fix = Corrections.of(baseline, inserted, latest)
        drop()
        return fix
    }

    fun drop() { active = false; inserted = ""; baseline = ""; latest = "" }

    companion object {
        /** A fix usually comes within seconds; this long keeps a forgotten field from being followed. */
        const val WATCH_MS = 5 * 60_000L
    }
}

internal object Corrections {
    /** Text kept on each side of the edit; the computer reads a few hundred words at most. */
    private const val MARGIN = 300
    private const val MAX_WINDOW = 3_000
    private const val MAX_NEW_WORDS = 5
    private val token = Regex("[\\p{L}']+")

    /** What changing [baseline] into [edited] taught about the dictated [inserted], or null when the
     * text is unchanged or the change misses the dictated words. */
    fun of(baseline: String, inserted: String, edited: String): DictationFix? {
        if (edited == baseline) return null
        val at = baseline.lastIndexOf(inserted)
        if (at < 0) return null
        val end = at + inserted.length
        var prefix = 0
        val shortest = minOf(baseline.length, edited.length)
        while (prefix < shortest && baseline[prefix] == edited[prefix]) prefix++
        var suffix = 0
        while (suffix < shortest - prefix && baseline[baseline.length - 1 - suffix] == edited[edited.length - 1 - suffix]) suffix++
        val changedEnd = baseline.length - suffix
        if (prefix > end || changedEnd < at) return null
        val from = maxOf(0, minOf(prefix, at) - MARGIN)
        val to = minOf(baseline.length, maxOf(changedEnd, end) + MARGIN)
        if (to - from > MAX_WINDOW) return null
        val editedTo = to + edited.length - baseline.length
        val inside = prefix >= at && changedEnd <= end
        return DictationFix(
            baseline.substring(from, to), inserted, edited.substring(from, editedTo),
            if (inside) newWords(baseline, edited, prefix, edited.length - suffix) else emptyList(),
        )
    }

    // The words in edited[from, to), widened to whole words, that the dictation didn't have.
    private fun newWords(baseline: String, edited: String, from: Int, to: Int): List<Pair<String, Boolean>> {
        var start = from
        while (start > 0 && (edited[start - 1].isLetter() || edited[start - 1] == '\'')) start--
        var end = to
        while (end < edited.length && (edited[end].isLetter() || edited[end] == '\'')) end++
        val had = token.findAll(baseline).map { it.value }.toHashSet()
        val region = edited.substring(start, end)
        return token.findAll(region)
            .filter { it.value.trim('\'').length >= 2 && it.value !in had }
            .distinctBy { it.value }.take(MAX_NEW_WORDS)
            .map { it.value.trim('\'') to startsSentence(edited, start + it.range.first) }.toList()
    }

    private fun startsSentence(text: String, at: Int): Boolean {
        var i = at - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '"' || text[i] == '“' || text[i] == '(')) i--
        return i < 0 || text[i] in ".!?\n:"
    }

    /** The phone's own words learn [words] as if used [LearnedWords.WORD_USES] times, so typing them works
     * at once. Returns whether any word became one of yours. */
    fun learn(learned: LearnedWords, words: List<Pair<String, Boolean>>): Boolean {
        var changed = false
        for ((word, sentenceStart) in words) repeat(LearnedWords.WORD_USES) { if (learned.learn(word, null, sentenceStart)) changed = true }
        return changed
    }

    /** Terms from the computer (a name can be several words), each word as one that doesn't start a sentence. */
    fun termWords(terms: List<String>) = terms.flatMap { term ->
        token.findAll(term).map { it.value.trim('\'') }.filter { it.length >= 2 }.toList()
    }.distinct().map { it to false }
}

/** Corrections the computer hasn't taken yet, newest last, never more than [max]: one it was unreachable
 * for waits for the next contact, across restarts ([write], [read]; the phone's own words already
 * learned theirs, so a read one carries no [DictationFix.words]). */
internal class PendingCorrections(private val max: Int = MAX) {
    private val queue = ArrayDeque<DictationFix>()
    @Synchronized fun add(fix: DictationFix) { queue.addLast(fix); while (queue.size > max) queue.removeFirst() }
    @Synchronized fun first(): DictationFix? = queue.firstOrNull()
    @Synchronized fun remove(fix: DictationFix) { queue.remove(fix) }
    @Synchronized fun clear() = queue.clear()
    @get:Synchronized val size get() = queue.size

    /** One line per correction, its three texts in Base64, oldest first. */
    @Synchronized fun write(): String = queue.joinToString("") { f ->
        listOf(f.baseline, f.inserted, f.edited).joinToString("\t", postfix = "\n") { encoder.encodeToString(it.toByteArray()) }
    }
    /** What [write] wrote, after what's here; a line it can't read is skipped. */
    @Synchronized fun read(text: String) {
        for (line in text.lineSequence()) {
            val f = line.split('\t').takeIf { it.size == 3 } ?: continue
            val (baseline, inserted, edited) = runCatching { f.map { String(decoder.decode(it)) } }.getOrNull() ?: continue
            add(DictationFix(baseline, inserted, edited, emptyList()))
        }
    }

    companion object {
        const val MAX = 5
        const val FILE = "pending-corrections.tsv"
        private val encoder = java.util.Base64.getEncoder()
        private val decoder = java.util.Base64.getDecoder()
    }
}
