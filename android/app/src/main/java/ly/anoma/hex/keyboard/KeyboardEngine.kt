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
import ly.anoma.hex.typing.KeyGrid
import ly.anoma.hex.typing.KeyPoint
import ly.anoma.hex.typing.LearnedWords
import ly.anoma.hex.typing.PersonalWords
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.typing.TouchOffsets
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences
import ly.anoma.hex.typing.TypingWord

/** What the engine shows: the keys' page and the strip's slots. */
internal interface KeyboardSurface {
    fun showPage(page: KeyboardPage)
    /** The strip, in the typing model's order: what was typed first when it shows, then the fix or the
     * likeliest word, then the rest. Next words after a space are [Suggestion.Kind.Next]. */
    fun suggestions(slots: List<Suggestion>)
    /** Offer an undo, or stop offering it. */
    fun undo(offer: UndoOffer?)
    /** Chips for the selection or what was just typed, in the suggestions' place; none clears them. */
    fun actions(list: List<StripAction>) {}
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
    /** Free times from the calendar for the text before the caret ([FreeTimes]), none while that's off. */
    private val freeTimes: (String) -> List<StripAction> = { emptyList() },
    private val background: Executor = Executor { it.run() },
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
    private val main: Handler = Handler(Looper.getMainLooper()),
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
        /** A pause this long in typing starts another undo step even mid-word. */
        const val STEP_GAP_MS = 2_000L
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
        private fun <T> edit(block: () -> T): T { editedAt = clock(); return block() }
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
        private fun <T> edit(block: () -> T): T { editedAt = clock(); return block() }
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
    // (another key, a tap in the text) cancels it: a space for double-space, a picked suggestion's
    // own space, and a space a pick held because the text after the cursor already separates it.
    private var spaceAnchor: String? = null
    private var spaceAt = 0L
    private var pickedAnchor: String? = null
    private var heldAnchor: String? = null
    /** The word a glide put in and its other readings, while [anchor], the text before the cursor right
     * after it, is still there: delete takes the word back, a pick replaces it, and the next word,
     * tapped or glided, puts a space in first while a mark goes against it (Gboard's phantom space). */
    private class Glided(val word: String, val readings: List<String>, val anchor: String)
    private var glided: Glided? = null
    var secure = true
        private set
    // Email addresses are offered and learned in text fields that allow suggestions and learning; an
    // address field offers them from the first letter, first in the strip.
    private var addressesHere = false
    private var addressField = false
    /** The addresses the strip offers and the text they complete, which a pick replaces. */
    private class AddressOffer(val typed: String, val addresses: List<String>)
    private var addressOffer: AddressOffer? = null
    /** The word the strip's emoji are for, which a picked one is learned for. */
    private var emojiWord: String? = null
    // Each address is learned once per field, and leaving it teaches only after something was typed there.
    private val addressesLearned = HashSet<String>()
    private var typedHere = false

    fun startInput(attribute: EditorInfo) {
        edited()
        forgetSteps(); batches = 0
        editor = attribute
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
        learnHere = autocorrectHere && attribute.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
        addressesHere = kind == InputType.TYPE_CLASS_TEXT && !secure &&
            attribute.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0 &&
            variation !in setOf(InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_FILTER) &&
            attribute.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0
        addressField = addressesHere && variation in setOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        addressesLearned.clear(); typedHere = false
        resetTyping(); word = null; clearSpaces()
        numpad = kind == InputType.TYPE_CLASS_NUMBER || kind == InputType.TYPE_CLASS_PHONE ||
            kind == InputType.TYPE_CLASS_DATETIME
        symbols = numpad
        more = false; symbolTyped = false; shift.reset()
        selStart = attribute.initialSelStart; selEnd = attribute.initialSelEnd
        refreshCaps(force = true)
    }

