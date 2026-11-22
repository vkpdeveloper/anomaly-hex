package ly.anoma.hex.typing

import android.content.Context
import java.security.MessageDigest
import ly.anoma.hex.speech.Spoken

internal class TypingPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("typing", Context.MODE_PRIVATE)
    var layout: Spoken?
        get() = Spoken.matching(prefs.getString("layout", null))
        set(value) { prefs.edit().putString("layout", value?.layoutCode).apply() }
    var autocorrect: Boolean
        get() = prefs.getBoolean("autocorrect", true)
        set(value) { prefs.edit().putBoolean("autocorrect", value).apply() }
    /** Off by default: the keys show lowercase until you press shift. */
    var autoCapitalize: Boolean
        get() = prefs.getBoolean("autoCapitalize", false)
        set(value) { prefs.edit().putBoolean("autoCapitalize", value).apply() }
    var suggestSwearWords: Boolean
        get() = prefs.getBoolean(SUGGEST_SWEAR_WORDS, false)
        set(value) { prefs.edit().putBoolean(SUGGEST_SWEAR_WORDS, value).apply() }
    var spatial: Boolean
        get() = prefs.getBoolean("spatial", true)
        set(value) { prefs.edit().putBoolean("spatial", value).apply() }
    var popups: Boolean
        get() = prefs.getBoolean("popups", false)
        set(value) { prefs.edit().putBoolean("popups", value).apply() }
    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(value) { prefs.edit().putBoolean("haptics", value).apply() }
    var glide: Boolean
        get() = prefs.getBoolean("glide", true)
        set(value) { prefs.edit().putBoolean("glide", value).apply() }
    var glideTrail: Boolean
        get() = prefs.getBoolean("glideTrail", true)
        set(value) { prefs.edit().putBoolean("glideTrail", value).apply() }
    /** Whether the keyboard starts from the words in the paired computer's Hex History (`LearnedWords.seed`). */
    var learnFromHistory: Boolean
        get() = prefs.getBoolean(LEARN_FROM_HISTORY, true)
        set(value) { prefs.edit().putBoolean(LEARN_FROM_HISTORY, value).apply() }
    /** When the computer's words last arrived, in wall-clock milliseconds; 0 for never. */
    var historyWordsAt: Long
        get() = prefs.getLong("historyWordsAt", 0)
        set(value) { prefs.edit().putLong("historyWordsAt", value).apply() }
    /** Forgets every word the keyboard learned: the saved file now, and a running keyboard's copy
     * through [FORGET_LEARNED]. */
    fun forgetLearned(files: java.io.File) {
        java.io.File(files, LearnedWords.FILE).delete()
        prefs.edit().putLong(FORGET_LEARNED, System.currentTimeMillis()).putLong("historyWordsAt", 0).apply()
    }

    // Remember only explicit rejection of this exact pair in this language; never save typed text.
    private fun pair(language: String, original: String, replacement: String): String {
        val bytes = (language + "\u0000" + original.lowercase() + "\u0000" + replacement.lowercase()).toByteArray()
        return android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes), android.util.Base64.NO_WRAP)
    }
    fun blocked(language: String, original: String, replacement: String) =
        prefs.getInt(pair(language, original, replacement), 0) >= 2
    fun undo(language: String, original: String, replacement: String) {
        val key = pair(language, original, replacement)
        prefs.edit().putInt(key, (prefs.getInt(key, 0) + 1).coerceAtMost(2)).apply()
    }
    companion object {
        const val SUGGEST_SWEAR_WORDS = "suggestSwearWords"
        const val LEARN_FROM_HISTORY = "learnFromHistory"
        const val FORGET_LEARNED = "forgetLearned"
    }
}
