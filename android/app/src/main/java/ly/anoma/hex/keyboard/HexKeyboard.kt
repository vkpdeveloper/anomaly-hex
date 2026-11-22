package ly.anoma.hex.keyboard

import android.content.SharedPreferences
import android.database.ContentObserver
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.provider.UserDictionary
import android.view.View
import android.view.inputmethod.EditorInfo
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ly.anoma.hex.R
import ly.anoma.hex.relay.HexClient
import ly.anoma.hex.relay.Router
import ly.anoma.hex.relay.Store
import ly.anoma.hex.speech.Spoken
import ly.anoma.hex.typing.LearnedWords
import ly.anoma.hex.typing.PersonalWords
import ly.anoma.hex.typing.SpatialDecoder
import ly.anoma.hex.typing.TypingModel
import ly.anoma.hex.typing.TypingPreferences

/** The input method: the editor's lifecycle, the views and dictation. Typing lives in [KeyboardEngine];
 * dictation records here and is transcribed by Hex on the paired computer ([Dictation]). */
class HexKeyboard : InputMethodService() {
    private var view: KeyboardView? = null
    internal val feedback by lazy { TypingPreferences(this) }
    internal val layoutLanguage get() = feedback.layout ?: Spoken.system()
    // Android's personal dictionary is read off the main thread, and again whenever it changes.
    private val words = Executors.newSingleThreadExecutor { Thread(it, "Hex personal words") }
    private val dictionaryChanged = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = engine.personalWordsChanged()
    }
    // What you write, learned by the engine, saved a few seconds after it changes and topped up from the
    // paired computer's Hex History. Nothing is saved before the file has been read, so a save never loses it.
    private val learned = LearnedWords()
    private val learnedFile by lazy { File(filesDir, LearnedWords.FILE) }
    @Volatile private var learnedLoaded = false
    private val main = Handler(Looper.getMainLooper())
    private val saveLearned = Runnable { val text = learned.write(); words.execute { writeLearned(text) } }
    private fun learnedChanged() {
        if (!learnedLoaded) return
        main.removeCallbacks(saveLearned); main.postDelayed(saveLearned, SAVE_DELAY_MS)
    }
    private fun writeLearned(text: String) {
        val partial = File(filesDir, LearnedWords.FILE + ".partial")
        runCatching { partial.writeText(text); partial.renameTo(learnedFile) }
    }
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val router by lazy { Router(this, Store(this)) }
    private var fetching = false
    /** The computer's words, at most every few hours, while Learn from Hex history is on and a computer is paired. */
    private fun fetchHistoryWords(force: Boolean = false) {
        if (!learnedLoaded || fetching || !feedback.learnFromHistory) return
        if (!force && System.currentTimeMillis() - feedback.historyWordsAt < HISTORY_WORDS_MS) return
        fetching = true
        io.launch {
            try {
                val got = withContext(Dispatchers.IO) {
                    val resolved = router.resolve()
                    HexClient.words(resolved.route.host, resolved.computer.port, resolved.computer.code)
                }
                if (!feedback.learnFromHistory) return@launch
                learned.seed(got.words, got.pairs)
                feedback.historyWordsAt = System.currentTimeMillis()
                engine.learnedWordsChanged(); learnedChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Not paired, unreachable, or a computer without the words route: try again next time.
            } finally { fetching = false }
        }
    }
    internal val engine by lazy {
        KeyboardEngine(InputConnectionTarget(this), feedback, { layoutLanguage }, { SpatialDecoder(TypingModel.asset(assets, it)) },
            readPersonal = { model, mine -> PersonalWords.read(contentResolver, model, mine) }, learned = learned,
            learnedChanged = ::learnedChanged, background = words)
    }
    internal val dictation by lazy {
        Dictation(this,
            show = { phase -> if (phase == DictationPhase.Listening) engine.clearForDictation(); view?.dictation(phase) },
            level = { view?.level(it) },
            note = { view?.note(it) },
            insert = ::commitSpeech,
            blocked = { if (engine.secure) getString(R.string.keyboard_hint_password) else null })
    }
    private val typingChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            TypingPreferences.SUGGEST_SWEAR_WORDS -> engine.swearWordsChanged()
            // Settings deleted the file; this copy goes too.
            TypingPreferences.FORGET_LEARNED -> { main.removeCallbacks(saveLearned); learned.clear(); engine.learnedWordsChanged() }
            TypingPreferences.LEARN_FROM_HISTORY ->
                if (feedback.learnFromHistory) fetchHistoryWords(force = true)
                else { learned.seed(emptyList(), emptyList()); engine.learnedWordsChanged(); learnedChanged() }
        }
    }
    internal val previewAllowed get() = !engine.secure

    override fun onCreate() {
        setTheme(R.style.HexIme)
        super.onCreate()
        getSharedPreferences("typing", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(typingChanged)
        contentResolver.registerContentObserver(UserDictionary.Words.CONTENT_URI, true, dictionaryChanged)
        words.execute {
            runCatching { if (learnedFile.exists()) learned.read(learnedFile.readText()) }
            learnedLoaded = true
            main.post { engine.learnedWordsChanged(); learnedChanged(); fetchHistoryWords() }
        }
    }
    override fun onEvaluateFullscreenMode() = false
    override fun onCreateInputView(): View = KeyboardView(this, this).also { view = it; engine.surface = it }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        dictation.cancel() // Even a different field in the same app must not receive an old result.
        engine.startInput(attribute)
    }
    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        view?.cancelGestures()
        view?.shown()
        engine.opened()
        engine.configureTyping()
        if (!engine.secure) dictation.prewarm()
        fetchHistoryWords()
    }
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
                                   candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        engine.updateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd)
    }

    private fun commitSpeech(text: String) {
        if (currentInputConnection == null || engine.secure || !isInputViewShown) return
        engine.insertDictation(text, layoutLanguage.code)
    }
    private fun leaveEditor() { view?.cancelGestures(); engine.leave(); dictation.cancel() }
    override fun onFinishInputView(finishingInput: Boolean) { leaveEditor(); super.onFinishInputView(finishingInput) }
    override fun onFinishInput() { leaveEditor(); super.onFinishInput() }
    override fun onWindowHidden() { leaveEditor(); super.onWindowHidden() }
    override fun onDestroy() {
        getSharedPreferences("typing", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(typingChanged)
        contentResolver.unregisterContentObserver(dictionaryChanged); io.cancel()
        // The last changes are written before the worker stops.
        main.removeCallbacks(saveLearned)
        if (learnedLoaded) learned.write().let { text -> words.execute { writeLearned(text) } }
        words.shutdown()
        view?.cancelGestures(); view = null; engine.surface = null; dictation.close(); super.onDestroy()
    }
    private companion object {
        const val SAVE_DELAY_MS = 3_000L
        const val HISTORY_WORDS_MS = 6 * 60 * 60_000L
    }
}