    fun updateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int) {
        selStart = newSelStart; selEnd = newSelEnd
        if (oldSelStart != newSelStart || oldSelEnd != newSelEnd) offerActions()
        (offer as? Dictated)?.let { if (!undoable(it)) offerUndo(null) }
        val expected = expectedCaret >= 0 && newSelStart == expectedCaret && newSelEnd == expectedCaret
        if (oldSelStart != newSelStart || oldSelEnd != newSelEnd) {
            if (expectedCaret >= 0 && (newSelStart != expectedCaret || newSelEnd != expectedCaret)) {
                // Editors may coalesce selection notifications. Compare the actual surrounding text
                // before invalidating our own in-flight commit, not a stale intermediate offset.
                val suffix = undo?.let { it.replacement + " " } ?: word?.token?.takeIf { it.isNotEmpty() }?.let { prefix + it }
                if (newSelStart != newSelEnd || suffix == null || !endsWith(suffix) ||
                    oldSelStart == expectedCaret) { resetTyping(); surface?.suggestions(emptyList()) }
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
        if (symbols || selStart < 0 || selStart != selEnd) { surface?.suggestions(emptyList()); return }
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
        surface?.suggestions(strip)
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
        it.learned = learned; it.model.neverSuggest.hide(forgotten.all()); loadPersonal(it)
        if (it.model.language == Spoken.Hinglish.code) background.execute {
            it.english = try { loadDecoder(Spoken.English.code).model } catch (_: java.io.IOException) { null }
        }
    }
    /** Android's personal dictionary changed: every loaded language reads it again, off the main thread. */
    fun personalWordsChanged() { for (decoder in models.values) loadPersonal(decoder) }
    private fun loadPersonal(decoder: SpatialDecoder, then: () -> Unit = {}) {
        val read = readPersonal ?: return
        val model = decoder.model
        background.execute {
            decoder.personal = read(model, learned.words().filter { it !in forgotten && model.index(it.lowercase()) < 0 && !model.known(it.lowercase()) })
            then()
        }
    }

    /** [word], long-pressed on the strip, is forgotten: no longer suggested or corrected to, dropped from
     * what you wrote, and not learned again from one slip. A user action, so it works in any field. */
    fun forget(word: String) {
        if (!forgotten.add(word)) return
        learned.forgetWord(word)
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
     * one, or put back by undo. Forgotten words come back when you choose them. */
    private fun teach(word: String) {
        if (!learnHere) return
        if (forgotten.remove(word)) { forgottenChanged(); for (d in models.values) d.model.neverSuggest.hide(forgotten.all()) }
        repeat(LearnedWords.WORD_USES) { learned.learn(word, null, false) }
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
     * space or mark that just went in after it, or none) follow it. */
    private fun learn(b: String, boundary: Int) {
        if (word == null || !learnHere || b.length <= boundary || !b[b.length - 1 - boundary].isLetter()) return
        val f = LearnedWords.finished(b) ?: return
        word?.takeTouched()?.let { t -> if (TouchOffsets.alike(t.typed, f.word) && touch.learn(f.word, t.points, t.grid)) learnedChanged() }
        if (f.word in forgotten) return
        if (learned.learn(f.word, f.previous, f.sentenceStart, f.beforePrevious)) personalWordsChanged()
        learnedChanged()
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
        resetTyping(); clearSpaces(); surface?.suggestions(emptyList()); more = false; shift.forgetTap(); symbolTyped = false
    }
    private fun resetTyping() {
        cancelChip(); addressOffer = null; word?.reset(); undo = null; expectedCaret = -1; prefix = ""; tapped = null
    }
    private fun clearSpaces() { spaceAnchor = null; pickedAnchor = null; heldAnchor = null; glided = null }
    private fun cancelChip() { pendingChip?.let(main::removeCallbacks); pendingChip = null }
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
        word = if (wordsHere) {
            try {
                TypingWord(models.getOrPut(language) { prepared(loadDecoder(language)) }, feedback.spatial)
                    .also { it.suggestSwearWords = feedback.suggestSwearWords; it.addsCapitals = feedback.autoCapitalize }
            } catch (_: java.io.IOException) { null } // The editor can still type if its assets cannot be read.
        } else null
        showPlane()
        showSuggestions(word)
    }

    // The strip while a word is typed: completions at once, then, after a pause, the model's whole
    // strip with what space will put in. A fix you turned down twice shows as a plain word.
    private fun showSuggestions(w: TypingWord?) {
        cancelChip()
        if (w != null && w.token.isNotEmpty()) {
            showStrip(w.strip(autocorrect = false))
            if (!feedback.autocorrect || !canEndWord()) return
            val token = w.token
            pendingChip = Runnable {
                pendingChip = null
                if (word === w && w.token == token) showStrip(w.strip(autocorrect = true).map {
                    if (it.kind == Suggestion.Kind.Fix && (!autocorrectHere || QuickMath.endsInUnit(before()) ||
                            feedback.blocked(w.decoder.model.language, token, it.text)))
                        Suggestion(it.text, Suggestion.Kind.Word) else it
                })
            }.also { main.postDelayed(it, CHIP_PAUSE_MS) }
            return
        }
        val b = if (w != null && !symbols && selStart == selEnd) before() else null
        showPredictions(if (b != null && TypingWord.boundary(b.lastOrNull())) b else null)
    }
    /** Next words for the text before the cursor, capitalised as shift would be. */
    private fun showPredictions(before: String?) {
        val w = word
        showStrip(if (w != null && before != null && !symbols && canEndWord())
            w.predictions(before, capitalize = capsWanted()).map { Suggestion(it, Suggestion.Kind.Next) } else emptyList())
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
        if (found.isEmpty()) { surface?.suggestions(slots + emojiFor(slots)); return }
        addressOffer = AddressOffer(typed!!, found)
        val offered = found.map { Suggestion(it, Suggestion.Kind.Word) }
        surface?.suggestions(if (addressField) (offered + slots).take(3) else slots.take(2) + offered)
    }
    /** Emoji for the word being typed or one of the strip's words for it ("medicin" finds 💊 through
     * "medicine"), or after a space for the word before it, as Gboard offers them. */
    private fun emojiFor(slots: List<Suggestion>): List<Suggestion> {
        val w = word ?: return emptyList()
        if (symbols || selStart != selEnd) return emptyList()
        val lists = emojiLists(w.decoder.model.language) ?: return emptyList()
        val words = if (w.token.isNotEmpty()) listOf(w.token) + slots.filter { it.kind == Suggestion.Kind.Fix || it.kind == Suggestion.Kind.Word }.map { it.text }
            else before().takeIf { it.endsWith(" ") }?.let(EmojiWords::wordBefore)?.let(::listOf) ?: return emptyList()
        val found = lists.find(words, emojiPicks) ?: return emptyList()
        emojiWord = found.word
        return found.emoji.map { Suggestion(it, Suggestion.Kind.Emoji) }
    }

    /** An emoji on the strip was tapped. A word being typed ends first as space would end it, fix and
     * all, then the emoji goes in with its own space, as a picked word does; it's learned for its word. */
    fun pickEmoji(emoji: String) {
        if (!target.connected) return
        edited(); typedHere = true
        val learnFor = emojiWord
        val w = word
        if (w != null && !symbols && w.token.isNotEmpty() && endsWith(prefix + w.token)) typeSpace(null, null)
        takeHeldSpace(before())
        clearSpaces(); resetTyping()
        val holds = SpaceRules.separates(target.after(1))
        if (!target.commit(if (holds) emoji else "$emoji ")) return
        if (learnFor != null && learnHere) { emojiPicks.pick(learnFor, emoji); emojiPicked() }
        val b = before()
        if (holds) heldAnchor = b else pickedAnchor = b
        showPredictions(b); inputDone()
    }

    /** An emoji from the emoji panel: typed as it is, and learned for the word right before it, if any. */
    fun emoji(text: String) {
        // The word before the cursor, typed then or before; the field's kind says whether to learn.
        val after = if (learnHere && selStart == selEnd) EmojiWords.wordBefore(before()) else null
        type(text)
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
        return w.likelierKey(before(), hit, near, keys)
    }
    /** Shift as the keys show it: letters type capitals while it is on or held. */
    val uppercase get() = shift.uppercase

    fun letter(text: String, x: Double, y: Double, grid: KeyGrid, explicit: Boolean = false) {
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

    /** A strip slot was tapped. What was typed, a fix or a word goes in with its space, unless the text
     * after the cursor already starts with a space or closing mark: then the space waits for the next
     * key that starts a word. A space right after a picked word is swallowed (it still counts towards
     * double-space), and a closing mark takes its place. */
    fun pickSuggestion(text: String) {
        edited()
        addressOffer?.takeIf { text in it.addresses }?.let { pickAddress(it.typed, text); return }
        tapped?.let { pickForTapped(it, text); return }
        glided?.takeIf { it.anchor == before() }?.let { pickForGlided(it, text); return }
        val w = word ?: return
        if (symbols || !canEndWord()) return
        val old = w.token
        if (old.isNotEmpty() && !endsWith(prefix + old)) { resetTyping(); surface?.suggestions(emptyList()); return }
        if (old.isEmpty() && !w.startsWord(before())) return
        val holds = SpaceRules.separates(target.after(1))
        val taught = if (old.isEmpty()) null else taught(w, old, text)
        clearSpaces()
        if (replace(old, if (holds) text else "$text ")) {
            resetTyping()
            val b = before()
            if (holds) heldAnchor = b else pickedAnchor = b
            learn(b, if (holds) 0 else 1)
            taught?.let(::teach)
            showPredictions(b)
            inputDone()
        }
    }

    // An offered address takes the place of what was typed of it; in an address field nothing follows
    // it, elsewhere a space does, as after a picked word.
    private fun pickAddress(typed: String, address: String) {
        resetTyping(); clearSpaces()
        val holds = addressField || SpaceRules.separates(target.after(1))
        if (!replace(typed, if (holds) address else "$address ")) { surface?.suggestions(emptyList()); return }
        learnAddress(address)
        val b = before()
        if (addressField) surface?.suggestions(emptyList())
        else { if (holds) heldAnchor = b else pickedAnchor = b; showPredictions(b) }
        inputDone()
    }

    // Another reading takes the glided word's place, and the next word still puts its space in first.
    private fun pickForGlided(g: Glided, text: String) {
        if (text == g.word || !replace(g.word, text)) return
        showGlided(Glided(text, g.readings, before()))
    }

    // The tapped word goes whole, both sides of the caret, and the pick takes its place with no space.
    private fun pickForTapped(t: Tapped, text: String) {
        resetTyping()
        if (!secure && target.before(t.left.length) == t.left && target.after(t.right.length) == t.right &&
            target.selected().isNullOrEmpty()) {
            target.beginBatch()
            try {
                if (target.deleteAround(t.left.length, t.right.length) && target.commit(text)) {
                    if (learnHere) { learned.learn(text, null, false); learnedChanged() }
                }
            } finally { target.endBatch() }
        }
        surface?.suggestions(emptyList())
    }

    /** The space bar, tapped at [point] in [grid]'s coordinates (null off the letters page). */
    fun space(point: KeyPoint? = null, grid: KeyGrid? = null) {
        if (!target.connected) return
        typeSpace(point, grid)
        if (symbols && !numpad && symbolTyped) {
            symbols = false; more = false; symbolTyped = false
            showPlane(); showPredictions(before())
        }
    }
    private fun typeSpace(point: KeyPoint?, grid: KeyGrid?) {
        edited()
        val now = clock()
        val b = before()
        when {
            // The text after the cursor already separates the picked word: space has nothing to add.
            heldAnchor != null && heldAnchor == b -> { clearSpaces(); showPredictions(b); return }
            pickedAnchor != null && pickedAnchor == b -> { clearSpaces(); spaceAnchor = b; spaceAt = now; showPredictions(b); return }
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
                    cancelChip()
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

    fun type(value: String) {
        if (!target.connected) return
        edited(); typedHere = true
        if (value == " ") { space(); return }
        if (symbols && !numpad) symbolTyped = true
        val b = before()
        val mark = value.length == 1 && TypingWord.endsWord(value[0])
        // A clause-ending mark takes the space a picked suggestion typed ("Hello,").
        if (mark && value[0] in SpaceRules.CLAUSE_MARKS && pickedAnchor != null && pickedAnchor == b && b.endsWith(" ")) {
            clearSpaces()
            if (replace(" ", value)) { resetTyping(); showPredictions(before()); inputDone(); return }
        }
        // Anything that starts another word puts a held space in first; marks go against the word.
        if (value.none { it in SpaceRules.JOINERS || it.isWhitespace() }) takeHeldSpace(b)
        clearSpaces()
        val w = word
        if (mark && w != null && !symbols && canEndWord()) {
            if (feedback.autocorrect && autocorrectHere && w.token.isNotEmpty() && endsWith(prefix + w.token)) {
                val fix = w.finish(value[0])
                if (fix != null && !feedback.blocked(w.decoder.model.language, fix.original, fix.replacement) &&
                    replace(fix.original, fix.replacement + value)) { resetTyping(); afterMark(value); return }
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
    fun delete() {
        if (!target.connected) return
        edited()
        // Right after a glide, delete takes the whole word back; the space before it stays.
        glided?.takeIf { it.anchor == before() }?.let { g ->
            clearSpaces()
            if (replace(g.word, "")) { showSuggestions(word); return }
        }
        // Right after a pick held its space, delete takes that space back and deletes nothing.
        if (heldAnchor != null && heldAnchor == before()) { clearSpaces(); surface?.suggestions(emptyList()); return }
        clearSpaces()
        val last = undo
        undo = null
        if (last != null && endsWith(last.replacement + " ") && replace(last.replacement + " ", last.original)) {
            // What you put back is yours: it isn't corrected again (and, like the count, only where learning is allowed).
            if (learnHere) {
                feedback.undo(last.language, last.original, last.replacement)
                if (' ' !in last.original) teach(last.original)
            }
            val typed = last.original.substringAfterLast(' ')
            prefix = before().dropLast(typed.length).takeLast(40); word?.restored(typed, prefix)
            if (expectedCaret >= 0) expectedCaret += last.original.length - last.replacement.length - 1
            surface?.suggestions(listOf(Suggestion(typed, Suggestion.Kind.Typed))); return
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
        }
        if (w != null && w.token.isNotEmpty()) showSuggestions(w) else showStrip(emptyList())
    }
    /** Held delete once it speeds up ([DeleteRepeat]): the word before the cursor with the spaces
     * after it, as a delete slide counts words, taken one character at a time through [delete]. */
    fun deleteWord() {
        val before = if (secure) "" else target.before(64).orEmpty()
        val word = before.takeLast(DeleteSlide.wordLengths(before).firstOrNull() ?: 0)
        val characters = java.text.BreakIterator.getCharacterInstance().apply { setText(word) }
        var count = 0
        while (characters.next() != java.text.BreakIterator.DONE) count++
        repeat(maxOf(count, 1)) { delete() }
    }
    fun enter() {
        edited()
        learn(before(), 0); learnAddressBefore(before())
        resetTyping(); clearSpaces(); surface?.suggestions(emptyList())
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
    fun glide(path: List<KeyPoint>, grid: KeyGrid) {
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
        // The space can start a sentence the editor didn't capitalise for yet ("Done.").
        if (SpaceRules.followsWord(b) && !elided && target.commit(" ")) upper = upper || capsWanted()
        val readings = found.map { if (shift.locked) it.uppercase() else if (upper) it.replaceFirstChar { c -> c.uppercase() } else it }.distinct()
        if (!target.commit(readings[0])) return
        showGlided(Glided(readings[0], readings, before()))
        inputDone()
    }
    // The strip after a glide: the word that went in, highlighted in the middle, then the rest.
    private fun showGlided(g: Glided) {
        glided = g
        surface?.suggestions(listOf(Suggestion(g.word, Suggestion.Kind.Fix)) +
            g.readings.filter { it != g.word }.map { Suggestion(it, Suggestion.Kind.Word) })
    }

    /** A space-bar slide starts moving the cursor: the word in progress stays as typed. */
    /** A slide on space; with shift held it selects as it goes, and shift then types no capital. */
    fun cursorSlide(select: Boolean = false): CursorSlide {
        resetTyping(); clearSpaces(); surface?.suggestions(emptyList())
        val selecting = select && !secure
        if (selecting) shift.typed()
        return CursorSlide(target, minOf(selStart, selEnd), maxOf(selStart, selEnd), readable = !secure, selecting = selecting)
    }
    /** Shift is held: a slide on space selects. */
    val shiftHeld get() = shift.held

    /** A slide left from delete: null where it can't run (a password field, a selection, no known cursor). */
    fun deleteSlide(): DeleteSlide? {
        if (secure || selStart < 0 || selStart != selEnd || !target.connected) return null
        resetTyping(); clearSpaces(); surface?.suggestions(emptyList())
        return DeleteSlide(target, selStart, target.before(2000).orEmpty())
    }
    /** The delete slide lifted with words selected: they go. */
    fun deleteSelection() {
        if (!target.connected) return
        edited()
        target.commit(""); resetTyping(); surface?.suggestions(emptyList())
    }

    /** A clip from the toolbar's chip or the clipboard: it goes in as it is, never autocorrected, but its
     * links lose their tracking when Remove tracking from links is on. */
    fun paste(text: String) {
        if (!target.connected) return
        val clip = if (feedback.cleanLinks) Links.clean(text) else text
        edited(); resetTyping(); clearSpaces(); target.commit(clip); surface?.suggestions(emptyList()); refreshCaps()
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
        surface?.suggestions(emptyList()); refreshCaps()
    }

    /** Dictation starts: the word in progress is left as typed. */
    fun clearForDictation() { edited(); resetTyping(); clearSpaces(); surface?.suggestions(emptyList()) }

    /** The editor or the keyboard went away: forget the word, never carry it to the next field. */
    /** A panel (emoji, the grid's menu, the clipboard) takes the keys' place. The word in progress stays,
     * so closing it untouched brings back the word's suggestions ([configureTyping] with keepWord). */
    fun pause() { cancelChip(); surface?.suggestions(emptyList()) }

    fun leave() {
        edited()
        if (typedHere) learnAddressBefore(before())
        resetTyping(); clearSpaces(); word = null; surface?.suggestions(emptyList())
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
        return true
    }

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

    /** Makes the fix button's [fixes] to [field] as it was checked, or nothing if it has changed since. */
    fun applyFixes(field: FieldText, fixes: List<Fix>): Boolean {
        if (fixes.isEmpty() || fieldText() != field) return false
        edited(); resetTyping(); clearSpaces()
        rewrite(field, fixes)
        surface?.suggestions(emptyList())
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
