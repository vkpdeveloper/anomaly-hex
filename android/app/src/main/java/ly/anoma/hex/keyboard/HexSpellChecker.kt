package ly.anoma.hex.keyboard

import android.service.textservice.SpellCheckerService
import android.os.SystemClock
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import java.io.File
import ly.anoma.hex.fix.Fix
import ly.anoma.hex.fix.TinyfixFiles
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.LearnedWords
import ly.anoma.hex.typing.PersonalWords
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.SpellCheck
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences

/** Hex as the phone's spell checker (Settings › Languages › Spell checker): words are checked against the
 * keyboard's own words for its layout, yours and the ones it learned included, so Hinglish isn't
 * underlined. A word it doesn't know is underlined with no pop-up: tapping it puts its spellings on
 * the keyboard's strip instead ([KeyboardEngine]'s caret handling), as Gboard does. With the fix
 * button's files downloaded, on English and Hinglish, a known word the sentence says should be
 * another ("form" for "from") gets the grammar underline, and its fix waits in [WrongWords]. */
class HexSpellChecker : SpellCheckerService() {
    private val lock = Any()
    // One language's words at a time, the layout's; read again for another layout, and your words
    // again once they may have changed.
    private var language: Spoken? = null
    private var decoder: SpatialDecoder? = null
    private var personalAt = Long.MIN_VALUE / 2
    private val prefs by lazy { TypingPreferences(this) }
    // English's model, to tell Hinglish's Hindi words, which tinyfix would "fix" ("nahi" into "nazi").
    private val english by lazy { TypingModel.asset(assets, Spoken.English.code) }
    // A sentence's wrong words: the app asks again after each edit. Cleared with the layout.
    private val wrongWordCache = object : LinkedHashMap<String, List<Fix>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Fix>>) = size > 32
    }

    override fun createSession(): Session = HexSession()

    private fun check(text: String): List<SpellCheck.Word> = synchronized(lock) {
        val layout = prefs.layout ?: Spoken.system()
        val decoder = decoder.takeIf { language == layout } ?: SpatialDecoder(TypingModel.asset(assets, layout.layoutCode)).also {
            decoder = it; language = layout; personalAt = Long.MIN_VALUE / 2; wrongWordCache.clear()
        }
        val now = SystemClock.uptimeMillis()
        if (now - personalAt > PERSONAL_MS) {
            personalAt = now
            val model = decoder.model
            val learned = LearnedWords().also { l -> runCatching { File(filesDir, LearnedWords.FILE).takeIf { it.isFile }?.let { l.read(it.readText()) } } }
            decoder.personal = PersonalWords.read(contentResolver, model,
                learned.words().filter { model.index(it.lowercase()) < 0 && !model.known(it.lowercase()) })
        }
        val words = SpellCheck(decoder, prefs.autoCapitalize, prefs.suggestSwearWords).check(text, wrongWords(text, layout, decoder.model))
        for (w in words) if (w.wrongWord) WrongWords.put(text.substring(w.from, w.to), text.substring(0, w.from), w.suggestions!!.first())
        words
    }

    // tinyfix's wrong words for [text], with the fix button's files here, on English and Hinglish (it knows
    // only English): null otherwise.
    private fun wrongWords(text: String, layout: Spoken, model: TypingModel): ((keep: (String) -> Boolean) -> List<Fix>)? {
        val dir = TinyfixFiles.dir(this)
        val hinglish = layout == Spoken.Hinglish
        if (!prefs.fixButton || !(layout == Spoken.English || hinglish) || !TinyfixFiles.ready(dir)) return null
        return { keep ->
            wrongWordCache.getOrPut(text) {
                try {
                    TinyfixFiles.withFixer(dir) { fixer -> fixer.check(text) { w -> keep(w) || hinglish && hindi(model, w) } }
                } catch (_: Exception) { emptyList() } // The files are gone or damaged: Settings downloads them again.
            }
        }
    }

    private fun hindi(hinglish: TypingModel, word: String) = word.lowercase().let { hinglish.index(it) >= 0 && english.index(it) < 0 }

    private inner class HexSession : Session() {
        override fun onCreate() {}

        override fun onGetSuggestions(textInfo: TextInfo, suggestionsLimit: Int): SuggestionsInfo {
            val text = textInfo.text.orEmpty()
            val word = check(text).singleOrNull()?.takeIf { it.from == 0 && it.to == text.length }
            return info(word, suggestionsLimit, textInfo.cookie, textInfo.sequence)
        }

        override fun onGetSentenceSuggestionsMultiple(textInfos: Array<TextInfo>, suggestionsLimit: Int): Array<SentenceSuggestionsInfo> =
            textInfos.map { textInfo ->
                val words = check(textInfo.text.orEmpty())
                SentenceSuggestionsInfo(
                    words.map { info(it, suggestionsLimit, textInfo.cookie, textInfo.sequence) }.toTypedArray(),
                    words.map { it.from }.toIntArray(),
                    words.map { it.to - it.from }.toIntArray())
            }.toTypedArray()

        private fun info(word: SpellCheck.Word?, limit: Int, cookie: Int, sequence: Int): SuggestionsInfo {
            val suggestions = word?.suggestions ?: return SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, emptyArray(), cookie, sequence)
            // The keyboard's strip shows the spellings; the app shows no pop-up of its own.
            var attrs = SuggestionsInfo.RESULT_ATTR_DONT_SHOW_UI_FOR_SUGGESTIONS or
                if (word.wrongWord) SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_GRAMMAR_ERROR else SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO
            if (suggestions.isNotEmpty()) attrs = attrs or SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS
            return SuggestionsInfo(attrs, suggestions.take(limit.coerceAtLeast(0)).toTypedArray(), cookie, sequence)
        }
    }

    private companion object {
        const val PERSONAL_MS = 60_000L
    }
}

/** The wrong words the spell checker underlined last ("form" for "from"), for the keyboard in the same
 * process: with the caret in one, the strip offers its fix. Known by the word and the text just before it. */
internal object WrongWords {
    private class Flag(val word: String, val before: String, val fix: String)
    private const val BEFORE = 24
    private const val KEPT = 32
    private val flags = ArrayDeque<Flag>()

    @Synchronized fun put(word: String, before: String, fix: String) {
        val tail = before.takeLast(BEFORE)
        flags.removeAll { it.word == word && it.before == tail }
        flags.addLast(Flag(word, tail, fix))
        while (flags.size > KEPT) flags.removeFirst()
    }

    /** The fix for [word] with [before] the text before it, if the checker underlined it there. */
    @Synchronized fun fix(word: String, before: String): String? =
        flags.lastOrNull { it.word == word && before.endsWith(it.before) }?.fix
}
