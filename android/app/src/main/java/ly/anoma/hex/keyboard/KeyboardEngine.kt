package ly.anoma.hex.keyboard

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import java.util.concurrent.Executor
import ly.anoma.hex.fix.Fix
import ly.anoma.hex.fix.Tinyfix
import ly.anoma.hex.relay.HexClient
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.speech.SpokenText
import ly.anoma.hex.typing.EmailAddresses
import ly.anoma.hex.typing.EmojiWords
import ly.anoma.hex.typing.Forgotten
import ly.anoma.hex.typing.NextWords
import ly.anoma.hex.typing.KeyGrid
import ly.anoma.hex.typing.KeyPoint
import ly.anoma.hex.typing.FieldWords
import ly.anoma.hex.typing.LearnedPhrases
import ly.anoma.hex.typing.LearnedWords
import ly.anoma.hex.typing.PersonalWords
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.typing.TouchOffsets
import ly.anoma.hex.typing.TypoMemory
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences
import ly.anoma.hex.typing.TypingStats
import ly.anoma.hex.typing.TypingWord

/** What the engine shows: the keys' page and the strip's slots. */
internal interface KeyboardSurface {
    fun showPage(page: KeyboardPage)
    /** The strip, in the typing model's order: what was typed first when it shows, then the fix or the
     * likeliest word, then the rest. Next words after a space are [Suggestion.Kind.Next]. */
    fun suggestions(slots: List<Suggestion>)
    /** The emoji a colon search typed in the text (":fire") found, in a row in the strip's place; none
     * puts the strip back. */
    fun emojiRow(emoji: List<String>) {}
    /** The emoji panel's search is open with [query] typed and the emoji it [found], or closed (null). */
    fun emojiSearch(query: String?, found: List<String>) {}
    /** Offer an undo, or stop offering it. */
    fun undo(offer: UndoOffer?)
    /** Chips for the selection or what was just typed, in the suggestions' place; none clears them. */
    fun actions(list: List<StripAction>) {}
    /** The fix button's chip shows on the strip while [shown]: the field has fixes to make. */
    fun fixOffer(shown: Boolean) {}
}

/** Works out the fixes for a field's text off the main thread and calls [done] with them on the main
 * thread. [cancelled] turns true once the text has changed, and a run that sees it stops without calling [done]. */
internal fun interface FixFinder {
    fun find(field: FieldText, cancelled: () -> Boolean, done: (List<Fix>) -> Unit)
}

internal enum class UndoOffer { Dictation, Fixes }

/** The field's text around the caret for the fix button: [text] starts at [start] in the field. */
internal data class FieldText(val text: String, val start: Int, val selStart: Int, val selEnd: Int)

/** Keys in, text out: the current word, autocorrection and its undo, spaces, shift and the key pages.
 * It talks to the editor only through [KeyboardTextTarget] and to the views through [KeyboardSurface]. */
