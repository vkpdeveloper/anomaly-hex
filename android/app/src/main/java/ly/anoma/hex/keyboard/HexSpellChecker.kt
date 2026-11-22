package ly.anoma.hex.keyboard

import android.service.textservice.SpellCheckerService
import android.os.SystemClock
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import java.io.File
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
 * the keyboard's strip instead ([KeyboardEngine]'s caret handling), as Gboard does. */
class HexSpellChecker : SpellCheckerService() {
    private val lock = Any()
    // One language's words at a time, the layout's; read again for another layout, and your words
    // again once they may have changed.
    private var language: Spoken? = null
    private var decoder: SpatialDecoder? = null
    private var personalAt = Long.MIN_VALUE / 2
    private val prefs by lazy { TypingPreferences(this) }

    override fun createSession(): Session = HexSession()

    private fun check(text: String): List<SpellCheck.Word> = synchronized(lock) {
        val layout = prefs.layout ?: Spoken.system()
        val decoder = decoder.takeIf { language == layout } ?: SpatialDecoder(TypingModel.asset(assets, layout.layoutCode)).also {
            decoder = it; language = layout; personalAt = Long.MIN_VALUE / 2
        }
        val now = SystemClock.uptimeMillis()
        if (now - personalAt > PERSONAL_MS) {
            personalAt = now
            val model = decoder.model
            val learned = LearnedWords().also { l -> runCatching { File(filesDir, LearnedWords.FILE).takeIf { it.isFile }?.let { l.read(it.readText()) } } }
            decoder.personal = PersonalWords.read(contentResolver, model,
                learned.words().filter { model.index(it.lowercase()) < 0 && !model.known(it.lowercase()) })
        }
        SpellCheck(decoder, prefs.autoCapitalize, prefs.suggestSwearWords).check(text)
    }

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
            var attrs = SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO or SuggestionsInfo.RESULT_ATTR_DONT_SHOW_UI_FOR_SUGGESTIONS
            if (suggestions.isNotEmpty()) attrs = attrs or SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS
            return SuggestionsInfo(attrs, suggestions.take(limit.coerceAtLeast(0)).toTypedArray(), cookie, sequence)
        }
    }

    private companion object {
        const val PERSONAL_MS = 60_000L
    }
}
