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
    /** On by default: swears are suggested like other words. Slurs never are. */
    var suggestSwearWords: Boolean
        get() = prefs.getBoolean(SUGGEST_SWEAR_WORDS, true)
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
    /** Off by default: 1 to 0 above the letters, in place of row 1's small digits. */
    var numberRow: Boolean
        get() = prefs.getBoolean(NUMBER_ROW, false)
        set(value) { prefs.edit().putBoolean(NUMBER_ROW, value).apply() }
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
    /** Whether words you fix in text the keyboard dictated are learned, here and on the paired computer. */
    var learnFromCorrections: Boolean
        get() = prefs.getBoolean(LEARN_FROM_CORRECTIONS, true)
        set(value) { prefs.edit().putBoolean(LEARN_FROM_CORRECTIONS, value).apply() }
    /** The fix button above the keys: on only once its files have downloaded ([ly.anoma.hex.fix.TinyfixFiles]). */
    var fixButton: Boolean
        get() = prefs.getBoolean(FIX_BUTTON, false)
        set(value) { prefs.edit().putBoolean(FIX_BUTTON, value).apply() }
    /** Off by default: every key, strip, pick and autocorrection goes to a file in Download/Hex ([ly.anoma.hex.keyboard.TypingLog]). */
    var typingLog: Boolean
        get() = prefs.getBoolean(TYPING_LOG, false)
        set(value) { prefs.edit().putBoolean(TYPING_LOG, value).apply() }
    /** Whether the strip offers your contacts' email addresses (`EmailAddresses`); it reads them only
     * while Hex has the Contacts permission too. */
    var contactEmails: Boolean
        get() = prefs.getBoolean(CONTACT_EMAILS, false)
        set(value) { prefs.edit().putBoolean(CONTACT_EMAILS, value).apply() }
    /** When the computer's words last arrived, in wall-clock milliseconds; 0 for never. */
    var historyWordsAt: Long
        get() = prefs.getLong("historyWordsAt", 0)
        set(value) { prefs.edit().putLong("historyWordsAt", value).apply() }
    /** Off by default: a dictation that ends with "send it" presses the field's send key ([ly.anoma.hex.keyboard.SpokenSend]). */
    var sayToSend: Boolean
        get() = prefs.getBoolean(SAY_TO_SEND, false)
        set(value) { prefs.edit().putBoolean(SAY_TO_SEND, value).apply() }
    /** Off by default: a link copied while the keyboard runs, or pasted from its clipboard, loses its
     * tracking ([ly.anoma.hex.keyboard.Links]). */
    var cleanLinks: Boolean
        get() = prefs.getBoolean(CLEAN_LINKS, false)
        set(value) { prefs.edit().putBoolean(CLEAN_LINKS, value).apply() }
    /** Off by default: the strip offers a one-time code a text brought ([ly.anoma.hex.keyboard.TextedCodes]),
     * only while Hex may read texts too. */
    var textedCodes: Boolean
        get() = prefs.getBoolean(TEXTED_CODES, false)
        set(value) { prefs.edit().putBoolean(TEXTED_CODES, value).apply() }
    /** Off by default: a copied short link becomes the link it stands for ([ly.anoma.hex.keyboard.ShortLinks]). */
    var expandLinks: Boolean
        get() = prefs.getBoolean(EXPAND_LINKS, false)
        set(value) { prefs.edit().putBoolean(EXPAND_LINKS, value).apply() }
    /** The paired computer's text replacements, as [Replacements.write] keeps them; "" for none. */
    var replacements: String
        get() = prefs.getString(REPLACEMENTS, "").orEmpty()
        set(value) { prefs.edit().putString(REPLACEMENTS, value).apply() }
    /** Off by default: "I'm free " offers your calendar's free times ([ly.anoma.hex.keyboard.FreeTimes]),
     * only while Hex may read the calendar too. */
    var freeTimes: Boolean
        get() = prefs.getBoolean(FREE_TIMES, false)
        set(value) { prefs.edit().putBoolean(FREE_TIMES, value).apply() }
    /** Your text shortcuts as [ly.anoma.hex.keyboard.Snippets.write] keeps them; null until first changed. */
    var snippets: String?
        get() = prefs.getString(SNIPPETS, null)
        set(value) { prefs.edit().putString(SNIPPETS, value).apply() }
    /** When the computer's replacements last arrived, in wall-clock milliseconds; 0 for never. */
    var replacementsAt: Long
        get() = prefs.getLong("replacementsAt", 0)
        set(value) { prefs.edit().putLong("replacementsAt", value).apply() }
    /** The computer was forgotten or another paired: its replacements go, and the next keyboard asks again. */
    fun forgetReplacements() { prefs.edit().putString(REPLACEMENTS, "").putLong("replacementsAt", 0).apply() }
    /** Forgets every word the keyboard learned: the saved file now, and a running keyboard's copy
     * through [FORGET_LEARNED]. */
    fun forgetLearned(files: java.io.File) {
        java.io.File(files, LearnedWords.FILE).delete()
        java.io.File(files, LearnedPhrases.FILE).delete()
        java.io.File(files, TouchOffsets.FILE).delete()
        java.io.File(files, TypoMemory.FILE).delete()
        java.io.File(files, TypingStats.FILE).delete()
        java.io.File(files, Forgotten.FILE).delete()
        java.io.File(files, ly.anoma.hex.keyboard.PendingCorrections.FILE).delete()
        prefs.edit().putLong(FORGET_LEARNED, System.currentTimeMillis()).putLong("historyWordsAt", 0).apply()
    }

    // Remember only explicit rejection of this exact pair in this language; never save typed text.
    private fun pair(language: String, original: String, replacement: String): String {
        val bytes = (language + "\u0000" + original.lowercase() + "\u0000" + replacement.lowercase()).toByteArray()
        return java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
    /** Whether the keyboard has been told once that [original] shouldn't become [replacement]: only that swap is off. */
    fun blocked(language: String, original: String, replacement: String) =
        prefs.getInt(pair(language, original, replacement), 0) >= 1
    fun undo(language: String, original: String, replacement: String) {
        prefs.edit().putInt(pair(language, original, replacement), 1).apply()
    }
    companion object {
        const val SUGGEST_SWEAR_WORDS = "suggestSwearWords"
        const val LEARN_FROM_HISTORY = "learnFromHistory"
        const val LEARN_FROM_CORRECTIONS = "learnFromCorrections"
        const val FORGET_LEARNED = "forgetLearned"
        const val FIX_BUTTON = "fixButton"
        const val TYPING_LOG = "typingLog"
        const val CONTACT_EMAILS = "contactEmails"
        const val SAY_TO_SEND = "sayToSend"
        const val REPLACEMENTS = "replacements"
        const val NUMBER_ROW = "numberRow"
        const val CLEAN_LINKS = "cleanLinks"
        const val TEXTED_CODES = "textedCodes"
        const val EXPAND_LINKS = "expandLinks"
        const val SNIPPETS = "snippets"
        const val FREE_TIMES = "freeTimes"
    }
}
