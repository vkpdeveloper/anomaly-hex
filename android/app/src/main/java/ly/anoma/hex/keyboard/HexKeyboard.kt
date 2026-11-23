package ly.anoma.hex.keyboard

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.UserDictionary
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ly.anoma.hex.R
import ly.anoma.hex.fix.TinyfixFiles
import ly.anoma.hex.relay.HexClient
import ly.anoma.hex.relay.HexError
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.ContactNames
import ly.anoma.hex.typing.EmailAddresses
import ly.anoma.hex.typing.EmojiWords
import ly.anoma.hex.typing.Forgotten
import ly.anoma.hex.typing.LearnedWords
import ly.anoma.hex.typing.PersonalWords
import ly.anoma.hex.typing.Replacements
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.TouchOffsets
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences

/** The input method: the editor's lifecycle, the views and dictation. Typing lives in [KeyboardEngine];
 * dictation records here and is transcribed by Hex on the paired computer ([Dictation]). */
class HexKeyboard : InputMethodService() {
    private var view: KeyboardView? = null
    internal val feedback by lazy { TypingPreferences(this) }
    internal val layoutLanguage get() = feedback.layout ?: Spoken.system()
    // Android's personal dictionary is read off the main thread, and again whenever it changes. Work
    // that arrives once the keyboard is destroyed (a save posted late) is dropped, never a crash.
    private val words = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
        { Thread(it, "Hex personal words") }, ThreadPoolExecutor.DiscardPolicy())
    private val dictionaryChanged = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = engine.personalWordsChanged()
    }
    // What you write, learned by the engine, saved a few seconds after it changes and topped up from the
    // paired computer's Hex History. Nothing is saved before the file has been read, so a save never loses it.
    private val learned = LearnedWords()
    // Where your taps land on the keys, learned and saved with [learned] and forgotten with it.
    private val touch = TouchOffsets()
    private val touchFile by lazy { File(filesDir, TouchOffsets.FILE) }
    private val learnedFile by lazy { File(filesDir, LearnedWords.FILE) }
    @Volatile private var learnedLoaded = false
    private val main = Handler(Looper.getMainLooper())
    private val saveLearned = Runnable { saveWords() }
    private fun saveWords() {
        val text = learned.write(); val touches = touch.write()
        words.execute { writeLearned(text, LearnedWords.FILE); writeLearned(touches, TouchOffsets.FILE) }
    }
    private fun learnedChanged() {
        if (!learnedLoaded) return
        main.removeCallbacks(saveLearned); main.postDelayed(saveLearned, SAVE_DELAY_MS)
    }
    private fun writeLearned(text: String, name: String) {
        val partial = File(filesDir, "$name.partial")
        runCatching { partial.writeText(text); partial.renameTo(File(filesDir, name)) }
    }
    // The words you forgot from the strip, saved at once (a user action, rare) and read with the learned ones.
    private val forgotten = Forgotten()
    private fun forgottenChanged() {
        val text = forgotten.write()
        words.execute {
            val partial = File(filesDir, Forgotten.FILE + ".partial")
            runCatching { partial.writeText(text); partial.renameTo(File(filesDir, Forgotten.FILE)) }
        }
    }
    // The emoji you pick for words, kept beside the emoji panel's recent ones, and each layout's emoji
    // lists, read on first use (English's, and Hinglish's own with English's).
    private val emojiPrefs by lazy { getSharedPreferences("emoji", MODE_PRIVATE) }
    private val emojiPicks by lazy { EmojiWords.Picks.read(emojiPrefs.getString(EMOJI_PICKS, "").orEmpty()) }
    private val emojiLists = HashMap<String, EmojiWords>()
    private fun emojiLists(language: String): EmojiWords? {
        emojiLists[language]?.let { return it }
        val names = when (language) {
            Spoken.English.code -> listOf("en")
            Spoken.Hinglish.code -> listOf("hien", "en")
            else -> return null
        }
        val paint = android.graphics.Paint()
        return EmojiWords(names.map { EmojiWords.parse(assets.open("typing/$it.emoji").bufferedReader().use { r -> r.readText() }) },
            shows = paint::hasGlyph).also { emojiLists[language] = it }
    }
    // The email addresses the strip offers: the phone's accounts, read once, and your contacts' while
    // Settings › Keyboard's switch and the Contacts permission are both on, read again a little after
    // they change (a sync changes many at once).
    private val emails = EmailAddresses()
    // Your contacts' names are words too: typed or swiped right, completed with their capitals. In memory only.
    @Volatile private var contactNames: List<String> = emptyList()
    private val readContacts = Runnable {
        words.execute {
            val allowed = contactsAllowed()
            emails.contacts = if (allowed) EmailAddresses.contacts(contentResolver) else emptyList()
            contactNames = if (allowed) ContactNames.read(contentResolver) else emptyList()
            main.post { engine.personalWordsChanged() }
        }
    }
    private val contactsChanged = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { main.removeCallbacks(readContacts); main.postDelayed(readContacts, CONTACTS_DELAY_MS) }
    }
    private var watchingContacts = false
    private fun contactsAllowed() = feedback.contactEmails && checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    private fun contactsSettingChanged() {
        val wanted = contactsAllowed()
        if (wanted != watchingContacts) {
            watchingContacts = wanted
            try {
                if (wanted) contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, contactsChanged)
                else contentResolver.unregisterContentObserver(contactsChanged)
            } catch (_: SecurityException) { watchingContacts = false }
        }
        main.removeCallbacks(readContacts); readContacts.run()
    }
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var fetching = false
    /** The computer's words, at most every few hours, while Learn from Hex history is on and a computer is paired. */
    private fun fetchHistoryWords(force: Boolean = false) {
        if (!learnedLoaded || fetching || !feedback.learnFromHistory) return
        if (!force && System.currentTimeMillis() - feedback.historyWordsAt < HISTORY_WORDS_MS) return
        fetching = true
        io.launch {
            try {
                val got = dictation.router.send { host, computer -> HexClient.words(host, computer.port, computer.code) }
                if (!feedback.learnFromHistory) return@launch
                // The computer's Vocabulary names are words you use there, so they are words here.
                learned.seed(got.words + Corrections.termWords(got.vocabulary).map { it.first to VOCABULARY_COUNT }, got.pairs)
                forgotten.all().forEach(learned::forgetWord)
                feedback.historyWordsAt = System.currentTimeMillis()
                engine.learnedWordsChanged(); learnedChanged()
                sendCorrections()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Not paired, unreachable, or a computer without the words route: try again next time.
            } finally { fetching = false }
        }
    }
    // Fixes to dictated text the computer hasn't taken yet: a few, kept on the phone until the next contact.
    private val pendingCorrections = PendingCorrections()
    private var sendingCorrections = false
    private fun correctionFound(fix: DictationFix) { pendingCorrections.add(fix); pendingChanged(); sendCorrections() }
    private fun pendingChanged() {
        if (!learnedLoaded) return
        val text = pendingCorrections.write()
        words.execute { writeLearned(text, PendingCorrections.FILE) }
    }
    /** Sends the waiting corrections, oldest first, and learns the terms the computer took from them. A
     * computer that can't be reached keeps them; one that refuses a correction (an older Hex) drops it. */
    private fun sendCorrections() {
        if (sendingCorrections || pendingCorrections.size == 0) return
        sendingCorrections = true
        io.launch {
            try {
                while (feedback.learnFromCorrections) {
                    val fix = pendingCorrections.first() ?: break
                    try {
                        val terms = dictation.router.send { host, computer -> HexClient.correct(host, computer.port, computer.code, fix.baseline, fix.inserted, fix.edited) }
                        pendingCorrections.remove(fix); pendingChanged()
                        if (Corrections.learn(learned, Corrections.termWords(terms))) engine.learnedWordsChanged()
                        if (terms.isNotEmpty()) learnedChanged()
                    } catch (_: HexError.Failed) {
                        pendingCorrections.remove(fix); pendingChanged()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Not paired or unreachable: they wait for the next contact.
            } finally { sendingCorrections = false }
        }
    }
    private var fetchingReplacements = false
    /** The computer's text replacements, as shortcuts on the strip, at most hourly while a computer is paired. */
    private fun fetchReplacements() {
        if (fetchingReplacements || System.currentTimeMillis() - feedback.replacementsAt < REPLACEMENTS_MS) return
        fetchingReplacements = true
        io.launch {
            try {
                val got = dictation.router.send { host, computer -> HexClient.replacements(host, computer.port, computer.code) }
                val stored = Replacements.write(got)
                if (stored != feedback.replacements) feedback.replacements = stored
                feedback.replacementsAt = System.currentTimeMillis()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Not paired, unreachable, or a computer without the replacements route: try again next time.
            } finally { fetchingReplacements = false }
        }
    }
    // The editor's text, read from a copy kept here while it holds: a read through the InputConnection waits on the app.
    private val text by lazy { CachedText(InputConnectionTarget(this), SystemClock::uptimeMillis) }
    internal val engine by lazy {
        KeyboardEngine(text, feedback, { layoutLanguage }, { SpatialDecoder(TypingModel.asset(assets, it)) },
            readPersonal = { model, mine -> PersonalWords.read(contentResolver, model, mine, Replacements.read(feedback.replacements), contactNames, forgotten) },
            learned = learned, touch = touch, learnedChanged = ::learnedChanged, forgotten = forgotten, forgottenChanged = ::forgottenChanged,
            emails = emails, emojiLists = ::emojiLists, emojiPicks = emojiPicks,
            emojiPicked = { emojiPrefs.edit().putString(EMOJI_PICKS, emojiPicks.write()).apply() },
            freeTimes = { before ->
                if (!feedback.freeTimes || !FreeTimes.allowed(this)) emptyList()
                else FreeTimes.offer(before, java.time.LocalDateTime.now(), android.text.format.DateFormat.is24HourFormat(this)) {
                    FreeTimes.busy(contentResolver, it)
                }
            }, corrected = ::correctionFound, background = words)
    }
    internal val dictation by lazy {
        Dictation(this,
            show = { phase ->
                if (phase == DictationPhase.Listening) { engine.clearForDictation(); dictatedFor = field }
                view?.dictation(phase)
            },
            level = { view?.level(it) },
            note = { view?.note(it) },
            insert = ::commitSpeech,
            blocked = { if (engine.secure) getString(R.string.keyboard_hint_password) else null },
            origin = { HexClient.Origin.of(this, currentInputEditorInfo?.packageName, HexClient.Origin.language(layoutLanguage)) })
    }
    internal val clips by lazy { ClipKeeper(this, changed = { view?.clipsChanged() }, note = { view?.note(it) }) }
    private val clipsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ClipKeeper.RECENT_PHOTOS) clips.syncPhotos()
    }
    private val typingChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            TypingPreferences.SUGGEST_SWEAR_WORDS -> engine.swearWordsChanged()
            // Settings deleted the file; this copy goes too.
            TypingPreferences.FIX_BUTTON -> {
                if (!feedback.fixButton) fixWork.execute { TinyfixFiles.drop() }
                view?.fixChanged()
            }
            TypingPreferences.FORGET_LEARNED -> {
                main.removeCallbacks(saveLearned); learned.clear(); touch.clear(); engine.learnedWordsChanged()
                forgotten.clear(); engine.hiddenChanged()
                engine.dropWatch(); pendingCorrections.clear(); pendingChanged()
                emojiPicks.clear(); emojiPrefs.edit().remove(EMOJI_PICKS).apply()
            }
            TypingPreferences.LEARN_FROM_HISTORY ->
                if (feedback.learnFromHistory) fetchHistoryWords(force = true)
                else { learned.seed(emptyList(), emptyList()); engine.learnedWordsChanged(); learnedChanged() }
            TypingPreferences.LEARN_FROM_CORRECTIONS -> if (!feedback.learnFromCorrections) { pendingCorrections.clear(); pendingChanged() }
            TypingPreferences.CONTACT_EMAILS -> contactsSettingChanged()
            TypingPreferences.REPLACEMENTS -> engine.personalWordsChanged()
            TypingPreferences.NUMBER_ROW -> view?.numberRowChanged()
        }
    }
    internal val previewAllowed get() = !engine.secure

    // The fix button: its checker ([TinyfixFiles.withFixer], shared with the spell checker) is read from
    // the downloaded files on first use, on its own thread, and dropped when the button goes off.
    private val fixWork = Executors.newSingleThreadExecutor { Thread(it, "Hex fix") }
    // Hinglish's Hindi words, those its typing model has and English's lacks: tinyfix knows only English
    // and would "fix" them ("nahi" into "nazi"). Read on first use, on fixWork.
    private val hindiWords: (String) -> Boolean by lazy {
        val hinglish = TypingModel.asset(assets, Spoken.Hinglish.code)
        val english = TypingModel.asset(assets, Spoken.English.code)
        fun hindi(word: String) = word.lowercase().let { hinglish.index(it) >= 0 && english.index(it) < 0 }
        ::hindi
    }
    /** A check is running; another tap waits until it's done. */
    internal var fixing = false
        private set(value) { field = value; view?.fixChanged() }
    // tinyfix knows only English: on another layout it would "fix" every word it doesn't know.
    internal val fixShown get() = feedback.fixButton && !engine.secure &&
        (layoutLanguage == Spoken.English || layoutLanguage == Spoken.Hinglish)
    internal fun fix() {
        if (fixing || !fixShown) return
        val field = engine.fieldText() ?: return
        if (field.text.isBlank()) { view?.note(getString(R.string.keyboard_nothing_to_fix)); return }
        fixing = true
        val dir = TinyfixFiles.dir(this)
        val hinglish = layoutLanguage == Spoken.Hinglish
        fixWork.execute {
            val fixes = try {
                TinyfixFiles.withFixer(dir) { if (hinglish) it.check(field.text, hindiWords) else it.check(field.text) }
            } catch (_: Exception) { null } // The files are gone or damaged: Settings downloads them again.
            main.post {
                fixing = false
                if (fixes != null && !engine.applyFixes(field, fixes) && fixes.isEmpty())
                    view?.note(getString(R.string.keyboard_nothing_to_fix))
            }
        }
    }
    /** The toolbar's undo chip. Words a fix changed and you put back are left alone from then on. */
    internal fun undo() {
        val restored = engine.undo()
        if (restored.isNotEmpty()) fixWork.execute { TinyfixFiles.ifLoaded { fixer -> restored.forEach(fixer::ignore) } }
    }

    override fun onCreate() {
        setTheme(R.style.HexIme)
        super.onCreate()
        getSharedPreferences("typing", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(typingChanged)
        getSharedPreferences(ClipKeeper.PREFS, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(clipsChanged)
        contentResolver.registerContentObserver(UserDictionary.Words.CONTENT_URI, true, dictionaryChanged)
        clips.start()
        words.execute {
            runCatching { if (learnedFile.exists()) learned.read(learnedFile.readText()) }
            runCatching { if (touchFile.exists()) touch.read(touchFile.readText()) }
            runCatching { File(filesDir, Forgotten.FILE).takeIf { it.exists() }?.let { forgotten.read(it.readText()) } }
            val pending = runCatching { File(filesDir, PendingCorrections.FILE).takeIf { it.exists() }?.readText() }.getOrNull()
            learnedLoaded = true
            main.post {
                // Corrections found before the file was read go after the ones it kept.
                pending?.let { val found = pendingCorrections.write(); pendingCorrections.clear(); pendingCorrections.read(it); pendingCorrections.read(found); pendingChanged() }
                engine.hiddenChanged(); engine.learnedWordsChanged(); learnedChanged(); fetchHistoryWords(); sendCorrections()
            }
        }
        words.execute { emails.accounts = EmailAddresses.accounts(this) }
        contactsSettingChanged()
        engine.preload()
    }
    override fun onEvaluateFullscreenMode() = false
    override fun onCreateInputView(): View = KeyboardView(this, this).also { view = it; engine.surface = it }

    // Each field gets a number as it starts; an app restarting the same field (chat apps do) keeps it.
    // A transcript comes back to the field it was spoken for, or is copied ([commitSpeech]).
    private var field = 0
    private var dictatedFor = -1

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (!restarting) { field++; dictation.leave(); autofill.clear() }
        text.started(attribute.initialSelStart, attribute.initialSelEnd,
            attribute.getInitialTextBeforeCursor(CachedText.INITIAL, 0), attribute.getInitialTextAfterCursor(CachedText.INITIAL, 0))
        engine.startInput(attribute)
    }
    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        view?.cancelGestures()
        clips.shown()
        view?.shown()
        engine.opened()
        engine.configureTyping()
        if (!engine.secure) dictation.prewarm()
        fetchHistoryWords()
        fetchReplacements()
        sendCorrections()
    }
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
                                   candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        text.selectionChanged(newSelStart, newSelEnd)
        engine.updateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd)
    }

    // The autofill service's saved passwords, usernames and codes for the field, in the strip. Android asks
    // for the request as a field starts and sends a response (empty for none) whenever its offer changes.
    private val autofill by lazy { InlineAutofill(this) { unpinned, pinned -> view?.autofill(unpinned, pinned) } }
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle) = autofill.request()
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        autofill.offer(response.inlineSuggestions)
        return true
    }

    /** Types the transcript where it was spoken. If that field has gone (another field, or the keyboard
     * hidden), it's copied instead, so the strip's chip offers it, rather than typed somewhere else or lost. */
    private fun commitSpeech(heard: HexClient.Heard) {
        if (dictatedFor == field && currentInputConnection != null && !engine.secure && isInputViewShown) {
            engine.insertDictation(heard.text, heard.language)
            return
        }
        runCatching {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.keyboard_dictation), heard.text))
        }
        view?.note(getString(R.string.keyboard_dictation_copied))
    }
    private fun leaveEditor() { view?.cancelGestures(); engine.leave(); dictation.leave() }
    override fun onFinishInputView(finishingInput: Boolean) { leaveEditor(); super.onFinishInputView(finishingInput) }
    override fun onFinishInput() { leaveEditor(); super.onFinishInput() }
    override fun onWindowHidden() { leaveEditor(); super.onWindowHidden() }
    override fun onDestroy() {
        getSharedPreferences("typing", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(typingChanged)
        getSharedPreferences(ClipKeeper.PREFS, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(clipsChanged)
        contentResolver.unregisterContentObserver(dictionaryChanged); io.cancel(); clips.close()
        if (watchingContacts) contentResolver.unregisterContentObserver(contactsChanged)
        main.removeCallbacks(readContacts)
        // The last changes are written before the worker stops.
        main.removeCallbacks(saveLearned)
        if (learnedLoaded) saveWords()
        words.shutdown(); fixWork.shutdown()
        view?.cancelGestures(); view = null; engine.surface = null; dictation.close(); super.onDestroy()
    }
    private companion object {
        const val SAVE_DELAY_MS = 3_000L
        const val EMOJI_PICKS = "picks"
        const val CONTACTS_DELAY_MS = 2_000L
        /** What the computer's Vocabulary terms count as, so they are words at once. */
        const val VOCABULARY_COUNT = 3
        const val HISTORY_WORDS_MS = 6 * 60 * 60_000L
        const val REPLACEMENTS_MS = 60 * 60_000L
    }
}