internal class KeyboardEngine(
    field: KeyboardTextTarget,
    private val feedback: TypingPreferences,
    private val layoutLanguage: () -> Spoken,
    private val loadDecoder: (String) -> SpatialDecoder,
    /** Reads Android's personal dictionary for a model, with the words you've written it lacks; runs on [background]. */
    private val readPersonal: ((TypingModel, Collection<String>) -> PersonalWords)? = null,
    /** What you write, learned as each word ends in an ordinary text field (never a password or incognito field). */
    private val learned: LearnedWords = LearnedWords(),
    /** [learned] changed and should be saved. */
    private val learnedChanged: () -> Unit = {},
    /** Where your taps land on each key, learned from the words you finish under the same rules as [learned]. */
    val touch: TouchOffsets = TouchOffsets(),
    /** The slips you fix the same way again and again, learned and kept under the same rules as [learned]. */
    val typos: TypoMemory = TypoMemory(),
    /** Counts of what the strip and autocorrect did, kept under the same rules as [learned]; numbers, never text. */
    val stats: TypingStats = TypingStats(),
    /** The words you forgot from the strip: hidden from suggestions and never learned back from a slip. */
    private val forgotten: Forgotten = Forgotten(),
    /** [forgotten] changed and should be saved. */
    private val forgottenChanged: () -> Unit = {},
    /** The addresses the strip offers as one is typed, with those in [learned]. */
    private val emails: EmailAddresses = EmailAddresses(),
    /** A typing model's emoji lists (by its language), or null for a language with none. */
    private val emojiLists: (String) -> EmojiWords? = { null },
    /** The emoji you picked for words, learned in the same fields as [learned]. */
    private val emojiPicks: EmojiWords.Picks = EmojiWords.Picks(),
    /** [emojiPicks] changed and should be saved. */
    private val emojiPicked: () -> Unit = {},
    /** The emoji panel's recent emoji, which an empty search shows. */
    private val emojiRecent: () -> List<String> = { emptyList() },
    /** An emoji a search found went in: it's recent now. */
    private val emojiUsed: (String) -> Unit = {},
    /** An emoji with the skin tone chosen for it, as it goes in; everything else keeps it without. */
    private val emojiTone: (String) -> String = { it },
    /** Finds the fixes the fix button would make, where it works ([fixOn]); none, no chip. */
    private val fixFinder: FixFinder? = null,
    private val fixOn: () -> Boolean = { false },
    /** Free times from the calendar for the text before the caret ([FreeTimes]), none while that's off. */
    private val freeTimes: (String) -> List<StripAction> = { emptyList() },
    /** What the person fixed in text just dictated, for the paired computer ([DictationWatch]). */
    private val corrected: (DictationFix) -> Unit = {},
    /** The runs of words you write, offered as one chip and learned in the same fields as [learned]. */
    private val phrases: LearnedPhrases = LearnedPhrases(),
    private val background: Executor = Executor { it.run() },
    /** The next-word model's words for English and Hinglish layouts ([NextWords]); none, the word pairs alone. */
    private val nextWords: NextWords? = null,
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
    private val main: Handler = Handler(Looper.getMainLooper()),
    /** Work the strip after the key's own work has returned, so a letter is committed first and the
     * suggestions follow in the next turn of the main loop. Off, the strip is worked out at once. */
    private val deferStrip: Boolean = false,
) {
    private companion object {
        /** The fix and the decoder's readings wait for this pause in typing: working them out costs a
         * search (2 ms median, 16 ms p95 per letter on the API 31 emulator), which shouldn't delay keys. */
        const val CHIP_PAUSE_MS = 100L
        /** A selection change this long after the keyboard's last edit is the person's: a tap in the text. */
        const val OWN_EDIT_MS = 500L
        /** Undo dictation stays this long unless something else happens first (iPhone Hex: 12 s). */
        const val UNDO_MS = 12_000L
        /** How much text on each side of the caret the fix button reads and checks. Each word costs a
         * model run of about 3 ms, so this keeps a long field's check near a second. */
        const val FIX_WINDOW = 1_000
        /** The fix button's chip waits for this pause in typing and in moving the caret before the text is checked. */
        const val FIX_PAUSE_MS = 700L
        /** A pause this long in typing starts another undo step even mid-word. */
        const val STEP_GAP_MS = 2_000L
        /** How much of the text before a word tells that two edits are at the same place. */
        const val START_TAIL = 40
        /** A fixed dictation is read once the field has been still this long. */
        const val WATCH_PAUSE_MS = 1_000L
        /** Words kept on the strip beside a phrase. */
        const val PHRASE_WORDS = 1
    }
    private var editedAt = Long.MIN_VALUE / 2
    // Undo's steps: a word with what ends it, a run of deletes, or one larger edit ([EditHistory]). Within a
    // batch only its first edit can start one, so a batch is one step.
    private enum class Step { Letter, Ending, Delete, Other }
    private var lastStep: Step? = null
    private var lastStepAt = Long.MIN_VALUE / 2
    private var stepAt = Long.MIN_VALUE / 2
    private var committedAt = Long.MIN_VALUE / 2
    private var committed = 0
    private var batches = 0
    private var batchStepped = false
    private fun step(kind: Step) {
        // A password is never kept, even as an undo step.
        if (secure || batches > 0 && batchStepped) return
        val now = clock()
        val starts = when (kind) {
            // A word starts after anything but its own letters; marks and spaces end it, so they join it.
            Step.Letter -> lastStep != Step.Letter
            Step.Ending -> lastStep == null
            Step.Delete -> lastStep != Step.Delete
            Step.Other -> true
        } || now - lastStepAt > STEP_GAP_MS
        if (starts && history.checkpoint()) stepAt = now
        lastStep = kind; lastStepAt = now
        if (batches > 0) batchStepped = true
    }
    private fun stepFor(text: String) = when {
        text.length == 1 && text[0].isLetterOrDigit() || text.length == 2 && Character.isSurrogatePair(text[0], text[1]) -> Step.Letter
        text.isNotEmpty() && text.all { it.isWhitespace() || TypingWord.endsWord(it) } -> Step.Ending
        else -> Step.Other
    }
    // Every edit goes through here, so the selection changes that follow can be told from a tap in the text.
    private val target = object : KeyboardTextTarget by field {
        private fun <T> edit(block: () -> T): T { editedAt = clock(); fixesStale(); return block() }
        override fun commit(text: String) = edit {
            step(stepFor(text)); committedAt = clock(); committed = text.length
            field.commit(text)
        }
        override fun deleteBefore(length: Int) = edit { step(Step.Delete); field.deleteBefore(length) }
        override fun deleteCodePointsBefore(count: Int) = edit { step(Step.Delete); field.deleteCodePointsBefore(count) }
        override fun deleteAround(before: Int, after: Int) = edit { step(Step.Delete); field.deleteAround(before, after) }
        override fun setSelection(start: Int, end: Int) = edit { field.setSelection(start, end) }
        // A sent message's text never comes back by undo.
        override fun performEditorAction(id: Int) = edit { field.performEditorAction(id).also { if (it) forgetSteps() } }
        override fun defaultEditorAction() = edit { field.defaultEditorAction().also { if (it) forgetSteps() } }
        override fun sendKey(code: Int) = edit { if (code == KeyEvent.KEYCODE_DEL) step(Step.Delete); field.sendKey(code) }
        override fun beginBatch() { if (batches++ == 0) batchStepped = false; field.beginBatch() }
        override fun endBatch() { if (batches > 0) batches--; field.endBatch() }
    }
    // Undo and redo edit the field directly, never starting a step of their own.
    private val history = EditHistory(object : KeyboardTextTarget by field {
        private fun <T> edit(block: () -> T): T { editedAt = clock(); fixesStale(); return block() }
        override fun commit(text: String) = edit { field.commit(text) }
        override fun deleteBefore(length: Int) = edit { field.deleteBefore(length) }
        override fun setSelection(start: Int, end: Int) = edit { field.setSelection(start, end) }
    })

    private fun forgetSteps() { history.clear(); lastStep = null }

    /** A two-finger swipe left on the keys: the field goes back a step. False when there is none. A step
     * begun at or after [strayFrom] (the swipe's second finger typing the first's key) is taken back first. */
    fun undoStep(strayFrom: Long? = null): Boolean = historyStep {
        takeBackStray(strayFrom)
        history.undo()
    }
    /** A two-finger swipe right: the step undo took back comes again, after a stray step as [undoStep]'s. */
    fun redoStep(strayFrom: Long? = null): Boolean = historyStep {
        takeBackStray(strayFrom)
        history.redo()
    }
    // What the keyboard typed since [strayFrom]: a step of its own is forgotten, and a key that joined the
    // word being typed is deleted, so neither undo nor redo keeps it.
    private fun takeBackStray(strayFrom: Long?) {
        if (strayFrom == null) return
        if (stepAt >= strayFrom) history.forget()
        else if (committedAt >= strayFrom) history.delete(committed)
    }
    private fun historyStep(step: () -> Boolean): Boolean {
        if (secure || !target.connected) return false
        edited(); resetTyping(); clearSpaces()
        val done = step()
        // Typing after this starts a step of its own.
        lastStep = null
        showPredictions(before()); refreshCaps()
        return done
    }
    /** The word the caret was tapped into, split at the caret, while the strip shows its suggestions. */
    private class Tapped(val left: String, val right: String)
    private var tapped: Tapped? = null
    // Undo dictation, as iPhone Hex's: the dictation just inserted and the text after it then, offered
    // back until the next edit, 12 s, or the cursor leaving its end, so the chip never does nothing.
    // Undo fixes is offered the same way, and works while the field still reads as the fixes left it.
    private sealed interface Offer
    private data class Dictated(val text: String, val after: String) : Offer
    private class Fixed(val field: FieldText, val fixes: List<Fix>, val result: String) : Offer
    private var offer: Offer? = null
    private val dropDictation = Runnable { offerUndo(null) }
    private var pendingChip: Runnable? = null
    /** Shows the strip last shown again, if what it was for is still there: the next-word model's
     * words arrive after the strip ([NextWords]), and take their place in it then. */
    private var redo: (() -> Unit)? = null
    private val nextReady = Runnable { redo?.invoke() }
    init { nextWords?.ready = { main.removeCallbacks(nextReady); main.post(nextReady) } }
    var surface: KeyboardSurface? = null
    private val models = HashMap<String, SpatialDecoder>()
    private var word: TypingWord? = null
    private var editor: EditorInfo? = null
    // What the field allows, as Gboard reads it. The strip's words show in every text field but a password
    // or email field: a field that asks for no suggestions or autocorrect still gets them to tap, as Gboard
    // gives them, but nothing changes by itself there ([autocorrectHere]); and only [learnHere] is learned.
    private var wordsHere = false
    private var autocorrectHere = false
    private var learnHere = false
    private var prefix = ""
    private var expectedCaret = -1
    private data class Undo(val language: String, val original: String, val replacement: String)
    private var undo: Undo? = null
    private val shift = ShiftState()
    private var symbols = false
    private var more = false
    private var numpad = false
    // A symbol was typed since ?123 opened the symbol pages: the next space goes back to the letters,
    // as on Gboard, so a "." pressed after "?" and a space isn't the symbol page's "?" again.
    private var symbolTyped = false
    private var selStart = -1
    private var selEnd = -1
    // Spaces, each remembered with the text before the cursor it left, so anything else in between
    // (another key, a tap in the text) cancels it: a space for double-space, and the space a pick
    // left waiting for the next word.
    private var spaceAnchor: String? = null
    private var spaceAt = 0L
    private var heldAnchor: String? = null
    /** The word a glide put in and its other readings, while [anchor], the text before the cursor right
     * after it, is still there: delete takes the word back, a pick replaces it, and the next word,
     * tapped or glided, puts a space in first while a mark goes against it (Gboard's phantom space).
     * [readings] are as the strip shows them (with shift's capitals), each with its own spelling. */
    private class Glided(val word: String, val readings: Map<String, String>, val anchor: String)
    private var glided: Glided? = null
    /** A glided word delete took back: the word typed in its place, at [Glided.anchor], is the one the
     * glide missed, and is yours at once, as Gboard learns it ([learn]). */
    private var unglided: Glided? = null
    /** What the last [learn] counted, at [anchor] (the text before the cursor then), so undoing the
     * autocorrection that put the word in takes it back. */
    private class Counted(val anchor: String, val word: LearnedWords.Finished, val counts: Boolean, val touched: TypingWord.Touched?,
                          val typed: String?, val typoWeight: Int)
    private var counted: Counted? = null
    /** The last word that ended as typed and the text before it, so deleting it whole and typing another
     * over it ([Redone]) is a fix of that slip. */
    private class Ended(val word: String, val start: String)
    private var ended: Ended? = null
    private var redone: Ended? = null
    var secure = true
        private set
    // Email addresses are offered and learned in text fields that allow suggestions and learning; an
    // address field offers them from the first letter, first in the strip.
    private var addressesHere = false
    private var addressField = false
    /** The addresses the strip offers and the text they complete, which a pick replaces. */
    private class AddressOffer(val typed: String, val addresses: List<String>)
    private var addressOffer: AddressOffer? = null
    /** The words already in this field, which are likelier to come again ([FieldWords]). */
    private val fieldWords = FieldWords()
    /** The word the strip's emoji are for, which a picked one is learned for. */
    private var emojiWord: String? = null
    // Each address is learned once per field, and leaving it teaches only after something was typed there.
    private val addressesLearned = HashSet<String>()
    private var typedHere = false

    fun startInput(attribute: EditorInfo) {
        stopSearch()
        edited()
        forgetSteps(); batches = 0
        editor = attribute
        learned.app = attribute.packageName
        val kind = attribute.inputType and InputType.TYPE_MASK_CLASS
        val variation = attribute.inputType and InputType.TYPE_MASK_VARIATION
        secure = when (kind) {
            InputType.TYPE_CLASS_TEXT -> variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        wordsHere = kind == InputType.TYPE_CLASS_TEXT && !secure && variation !in setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        autocorrectHere = wordsHere &&
            variation !in setOf(InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_FILTER) &&
            attribute.inputType and (InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE) == 0
        // A search box that shows its app's own completions isn't corrected, but what you write there is
        // yours, as on Gboard.
        learnHere = wordsHere && attribute.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0 &&
            variation !in setOf(InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_FILTER) &&
            attribute.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0
        addressesHere = kind == InputType.TYPE_CLASS_TEXT && !secure &&
            attribute.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0 &&
            variation !in setOf(InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_FILTER) &&
            attribute.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0
        addressField = addressesHere && variation in setOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        addressesLearned.clear(); typedHere = false; unglided = null; counted = null; ended = null; redone = null
        // The text around the caret from the copy the keyboard keeps, never from the editor on a key.
        fieldWords.clear()
        if (wordsHere && !secure && attribute.initialSelStart == attribute.initialSelEnd) {
            target.before(CachedText.INITIAL)?.let(fieldWords::seed); target.after(CachedText.INITIAL)?.let(fieldWords::seed)
        }
        if (!learnsCorrections()) dropWatch()
        resetTyping(); word = null; clearSpaces()
        numpad = kind == InputType.TYPE_CLASS_NUMBER || kind == InputType.TYPE_CLASS_PHONE ||
            kind == InputType.TYPE_CLASS_DATETIME
        symbols = numpad
        more = false; symbolTyped = false; shift.reset()
        selStart = attribute.initialSelStart; selEnd = attribute.initialSelEnd
        refreshCaps(force = true)
        fixesStale()
    }

    fun updateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int) = traced("hex:select") { doUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd) }
    private fun doUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int) {
        selStart = newSelStart; selEnd = newSelEnd
        if (newSelStart == 0 && newSelEnd == 0 && oldSelStart > 0) fieldWords.clear() // Sent or cleared: a new message.
        if (watch.active) watchChanged()
        if (oldSelStart != newSelStart || oldSelEnd != newSelEnd) { offerActions(); fixesStale() }
        (offer as? Dictated)?.let { if (!undoable(it)) offerUndo(null) }
        val expected = expectedCaret >= 0 && newSelStart == expectedCaret && newSelEnd == expectedCaret
        if (oldSelStart != newSelStart || oldSelEnd != newSelEnd) {
            if (expectedCaret >= 0 && (newSelStart != expectedCaret || newSelEnd != expectedCaret)) {
                // Editors may coalesce selection notifications. Compare the actual surrounding text
                // before invalidating our own in-flight commit, not a stale intermediate offset.
                val suffix = undo?.let { it.replacement + " " } ?: word?.token?.takeIf { it.isNotEmpty() }?.let { prefix + it }
                if (newSelStart != newSelEnd || suffix == null || !endsWith(suffix) ||
                    oldSelStart == expectedCaret) { resetTyping(); clearStrip() }
            }
            refreshCaps()
            if (!expected && clock() - editedAt > OWN_EDIT_MS) caretMoved()
        }
    }

    /** The person put the caret somewhere in the text. In a word, the strip offers that word's
     * suggestions, as Gboard does; picking one replaces the whole word. Elsewhere, next words. */
    private fun caretMoved() {
        val w = word ?: return
        resetTyping(); clearSpaces()
        if (symbols || selStart < 0 || selStart != selEnd) { clearStrip(); return }
        val b = before()
        val a = target.after(48).orEmpty()
        val left = TypingWord.currentToken(b) ?: if (TypingWord.boundary(b.lastOrNull())) "" else null
        var right = a.takeWhile { it.isLetter() || it == '\'' || it == '\u2019' }.trimEnd('\'', '\u2019')
        if (a.getOrNull(right.length)?.let { it.isDigit() || it in "@_/" } == true) right = ""
        val whole = (left ?: "") + right
        if (left == null || whole.firstOrNull()?.isLetter() != true || whole.length > TypingWord.MAX_LENGTH) {
            showSuggestions(w); return
        }
        val start = b.dropLast(left.length)
        w.reread(whole, start.takeLast(40))
        var strip = w.strip(autocorrect = feedback.autocorrect).map {
            if (it.kind == Suggestion.Kind.Fix && (!autocorrectHere || QuickMath.endsInUnit(b) ||
                    feedback.blocked(w.decoder.model.language, whole, it.text)))
                Suggestion(it.text, Suggestion.Kind.Word) else it
        }
        // A word the spell checker underlined as the wrong one ("form" for "from"): its fix in the middle.
        (if (secure) null else WrongWords.fix(whole, start))?.let { fix ->
            strip = listOf(Suggestion(whole, Suggestion.Kind.Typed), Suggestion(fix, Suggestion.Kind.Fix)) +
                strip.filter { it.kind != Suggestion.Kind.Typed && !it.text.equals(fix, ignoreCase = true) }.take(1)
        }
        // At the word's end it is the word in progress again: letters add to it and a pick ends it.
        if (right.isEmpty()) { prefix = start.takeLast(40); w.restored(left, prefix) }
        else { w.reset(); tapped = Tapped(left, right) }
        cancelSuggestions(); strip(strip)
    }

    /** Suggest swear words changed: the current word's suggestions follow at once. */
    fun swearWordsChanged() {
        word?.let {
            it.suggestSwearWords = feedback.suggestSwearWords
            showSuggestions(it)
        }
    }

    // A language's decoder with what you write and your own words, and for Hinglish English's model,
    // read in the background, which tells its Hindi words from English ones (SpatialDecoder.english).
    private fun prepared(decoder: SpatialDecoder) = decoder.also {
        it.learned = learned; it.typos = typos; it.fieldWords = fieldWords; it.model.neverSuggest.hide(forgotten.all()); loadPersonal(it)
        if (it.model.language == Spoken.Hinglish.code) background.execute {
            it.english = try { loadDecoder(Spoken.English.code).model } catch (_: java.io.IOException) { null }
        }
    }
    /** Android's personal dictionary changed: every loaded language reads it again, off the main thread. */
    fun personalWordsChanged() { for (decoder in models.values) loadPersonal(decoder) }
    private fun loadPersonal(decoder: SpatialDecoder, then: () -> Unit = {}) {
        val read = readPersonal ?: return
        val model = decoder.model
        // Which of your words the model lacks asks the model's word list, whose search keeps a plain
        // cache that only the main thread may touch (it also decodes the strip), so that is asked here.
        // The background read then needs only the model's alphabet.
        val mine = learned.yours(model).filter { it !in forgotten }
        background.execute {
            decoder.personal = read(model, mine)
            then()
        }
    }

    /** [word], long-pressed on the strip, is forgotten: no longer suggested or corrected to, dropped from
     * what you wrote, and not learned again from one slip. A user action, so it works in any field. */
    fun forget(word: String) {
        if (!forgotten.add(word)) return
        learned.forgetWord(word); phrases.forgetWord(word)
        hiddenChanged(refresh = true)
        forgottenChanged(); learnedChanged()
    }
    /** [forgotten] changed (a word forgotten or brought back, or all of them cleared). */
    fun hiddenChanged(refresh: Boolean = false) {
        for (decoder in models.values) {
            decoder.model.neverSuggest.hide(forgotten.all())
            loadPersonal(decoder) { if (refresh) main.post { showSuggestions(word) } }
        }
        if (refresh) showSuggestions(word)
    }

    /** [word] is yours from now on, as if used twice: kept as typed against an autocorrection, picked over
     * one or over a glide, or put back by undo. Forgotten words come back when you choose them. At a
     * [sentenceStart] its capital teaches nothing, as in [LearnedWords.learn]. */
    private fun teach(word: String, sentenceStart: Boolean = false) {
        if (!learnHere) return
        typos.forget(word)
        if (forgotten.remove(word)) { forgottenChanged(); for (d in models.values) d.model.neverSuggest.hide(forgotten.all()) }
        repeat(LearnedWords.WORD_USES) { learned.learn(word, null, sentenceStart) }
        personalWordsChanged(); learnedChanged()
    }
    // What a picked strip word teaches: the word kept as typed, when the keyboard lacks it or would have
    // corrected it, or a word picked over the autocorrection that space would have made.
    private fun taught(w: TypingWord, typed: String, picked: String): String? {
        if (!learnHere || typed.length < 2) return null
        val lang = w.decoder.model.language
        val fix = w.replacement()?.takeIf {
            feedback.autocorrect && autocorrectHere && !it.equals(typed, ignoreCase = true) && !feedback.blocked(lang, typed, it)
        }
        return when {
            picked == typed -> typed.takeIf { w.unknown() || fix != null }
            fix != null && !picked.equals(fix, ignoreCase = true) -> picked
            else -> null
        }
    }

    /** The word that ends [b], the text before the cursor, is learned when [boundary] characters (a
     * space or mark that just went in after it, or none: the next glide, a held space, return) follow it. */
    private fun learn(b: String, boundary: Int, picked: Boolean = false) {
        if (word == null || b.length <= boundary || !b[b.length - 1 - boundary].isLetter()) return
        // `finished` reads a word a space or mark ended; with none yet, the one about to go in.
        val closed = if (boundary == 0) "$b " else b
        val f = LearnedWords.finished(closed) ?: return
        fieldWords.add(f.word)
        if (!learnHere) return
        val touched = word?.takeTouched()?.takeIf { t -> TouchOffsets.alike(t.typed, f.word) && touch.learn(f.word, t.points, t.grid) }
        if (touched != null) learnedChanged()
        val start = b.dropLast(boundary).dropLast(f.word.length)
        val typed = word?.takeTyped()
        val typoWeight = if (typed != null) learnTypo(typed, f.word, if (picked) TypoMemory.OWN else 1, f.sentenceStart) else 0
        redone?.takeIf { sameStart(it.start, start) }?.let { learnTypo(it.word, f.word, TypoMemory.OWN, f.sentenceStart) }
        redone = null
        ended = if (typed != null && typed.equals(f.word, ignoreCase = true)) Ended(f.word, start) else null
        val missed = unglided?.takeIf { g -> b.dropLast(boundary).dropLast(f.word.length) == g.anchor &&
            g.readings.keys.none { it.equals(f.word, ignoreCase = true) } }
        unglided = null
        val counts = f.word !in forgotten
        counted = Counted(b, f, counts, touched, typed, typoWeight)
        stats.words++
        if (!counts) return
        if (learned.learn(f.word, f.previous, f.sentenceStart, f.beforePrevious)) personalWordsChanged()
        phrases.learn(closed)
        learnedChanged()
        missed?.let { teach(f.word, f.sentenceStart) }
    }
    private fun sameStart(a: String, b: String) = a.takeLast(START_TAIL) == b.takeLast(START_TAIL)
    /** [typed] was ended as [word]: a slip of yours, remembered when it is one and not a swap you undid before.
     * Returns the weight learned (0 for none). */
    private fun learnTypo(typed: String, word: String, weight: Int, sentenceStart: Boolean = false): Int {
        val model = this.word?.decoder?.model ?: return 0
        if (word.lowercase() in forgotten || feedback.blocked(model.language, typed, word)) return 0
        // A sentence's capital isn't the word's: "The" is kept as "the", but "Debra" stays.
        val lower = word.replaceFirstChar { it.lowercase() }
        val spelled = if (sentenceStart && model.index(lower) >= 0) lower else word
        return if (typos.learn(typed, spelled, weight)) weight.also { learnedChanged() } else 0
    }
    // The address that ends [b], the text before the cursor, once a space, comma, semicolon or line break
    // follows it or the field is left. A period may still be the address's own ("a@b.co" of "a@b.co.uk").
    private fun learnAddressBefore(b: String) { if (addressesHere) EmailAddresses.finished(b)?.let(::learnAddress) }
    private fun learnAddress(address: String) {
        if (!addressesHere || !addressesLearned.add(address.lowercase())) return
        learned.learnAddress(address); learnedChanged()
    }
    /** The paired computer's words changed what [learned] holds: every language reads its words again. */
    fun learnedWordsChanged() = personalWordsChanged()

    /** Whether the field asks for a capital here (with Auto-capitalisation on), or caps lock is on: what shift would show. */
    private fun capsWanted(): Boolean {
        if (shift.locked) return true
        if (!feedback.autoCapitalize) return false
        val mask = (editor?.inputType ?: 0) and
            (InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        return !secure && mask != 0 && target.capsMode(mask) != 0
    }
    private fun refreshCaps(force: Boolean = false) {
        val was = shift.uppercase
        shift.auto(capsWanted())
        if (force || was != shift.uppercase) showPlane()
    }
    private fun showPlane() {
        surface?.showPage(KeyboardPage(layoutLanguage(), symbols, more, shift.uppercase, shift.locked, EnterKey.from(editor), numpad,
            FieldKind.of(editor?.inputType ?: 0)))
    }
    /** The keyboard came up: it starts in lowercase, whatever shift or caps lock was when it went away. */
    fun opened() { shift.reset(); refreshCaps(force = true) }
    /** Shift went down: it acts at once, so a held shift and a letter type a capital. */
    fun shiftDown() {
        if (symbols) return
        shift.down(clock()); showPlane()
    }
    /** Shift lifted; on the symbol pages its key switches between them, on lifting. */
    fun shiftUp() {
        if (symbols) { more = !more; showPlane(); return }
        val was = shift.uppercase
        shift.up()
        if (was != shift.uppercase) showPlane()
    }
    /** ?123 from the letters, ABC from any other page. */
    fun mode() { leavePage(); symbols = !symbols; numpad = false; showPlane() }
    /** The symbols page's 1234: the number pad. */
    fun numpad() { leavePage(); symbols = true; numpad = true; showPlane() }
    /** The number pad's !?#: the first symbols page. */
    fun symbolPage() { leavePage(); symbols = true; numpad = false; showPlane() }
    private fun leavePage() {
        resetTyping(); clearSpaces(); clearStrip(); more = false; shift.forgetTap(); symbolTyped = false
    }
    private fun resetTyping() {
        cancelSuggestions(); addressOffer = null; word?.reset(); undo = null; expectedCaret = -1; prefix = ""; tapped = null
    }
    private fun clearSpaces() { spaceAnchor = null; heldAnchor = null; glided = null }
    private fun cancelChip() { pendingChip?.let(main::removeCallbacks); pendingChip = null }
    private var pendingStrip: Runnable? = null
    private fun cancelSuggestions() {
        cancelChip()
        pendingStrip?.let(main::removeCallbacks); pendingStrip = null
    }
    /** Empties the strip now, and drops any strip still waiting to be worked out. */
    private fun clearStrip() { cancelSuggestions(); redo = null; strip(emptyList()) }
    /** The strip's [slots], and in their place the emoji for a colon search right before the cursor. */
    private fun strip(slots: List<Suggestion>) {
        surface?.suggestions(slots)
        if (search != null) return
        val query = if (secure || selStart != selEnd || !target.connected) null else EmojiWords.colonQuery(before())
        val found = query?.let { q -> emojiLists(layoutLanguage().layoutCode)?.search(q, emojiPicks) }.orEmpty()
        colonQuery = query.takeIf { found.isNotEmpty() }
        surface?.emojiRow(found)
    }
    /** Runs [work], the strip's suggestions, after the key that asked for them (see [deferStrip]).
     * The latest request replaces an earlier one that has not run, so fast typing works out one strip. */
    private fun defer(work: () -> Unit) {
        cancelSuggestions()
        if (!deferStrip) { traced("hex:suggest", work); return }
        pendingStrip = Runnable { pendingStrip = null; traced("hex:suggest", work) }.also(main::post)
    }
    /** Reads the layout's typing model on [background] before the keyboard first shows, so showing it
     * doesn't wait on the read. A field that opens first reads the model itself and this copy goes. */
    fun preload() {
        val language = layoutLanguage().layoutCode
        if (language in models) return
        background.execute {
            val decoder = try { loadDecoder(language) } catch (_: java.io.IOException) { return@execute }
            main.post { if (language !in models) models[language] = prepared(decoder) }
        }
    }
    fun configureTyping(keepWord: Boolean = false) {
        val language = layoutLanguage().layoutCode
        val active = word
        if (wordsHere && keepWord && active != null && active.decoder.model.language == language && active.spatial == feedback.spatial &&
            active.token.isNotEmpty() && endsWith(prefix + active.token)) {
            active.addsCapitals = feedback.autoCapitalize
            showPlane(); showSuggestions(active); return
        }
        resetTyping()
        fixesStale()
        word = if (wordsHere) {
            try {
                TypingWord(models.getOrPut(language) { prepared(loadDecoder(language)) }, feedback.spatial)
                    .also { it.suggestSwearWords = feedback.suggestSwearWords; it.addsCapitals = feedback.autoCapitalize
                        it.phrases = phrases; it.next = nextWords?.takeIf { _ -> language in NextWords.LANGUAGES } }
            } catch (_: java.io.IOException) { null } // The editor can still type if its assets cannot be read.
        } else null
        showPlane()
        showSuggestions(word)
    }

    // The strip while a word is typed: completions at once, then, after a pause, the model's whole
    // strip with what space will put in. A fix you turned down twice shows as a plain word.
    private fun showSuggestions(w: TypingWord?) = defer { suggest(w) }
    private fun suggest(w: TypingWord?) {
        cancelChip()
        if (w != null && w.token.isNotEmpty()) {
            val token = w.token
            val quick = { if (word === w && w.token == token) showStrip(w.strip(autocorrect = false)) }
            quick(); redo = quick
            if (!feedback.autocorrect || !canEndWord()) return
            val full = {
                if (word === w && w.token == token) showStrip(w.strip(autocorrect = true).map {
                    if (it.kind == Suggestion.Kind.Fix && (!autocorrectHere || QuickMath.endsInUnit(before()) ||
                            feedback.blocked(w.decoder.model.language, token, it.text)))
                        Suggestion(it.text, Suggestion.Kind.Word) else it
                })
            }
            pendingChip = Runnable { pendingChip = null; full(); redo = full }.also { main.postDelayed(it, CHIP_PAUSE_MS) }
            return
        }
        val b = if (w != null && !symbols && selStart == selEnd) before() else null
        showPredictions(if (b != null && TypingWord.boundary(b.lastOrNull())) b else null)
    }
    /** Next words for the text before the cursor, capitalised as shift would be. */
    private fun showPredictions(before: String?) = defer { predict(before) }
    private fun predict(before: String?) {
        val w = word
        redo = null
        if (w == null || before == null || symbols || !canEndWord()) { showStrip(emptyList()); return }
        redo = { if (word === w && w.token.isEmpty() && before() == before) predict(before) }
        val caps = capsWanted()
        val words = w.predictions(before, capitalize = caps).map { Suggestion(it, Suggestion.Kind.Next) }
        val phrase = w.phrase(before, capitalize = caps)
        showStrip(if (phrase == null) words else withPhrase(phrase, words))
    }
    /** The strip with a [phrase] you write: it takes the wide middle slot and one word stays beside it (the
     * likeliest one that isn't the phrase's own first word, which the phrase already offers). */
    private fun withPhrase(phrase: String, words: List<Suggestion>): List<Suggestion> {
        val first = phrase.substringBefore(' ')
        return listOf(Suggestion(phrase, Suggestion.Kind.Phrase)) + words.filterNot { it.text.equals(first, ignoreCase = true) }.take(PHRASE_WORDS)
    }
    /** The strip, with the email addresses that complete the one being typed ([EmailAddresses]): up to
     * three, first, in an address field; elsewhere one in the last slot once three characters match,
     * and your contacts' only once an @ is typed, so they don't crowd out words. */
    private fun showStrip(slots: List<Suggestion>) {
        addressOffer = null; emojiWord = null
        val typed = if (addressesHere && selStart == selEnd) EmailAddresses.typed(before()) else null
        val found = if (typed == null || typed.length < (if (addressField) 1 else 3)) emptyList()
            else emails.matching(typed, learned, withContacts = addressField || '@' in typed, limit = if (addressField) 3 else 1)
        offerActions()
        if (found.isEmpty()) { strip(slots + emojiFor(slots)); return }
        addressOffer = AddressOffer(typed!!, found)
        val offered = found.map { Suggestion(it, Suggestion.Kind.Word) }
        strip(if (addressField) (offered + slots).take(3) else slots.take(2) + offered)
    }
    /** Emoji for the word being typed or one of the strip's words for it ("medicin" finds 💊 through
     * "medicine"), or after a space for the word before it, as Gboard offers them. */
    private fun emojiFor(slots: List<Suggestion>): List<Suggestion> {
        val w = word ?: return emptyList()
        if (symbols || selStart != selEnd) return emptyList()
        val lists = emojiLists(w.decoder.model.language) ?: return emptyList()
        val words = if (w.token.isNotEmpty()) listOf(w.token) + slots.filter { it.kind == Suggestion.Kind.Fix || it.kind == Suggestion.Kind.Word }.map { it.text }
            else before().takeIf { it.endsWith(" ") || it == heldAnchor }?.let(EmojiWords::wordBefore)?.let(::listOf) ?: return emptyList()
        val found = lists.find(words, emojiPicks) ?: return emptyList()
        emojiWord = found.word
        return found.emoji.map { Suggestion(it, Suggestion.Kind.Emoji) }
    }

    /** An emoji on the strip was tapped. A word being typed ends first as space would end it, fix and
     * all, then the emoji goes in and its space waits, as a picked word's does; it's learned for its word. */
    fun pickEmoji(emoji: String) {
        if (!target.connected) return
        edited(); typedHere = true
        val learnFor = emojiWord
        val w = word
        if (w != null && !symbols && w.token.isNotEmpty() && endsWith(prefix + w.token)) typeSpace(null, null)
        takeHeldSpace(before())
        clearSpaces(); resetTyping()
        if (!target.commit(emojiTone(emoji))) return
        if (learnFor != null && learnHere) { emojiPicks.pick(learnFor, emoji); emojiPicked() }
        val b = before()
        heldAnchor = b
        showPredictions(b); inputDone()
    }

    // The colon search the row's emoji are for (":fire" is "fire"), and the emoji panel's search, which
    // takes the keys' letters, space and delete while it is open.
    private var colonQuery: String? = null
    private var search: StringBuilder? = null

    /** The emoji panel's search opens: the keys type into it until [stopSearch]. */
    fun startSearch() {
        cancelChip(); resetTyping(); clearSpaces(); clearStrip()
        search = StringBuilder(); colonQuery = null
        surface?.emojiRow(emptyList())
        showSearch()
    }
    /** The emoji panel's search closes; the strip comes back. */
    fun stopSearch() {
        if (search == null) return
        search = null
        surface?.emojiSearch(null, emptyList())
    }
    val searching get() = search != null
    private fun showSearch() {
        val q = search?.toString() ?: return
        surface?.emojiSearch(q, if (q.isBlank()) emojiRecent() else emojiLists(layoutLanguage().layoutCode)?.search(q, emojiPicks).orEmpty())
    }
    /** A key while the emoji panel's search is open edits the search instead of the text: true when it did. */
    private fun searched(edit: StringBuilder.() -> Unit): Boolean {
        val s = search ?: return false
        s.edit(); showSearch(); return true
    }
    private fun StringBuilder.addSpace() { if (isNotEmpty() && last() != ' ') append(' ') }

    /** An emoji in the search's row was tapped. From the emoji panel's search it goes in as one from the
     * panel does, and the search stays open; for a colon search it takes the place of ":fire" and its
     * space waits, as a picked word's does. Either way it's learned for what was searched. */
    fun pickFound(emoji: String) {
        if (!target.connected) return
        val s = search
        val query = s?.toString() ?: colonQuery ?: return
        if (s != null) {
            search = null
            emoji(emoji)
            search = s
        } else {
            if (!endsWith(":$query")) { strip(emptyList()); return }
            edited(); typedHere = true
            resetTyping(); clearSpaces()
            if (!replace(":$query", emojiTone(emoji))) return
            colonQuery = null
            val b = before()
            heldAnchor = b
            showPredictions(b); inputDone()
        }
        val key = EmojiWords.queryWords(query).joinToString(" ")
        if (key.isNotEmpty() && learnHere) { emojiPicks.pick(key, emoji); emojiPicked() }
        emojiUsed(emoji)
        if (s != null) showSearch()
    }

    /** An emoji from the emoji panel: typed as it is, and learned for the word right before it, if any. */
    fun emoji(text: String) {
        // The word before the cursor, typed then or before; the field's kind says whether to learn.
        val after = if (learnHere && selStart == selEnd) EmojiWords.wordBefore(before()) else null
        type(emojiTone(text))
        if (after != null) { emojiPicks.pick(after, text); emojiPicked() }
    }

    private fun before() = if (secure) "" else target.before(96).orEmpty()
    private fun endsWith(suffix: String) = !secure && target.before(suffix.length) == suffix
    private fun canEndWord() = !secure && (target.after(1)?.firstOrNull()?.let {
        !it.isLetterOrDigit() && it !in "'_/@#"
    } ?: true)
    private fun inputDone() {
        val was = shift.uppercase
        shift.typed()
        if (was != shift.uppercase) showPlane()
    }
    /** The letter a touch near a key's edge types: the key it landed on, or a neighbour within
     * TypingWord.REACH dp ([near], distances in dp) that the word list makes clearly likelier next. Only
     * in an ordinary text field on the letters page, never with caps lock or Spatial autocorrect off.
     * Autocorrect itself needn't be on: this only reads a touch that missed every keycap, as Gboard does. */
    fun likelierLetter(hit: Char, near: Map<Char, Double>, keys: Collection<Char>): Char {
        val w = word ?: return hit
        if (near.isEmpty() || symbols || shift.locked || !w.spatial || !target.connected) return hit
        return w.likelierKey(before(), hit, near, keys, touch)
    }
    /** Shift as the keys show it: letters type capitals while it is on or held. */
    val uppercase get() = shift.uppercase

    fun letter(text: String, x: Double, y: Double, grid: KeyGrid, explicit: Boolean = false) = traced("hex:key:letter") {
        if (!searched { append(text.lowercase()) }) doLetter(text, x, y, grid, explicit)
    }
    private fun doLetter(text: String, x: Double, y: Double, grid: KeyGrid, explicit: Boolean = false) {
        if (!target.connected) return
        edited(); typedHere = true
        val w = word
        if (w == null || symbols || !text.single().isLetter()) { type(text); return }
        undo = null
        if (w.token.isEmpty()) {
            val b = takeHeldSpace(before())
            prefix = b.takeLast(40); w.reset(b)
            if (!w.startsWord(b) || !canEndWord()) { type(text); return }
        }
        clearSpaces()
        if (w.token.length >= TypingWord.MAX_LENGTH) { type(text); return }
        val start = if (expectedCaret >= 0) expectedCaret else minOf(selStart, selEnd)
        expectedCaret = if (start >= 0) start + text.length else -1
        if (target.commit(text)) {
            if (learnHere) stats.keys++
            if (explicit) w.explicit(text) else w.letter(text, x, y, grid)
            showSuggestions(w)
        } else resetTyping()
        inputDone()
    }
    // A space a pick held, or the one after a glided word, goes in before the next word starts;
    // returns the text before the cursor.
    private fun takeHeldSpace(before: String): String {
        if (before != heldAnchor && before != glided?.anchor) return before
        if (before == glided?.anchor) learn(before, 0)
        heldAnchor = null; glided = null
        if (!target.commit(" ")) return before
        if (expectedCaret >= 0) expectedCaret++
        return "$before "
    }
    private fun replace(old: String, new: String): Boolean {
        if (secure || !target.connected) return false
        // With a non-empty selection deleteSurroundingText would remove text outside the selection.
        if (target.selected()?.isNotEmpty() == true || (old.isNotEmpty() && !endsWith(old))) return false
        target.beginBatch()
        return try {
            (old.isEmpty() || target.deleteBefore(old.length)) && target.commit(new)
        } finally { target.endBatch() }
    }

    /** A strip slot was tapped. What was typed, a fix or a word goes in with no space after it: the
     * space waits for the next key that starts a word, and space types just that one (none when the
     * text after the cursor already starts with a space or closing mark). A mark goes against the word. */
    fun pickSuggestion(text: String) = traced("hex:key:pick") { doPickSuggestion(text) }
    private fun doPickSuggestion(text: String) {
        edited()
        addressOffer?.takeIf { text in it.addresses }?.let { pickAddress(it.typed, text); return }
        tapped?.let { pickForTapped(it, text); return }
        glided?.takeIf { it.anchor == before() }?.let { pickForGlided(it, text); return }
        val w = word ?: return
        if (symbols || !canEndWord()) return
        val old = w.token
        if (old.isNotEmpty() && !endsWith(prefix + old)) { resetTyping(); clearStrip(); return }
        // A next word after a pick puts the pick's waiting space in first.
        if (old.isEmpty()) takeHeldSpace(before())
        if (old.isEmpty() && !w.startsWord(before())) return
        val taught = if (old.isEmpty() || ' ' in text) null else taught(w, old, text)
        clearSpaces()
        if (replace(old, text)) {
            if (learnHere) {
                if (old.isEmpty()) stats.predictions++ else stats.completions++
                stats.saved += maxOf(0, text.length - old.length)
            }
            resetTyping()
            val b = before()
            heldAnchor = b
            // A phrase counts word by word, as if typed.
            val parts = text.split(' ')
            val base = b.dropLast(text.length)
            for (i in 1 until parts.size) learn(base + parts.take(i).joinToString(" ") + " ", 1)
            learn(b, 0, picked = parts.size == 1)
            taught?.let(::teach)
            showPredictions(b)
            inputDone()
        }
    }

    // An offered address takes the place of what was typed of it; in an address field nothing follows
    // it, elsewhere its space waits, as after a picked word.
    private fun pickAddress(typed: String, address: String) {
        resetTyping(); clearSpaces()
        if (!replace(typed, address)) { clearStrip(); return }
        learnAddress(address)
        val b = before()
        if (addressField) clearStrip()
        else { heldAnchor = b; showPredictions(b) }
        inputDone()
    }

    // Another reading takes the glided word's place, and the next word still puts its space in first.
    // The glide read it wrong, so the pick is yours at once, as a word picked over an autocorrection is.
    private fun pickForGlided(g: Glided, text: String) {
        if (text == g.word || !replace(g.word, text)) return
        showGlided(Glided(text, g.readings, before()))
        teach(g.readings[text] ?: text)
    }

    // The tapped word goes whole, both sides of the caret, and the pick takes its place with no space.
    // The word it replaces was the wrong one: what it counted after the words before it is taken back,
    // and the pick counts there instead, so fixing a word later teaches as an undone autocorrection does.
    private fun pickForTapped(t: Tapped, text: String) {
        resetTyping()
        if (!secure && target.before(t.left.length) == t.left && target.after(t.right.length) == t.right &&
            target.selected().isNullOrEmpty()) {
            val old = t.left + t.right
            val f = LearnedWords.finished(before().dropLast(t.left.length) + old + " ")
            target.beginBatch()
            try {
                if (target.deleteAround(t.left.length, t.right.length) && target.commit(text) && learnHere) {
                    if (f != null && !text.equals(old, ignoreCase = true) && learned.unlearn(old, f.previous, f.beforePrevious)) personalWordsChanged()
                    if (learned.learn(text, f?.previous, f?.sentenceStart ?: false, f?.beforePrevious)) personalWordsChanged()
                    learnTypo(old, text, TypoMemory.OWN, f?.sentenceStart ?: false)
                    learnedChanged()
                }
            } finally { target.endBatch() }
        }
        clearStrip()
    }

    /** The space bar, tapped at [point] in [grid]'s coordinates (null off the letters page). */
    fun space(point: KeyPoint? = null, grid: KeyGrid? = null) = traced("hex:key:space") { if (!searched { addSpace() }) doSpace(point, grid) }
    private fun doSpace(point: KeyPoint? = null, grid: KeyGrid? = null) {
        if (!target.connected) return
        typeSpace(point, grid)
        if (symbols && !numpad && symbolTyped) {
            symbols = false; more = false; symbolTyped = false
            showPlane(); showPredictions(before())
        }
    }
    private fun typeSpace(point: KeyPoint?, grid: KeyGrid?) {
        edited()
        if (learnHere) stats.keys++
        val now = clock()
        val b = before()
        when {
            // A pick's waiting space: space types it (the pick was learned already), or adds nothing
            // when the text after the cursor already separates the picked word.
            heldAnchor != null && heldAnchor == b -> {
                clearSpaces()
                if (!SpaceRules.separates(target.after(1)) && target.commit(" ")) { val s = before(); spaceAnchor = s; spaceAt = now; showPredictions(s); inputDone() }
                else showPredictions(b)
                return
            }
            word != null && spaceAnchor == b && now - spaceAt <= SpaceRules.DOUBLE_SPACE_MS && SpaceRules.periodFits(b) -> {
                clearSpaces()
                if (replace(" ", ". ")) { resetTyping(); showPredictions(before()); inputDone(); return }
            }
        }
        clearSpaces()
        val w = word
        if (w != null && !symbols && canEndWord()) {
            val old = w.token; val lang = w.decoder.model.language
            // "250 usd" stays as typed: its conversion chip needs the unit.
            if (feedback.autocorrect && autocorrectHere && old.isNotEmpty() && endsWith(prefix + old) && !QuickMath.endsInUnit(b)) {
                val fix = w.finish(' ', point, grid)
                if (fix != null && !feedback.blocked(lang, fix.original, fix.replacement) && replace(fix.original, fix.replacement + " ")) {
                    // What went in is the keyboard's word, not one typed for a missed glide.
                    cancelChip(); unglided = null
                    if (learnHere) stats.corrected++
                    undo = Undo(lang, fix.original, fix.replacement)
                    if (expectedCaret >= 0) expectedCaret += fix.replacement.length + 1 - fix.original.length
                    w.reset(); afterSpace(now); return
                }
            }
        }
        target.commit(" "); resetTyping(); afterSpace(now)
    }
    private fun afterSpace(now: Long) {
        val b = before()
        learn(b, 1); learnAddressBefore(b)
        spaceAnchor = b; spaceAt = now
        showPredictions(b); inputDone()
    }

    fun type(value: String) = traced("hex:key:type") {
        val typed = searched {
            if (value.isBlank()) addSpace() else if (value.all { it.isLetterOrDigit() || it == '\'' || it == '’' }) append(value.lowercase())
        }
        if (!typed) doType(value)
    }
    private fun doType(value: String) {
        if (!target.connected) return
        edited(); typedHere = true
        if (value == " ") { space(); return }
        if (learnHere) stats.keys++
        if (symbols && !numpad) symbolTyped = true
        val b = before()
        val mark = value.length == 1 && TypingWord.endsWord(value[0])
        // A mark goes against a picked word, in place of its waiting space ("Hello,"); the pick was learned already.
        if (heldAnchor != null && heldAnchor == b && value.isNotEmpty() && value.all { it in SpaceRules.JOINERS }) {
            clearSpaces(); resetTyping()
            if (target.commit(value)) showPredictions(before())
            inputDone(); return
        }
        // Anything that starts another word puts a held space in first; marks go against the word.
        if (value.none { it in SpaceRules.JOINERS || it.isWhitespace() }) takeHeldSpace(b)
        clearSpaces()
        val w = word
        // An apostrophe inside an English word keeps it one word, so "ler's" is still read as the word it was meant for.
        if ((value == "'" || value == "\u2019") && w != null && !symbols && canEndWord() && w.joinsApostrophe() &&
            endsWith(prefix + w.token)) {
            val start = if (expectedCaret >= 0) expectedCaret else minOf(selStart, selEnd)
            expectedCaret = if (start >= 0) start + value.length else -1
            if (target.commit(value)) { w.apostrophe(value); showSuggestions(w); inputDone() } else resetTyping()
            return
        }
        if (mark && w != null && !symbols && canEndWord()) {
            if (feedback.autocorrect && autocorrectHere && w.token.isNotEmpty() && endsWith(prefix + w.token)) {
                val fix = w.finish(value[0])
                if (fix != null && !feedback.blocked(w.decoder.model.language, fix.original, fix.replacement) &&
                    replace(fix.original, fix.replacement + value)) {
                    unglided = null; if (learnHere) stats.corrected++
                    resetTyping(); afterMark(value); return
                }
            }
        }
        resetTyping()
        if (target.commit(value) && mark) { afterMark(value); return }
        showPredictions(if (w != null) before() else null)
        inputDone()
    }
    private fun afterMark(mark: String) {
        val b = before()
        learn(b, mark.length)
        if (mark == "," || mark == ";") learnAddressBefore(b)
        showPredictions(b); inputDone()
    }
    fun delete() = traced("hex:key:delete") { if (!searched { if (isNotEmpty()) setLength(length - 1) }) doDelete() }
    private fun doDelete() {
        if (!target.connected) return
        edited()
        if (learnHere) stats.backspaces++
        // Right after a glide, delete takes the whole word back; the space before it stays.
        glided?.takeIf { it.anchor == before() }?.let { g ->
            clearSpaces()
            if (replace(g.word, "")) { unglided = Glided(g.word, g.readings, before()); showSuggestions(word); return }
        }
        // Right after a pick, when the text after the cursor already separated it, delete takes back the
        // space it held and deletes nothing. Otherwise its waiting space just goes and delete deletes.
        if (heldAnchor != null && heldAnchor == before() && SpaceRules.separates(target.after(1))) { clearSpaces(); clearStrip(); return }
        clearSpaces()
        val last = undo
        undo = null
        val anchor = before()
        if (last != null && endsWith(last.replacement + " ") && replace(last.replacement + " ", last.original)) {
            // The autocorrection's word counted when its space went in: undone, it takes nothing with it.
            counted?.takeIf { it.anchor == anchor }?.let { c ->
                val f = c.word
                c.touched?.let { t -> touch.unlearn(f.word, t.points, t.grid) }
                if (c.typed != null && c.typoWeight > 0) typos.unlearn(c.typed, f.word, c.typoWeight)
                if (c.counts && learned.unlearn(f.word, f.previous, f.beforePrevious)) personalWordsChanged()
                if (c.counts) phrases.unlearn(c.anchor)
                learnedChanged()
            }
            counted = null
            // What you put back is yours: it isn't corrected again (and, like the count, only where learning is allowed).
            if (learnHere) {
                stats.undone++
                feedback.undo(last.language, last.original, last.replacement)
                typos.remove(last.original, last.replacement)
                if (' ' !in last.original) teach(last.original)
            }
            val typed = last.original.substringAfterLast(' ')
            prefix = before().dropLast(typed.length).takeLast(40); word?.restored(typed, prefix)
            if (expectedCaret >= 0) expectedCaret += last.original.length - last.replacement.length - 1
            cancelSuggestions(); strip(listOf(Suggestion(typed, Suggestion.Kind.Typed))); return
        }
        val w = word
        val track = w != null && w.token.isNotEmpty() && endsWith(prefix + w.token)
        val selected = (selStart >= 0 && selEnd >= 0 && selStart != selEnd) ||
            (!secure && target.selected()?.isNotEmpty() == true)
        if (selected) { target.commit(""); resetTyping() }
        else {
            // A whole character as people see it: 👍🏽, a flag or a family is one, as on Gboard.
            val cluster = if (track || secure) 1 else DeleteSlide.lastCharacter(target.before(32).orEmpty())
            if (!(if (cluster > 1) target.deleteBefore(cluster) else target.deleteCodePointsBefore(1))) target.sendKey(KeyEvent.KEYCODE_DEL)
            if (track) { w!!.pop(); if (expectedCaret >= 0) expectedCaret-- } else resetTyping()
            ended?.let { e -> if (sameStart(before(), e.start)) { redone = e; ended = null } }
        }
        if (w != null && w.token.isNotEmpty()) showSuggestions(w) else clearStrip()
    }
    /** Held delete once it speeds up ([DeleteRepeat]): the word before the cursor with the spaces
     * after it, as a delete slide counts words, taken one character at a time through [delete]. */
    fun deleteWord() {
        if (searched { setLength(trimEnd().dropLastWhile { it != ' ' }.length) }) return
        val before = if (secure) "" else target.before(64).orEmpty()
        val word = before.takeLast(DeleteSlide.wordLengths(before).firstOrNull() ?: 0)
        val characters = java.text.BreakIterator.getCharacterInstance().apply { setText(word) }
        var count = 0
        while (characters.next() != java.text.BreakIterator.DONE) count++
        repeat(maxOf(count, 1)) { delete() }
    }
    fun enter() = traced("hex:key:enter") { if (!searched {}) doEnter() }
    private fun doEnter() {
        edited()
        // A pick was learned as it went in.
        if (heldAnchor != before()) learn(before(), 0)
        learnAddressBefore(before())
        resetTyping(); clearSpaces(); clearStrip()
        val info = editor
        if (info?.actionLabel != null && (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0 &&
            target.performEditorAction(info.actionId)) return
        if (!target.defaultEditorAction()) {
            if (editor?.inputType == InputType.TYPE_NULL) target.sendKey(KeyEvent.KEYCODE_ENTER)
            else type("\n")
        }
    }

    /** Whether a drag across the letters glides a word: Glide typing on, an ordinary text field (never a
     * password, number or address field) and the letters page. */
    val glides get() = feedback.glide && word != null && !symbols && target.connected

    /** A glide lifted, its [path] in [grid]'s coordinates. Its likeliest word goes in, after a space
     * when it follows a word, capitalised while shift is on and in capitals with caps lock; the strip
     * offers the other readings beside it. A word in progress stays as typed. */
    fun glide(path: List<KeyPoint>, grid: KeyGrid) = traced("hex:key:glide") { if (!searched {}) doGlide(path, grid) }
    private fun doGlide(path: List<KeyPoint>, grid: KeyGrid) {
        val w = word
        if (w == null || !glides) return
        edited()
        resetTyping()
        val b = takeHeldSpace(before())
        clearSpaces()
        val found = w.glide(path, grid, b)
        if (found.isEmpty()) { showSuggestions(w); return }
        // "l'" + "homme" joins in French and Italian, as a typed word would.
        val elided = (b.endsWith("'") || b.endsWith("\u2019")) && w.startsWord(b)
        var upper = shift.uppercase
        // The space can start a sentence the editor didn't capitalise for yet ("Done."). It ends a
        // tapped word, which is learned as a space would learn it.
        if (SpaceRules.followsWord(b) && !elided && target.commit(" ")) { upper = upper || capsWanted(); learn(before(), 1) }
        unglided = null
        val readings = LinkedHashMap<String, String>()
        for (r in found) readings.putIfAbsent(if (shift.locked) r.uppercase() else if (upper) r.replaceFirstChar { it.uppercase() } else r, r)
        val first = readings.keys.first()
        if (!target.commit(first)) return
        showGlided(Glided(first, readings, before()))
        inputDone()
    }
    // The strip after a glide: the word that went in, highlighted in the middle, then the rest.
    private fun showGlided(g: Glided) {
        glided = g
        cancelSuggestions()
        strip(listOf(Suggestion(g.word, Suggestion.Kind.Fix)) +
            g.readings.keys.filter { it != g.word }.map { Suggestion(it, Suggestion.Kind.Word) })
    }

    /** A space-bar slide starts moving the cursor: the word in progress stays as typed. */
    /** A slide on space; with shift held it selects as it goes, and shift then types no capital. */
    fun cursorSlide(select: Boolean = false): CursorSlide {
        resetTyping(); clearSpaces(); clearStrip()
        val selecting = select && !secure
        if (selecting) shift.typed()
        return CursorSlide(target, minOf(selStart, selEnd), maxOf(selStart, selEnd), readable = !secure, selecting = selecting)
    }
    /** Shift is held: a slide on space selects. */
    val shiftHeld get() = shift.held

    /** A slide left from delete: null where it can't run (a password field, a selection, no known cursor). */
    fun deleteSlide(): DeleteSlide? {
        if (secure || selStart < 0 || selStart != selEnd || !target.connected) return null
        resetTyping(); clearSpaces(); clearStrip()
        return DeleteSlide(target, selStart, target.before(2000).orEmpty())
    }
    /** The delete slide lifted with words selected: they go. */
    fun deleteSelection() {
        if (!target.connected) return
        edited()
        target.commit(""); resetTyping(); clearStrip()
    }

    /** A clip from the toolbar's chip or the clipboard: it goes in as it is, never autocorrected, but its
     * links lose their tracking when Remove tracking from links is on. */
    fun paste(text: String) {
        if (!target.connected) return
        val clip = if (feedback.cleanLinks) Links.clean(text) else text
        edited(); resetTyping(); clearSpaces(); target.commit(clip); clearStrip(); refreshCaps()
    }

    private var actionsShown = emptyList<StripAction>()
    private var snippetsStored: String? = null
    private var snippetList = Snippets.starter
    /** Your text shortcuts, read again only when the setting changes. */
    private fun snippets(): List<Snippet> {
        val stored = feedback.snippets
        if (stored != snippetsStored) { snippetsStored = stored; snippetList = Snippets.read(stored) }
        return snippetList
    }
    /** The strip's chips: what to do with a selection, else the answer to a sum or a conversion of an
     * amount just typed ([QuickMath]), the text shortcuts being typed ([Snippets]), and free times after
     * "free" ([FreeTimes]). */
    private fun offerActions() {
        val list = when {
            secure || !target.connected -> emptyList()
            selStart >= 0 && selStart != selEnd -> StripAction.selection
            numpad -> emptyList()
            else -> target.before(QuickMath.READ)?.let { listOfNotNull(QuickMath.offer(it)) + Snippets.offer(it, snippets()) + freeTimes(it) }
                .orEmpty().take(3)
        }
        if (list == actionsShown) return
        actionsShown = list
        surface?.actions(list)
    }

    /** A chip on the strip was tapped. */
    fun act(action: StripAction) {
        if (!target.connected) return
        edited(); resetTyping(); clearSpaces()
        when (action) {
            StripAction.Cut -> target.contextAction(android.R.id.cut)
            StripAction.Copy -> {
                target.contextAction(android.R.id.copy)
                // Copied, the selection is done with: the caret goes to its end.
                val end = maxOf(selStart, selEnd)
                if (end >= 0) target.setSelection(end, end)
            }
            is StripAction.Insert -> {
                val at = maxOf(selStart, selEnd)
                target.beginBatch()
                try {
                    if (action.replace > 0) target.deleteBefore(action.replace)
                    target.commit(action.text)
                    val select = action.select
                    if (select != null && at >= 0) {
                        val start = at - action.replace
                        target.setSelection(start + select.first, start + select.last + 1)
                    }
                } finally { target.endBatch() }
            }
        }
        clearStrip(); refreshCaps()
    }

    /** Dictation starts: the word in progress is left as typed. */
    fun clearForDictation() { finishWatch(read = true); edited(); resetTyping(); clearSpaces(); clearStrip() }

    /** The editor or the keyboard went away: forget the word, never carry it to the next field. */
    /** A panel (emoji, the grid's menu, the clipboard) takes the keys' place. The word in progress stays,
     * so closing it untouched brings back the word's suggestions ([configureTyping] with keepWord). */
    fun pause() { cancelChip(); clearStrip() }

    fun leave() {
        finishWatch(read = true)
        edited()
        if (typedHere) learnAddressBefore(before())
        resetTyping(); clearSpaces(); word = null; clearStrip()
        main.removeCallbacks(fixPause); fixRun++; fixOffer = null
    }

    /** Dictated text with only the separators missing at the cursor; mid-sentence its first word loses
     * the capital Whisper gave it ([SpokenText.continuing], [language] as dictated or "en" translated).
     * With Say "send it" to send on, a dictation ending in it presses the field's send key ([SpokenSend]).
     * One ending in "scratch that" ([SpokenEdit]) takes it back, or the dictation before when it is all
     * that was said. */
    fun insertDictation(text: String, language: String?) {
        if (!target.connected) return
        val send = spokenSendAction()
        val body = if (send != null) SpokenSend.strip(text) else null
        if (send != null && body != null) {
            // Sent text can't be taken back, so no undo; nothing is sent when the text didn't go in.
            if (body.isEmpty() || insertSpoken(body, language, offer = false)) target.performEditorAction(send)
            return
        }
        val scratched = if (secure) null else SpokenEdit.scratched(text)
        when {
            scratched == null -> insertSpoken(text, language, offer = true)
            scratched.isEmpty() && offer is Dictated -> undo()
        }
    }
    // The send key a dictation may press: the enter key's own action when it sends or goes, never in a password field.
    private fun spokenSendAction(): Int? {
        if (!feedback.sayToSend || secure) return null
        return EnterKey.from(editor).action.takeIf { it == EditorInfo.IME_ACTION_SEND || it == EditorInfo.IME_ACTION_GO }
    }
    private fun insertSpoken(text: String, language: String?, offer: Boolean): Boolean {
        val before = if (secure) null else target.before(64)
        // The keyboard's own word list for that language, when loaded, knows far more ordinary words than
        // the speech side's short list: a word it has in lowercase and the books rarely capitalise
        // loses Whisper's capital; a name keeps it (iOS asks UITextChecker the same).
        val model = models[if (language == "no") "nb" else language]?.model
        val shaped = if (model == null) SpokenText.continuing(text, before, language)
            else SpokenText.continuing(text, before, language) { model.index(it) >= 0 && (model.capitals?.percent(it) ?: -1) < 50 }
        val result = insertion(shaped, before?.lastOrNull(), target.after(1)?.firstOrNull())
        if (result.isEmpty() || !target.commit(result)) return false
        if (offer && !secure) offerUndo(Dictated(result, target.after(16).orEmpty()))
        if (offer) beginWatch(result)
        return true
    }

    // Fixes to a dictation are learned in the fields that learn, while Learn from my corrections is on.
    private val watch = DictationWatch(clock)
    private val readWatch = Runnable { target.all()?.let { watch.sample(it.text) } }
    private val endWatch = Runnable { finishWatch(read = true) }
    private fun learnsCorrections() = learnHere && feedback.learnFromCorrections
    private fun beginWatch(inserted: String) {
        dropWatch()
        if (!learnsCorrections()) return
        target.all()?.let { watch.begin(inserted, it.text) }
        if (watch.active) main.postDelayed(endWatch, DictationWatch.WATCH_MS)
    }
    /** Forgets the dictation being followed, teaching nothing. */
    internal fun dropWatch() { main.removeCallbacks(readWatch); main.removeCallbacks(endWatch); watch.drop() }
    private fun watchChanged() {
        if (!learnsCorrections()) { dropWatch(); return }
        main.removeCallbacks(readWatch); main.postDelayed(readWatch, WATCH_PAUSE_MS)
    }
    /** Ends the watch: the field is read once more when [read], and what was fixed goes to [corrected]. */
    internal fun finishWatch(read: Boolean = false) {
        if (!watch.active) return
        main.removeCallbacks(readWatch); main.removeCallbacks(endWatch)
        if (read && learnsCorrections() && target.connected) target.all()?.let { watch.sample(it.text) }
        if (!learnsCorrections()) { watch.drop(); return }
        val fix = watch.finish() ?: return
        if (Corrections.learn(learned, fix.words)) personalWordsChanged()
        if (fix.words.isNotEmpty()) learnedChanged()
        corrected(fix)
    }
    /** Reads the field now, as the pause after an edit does. */
    internal fun sampleWatch() = readWatch.run()

    /** The undo chip. Undo dictation: the last dictation goes, if it is still right before the cursor with
     * nothing selected. Undo fixes: each fixed word goes back, if the field hasn't changed since. Returns
     * the words that are back. */
    fun undo(): List<String> {
        val o = offer ?: return emptyList()
        offerUndo(null)
        when (o) {
            is Dictated -> if (undoable(o) && replace(o.text, "")) { resetTyping(); clearSpaces(); showPredictions(before()) }
            is Fixed -> {
                val now = fieldText()
                if (now == null || now.start != o.field.start || now.text != o.result) return emptyList()
                // Each fix's place in the fixed text, and the edit that takes it back.
                var shift = 0
                val back = o.fixes.map { fix ->
                    val from = fix.from + shift
                    shift += fix.replacement.length - fix.original.length
                    Fix(from, from + fix.replacement.length, fix.replacement, fix.original)
                }
                edited(); resetTyping(); clearSpaces()
                rewrite(now, back)
                return o.fixes.map { it.original }
            }
        }
        return emptyList()
    }

    /** The text the fix button checks, or null where it mustn't read: a password field or no editor. */
    fun fieldText(): FieldText? {
        if (secure || !target.connected || selStart < 0 || selEnd < 0) return null
        val before = target.before(FIX_WINDOW) ?: return null
        val selected = if (selStart == selEnd) "" else target.selected() ?: return null
        val after = target.after(FIX_WINDOW) ?: return null
        return FieldText(before + selected + after, minOf(selStart, selEnd) - before.length, selStart, selEnd)
    }

    // The fix chip: the text is checked once typing and the caret have been still for FIX_PAUSE_MS, off the
    // main thread, and only the text that was checked can show the chip. Any edit or caret move drops the
    // chip and the run in flight, so it never outlives the issue it was shown for.
    private class FixOffer(val field: FieldText, val fixes: List<Fix>)
    private var fixOffer: FixOffer? = null
    private var fixRun = 0
    private val fixPause = Runnable { findFixes() }

    /** The text, the caret or the setting changed: the chip goes and the text is checked again after a pause. */
    internal fun fixesStale() {
        main.removeCallbacks(fixPause)
        fixRun++
        if (fixOffer != null) { fixOffer = null; surface?.fixOffer(false) }
        if (fixFinder != null && wordsHere && target.connected && fixOn()) main.postDelayed(fixPause, FIX_PAUSE_MS)
    }

    /** Checks the text now, as the pause after an edit does, and shows the chip if the fix button would change it. */
    internal fun findFixes() {
        val finder = fixFinder ?: return
        if (!wordsHere || selStart != selEnd || !fixOn()) return
        val field = fieldText()?.takeIf { it.text.isNotBlank() } ?: return
        val run = fixRun
        finder.find(field, { run != fixRun }) { fixes ->
            if (run == fixRun && fixes.isNotEmpty() && fieldText() == field) {
                fixOffer = FixOffer(field, fixes)
                surface?.fixOffer(true)
            }
        }
    }

    /** The fix chip was tapped: the fixes it was shown for go in, with Undo fixes after. */
    fun applyFixOffer() {
        val offered = fixOffer ?: return
        if (!applyFixes(offered.field, offered.fixes)) fixesStale()
    }

    /** Makes the fix button's [fixes] to [field] as it was checked, or nothing if it has changed since. */
    fun applyFixes(field: FieldText, fixes: List<Fix>): Boolean {
        if (fixes.isEmpty() || fieldText() != field) return false
        edited(); resetTyping(); clearSpaces()
        rewrite(field, fixes)
        clearStrip()
        offerUndo(Fixed(field, fixes, Tinyfix.apply(field.text, fixes)))
        return true
    }

    /** Replaces each edit's span of [field] in one batch, last first so earlier places hold, and puts the
     * selection back where it was in the changed text. */
    private fun rewrite(field: FieldText, edits: List<Fix>) {
        fun moved(position: Int): Int {
            var shift = 0
            for (e in edits) {
                val from = field.start + e.from
                val to = field.start + e.to
                if (to <= position) shift += e.replacement.length - e.original.length
                else if (from < position) return from + e.replacement.length + shift
            }
            return position + shift
        }
        target.beginBatch()
        try {
            for (e in edits.sortedByDescending { it.from }) {
                target.setSelection(field.start + e.from, field.start + e.to)
                target.commit(e.replacement)
            }
            target.setSelection(moved(field.selStart), moved(field.selEnd))
        } finally { target.endBatch() }
    }
    private fun undoable(d: Dictated): Boolean {
        if (secure || !target.connected || target.selected()?.isNotEmpty() == true || !endsWith(d.text)) return false
        val after = target.after(d.after.length).orEmpty()
        return after.startsWith(d.after) || d.after.startsWith(after)
    }
    private fun offerUndo(next: Offer?) {
        main.removeCallbacks(dropDictation)
        val shown = offer != null
        offer = next
        if (next != null) main.postDelayed(dropDictation, UNDO_MS)
        if (shown || next != null) surface?.undo(when (next) {
            is Dictated -> UndoOffer.Dictation
            is Fixed -> UndoOffer.Fixes
            null -> null
        })
    }
    /** Any edit through the keys ends the undo offer. */
    private fun edited() { if (offer != null) offerUndo(null) }
}
