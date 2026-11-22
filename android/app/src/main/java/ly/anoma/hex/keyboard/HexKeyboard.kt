package ly.anoma.hex.keyboard

import android.content.SharedPreferences
import android.database.ContentObserver
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.provider.UserDictionary
import android.view.View
import android.view.inputmethod.EditorInfo
import java.util.concurrent.Executors
import ly.anoma.hex.R
import ly.anoma.hex.speech.Spoken
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
    internal val engine by lazy {
        KeyboardEngine(InputConnectionTarget(this), feedback, { layoutLanguage }, { SpatialDecoder(TypingModel.asset(assets, it)) },
            readPersonal = { PersonalWords.read(contentResolver, it) }, background = words)
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
        if (key == TypingPreferences.SUGGEST_SWEAR_WORDS) engine.swearWordsChanged()
    }
    internal val previewAllowed get() = !engine.secure

    override fun onCreate() {
        setTheme(R.style.HexIme)
        super.onCreate()
        getSharedPreferences("typing", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(typingChanged)
        contentResolver.registerContentObserver(UserDictionary.Words.CONTENT_URI, true, dictionaryChanged)
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
        contentResolver.unregisterContentObserver(dictionaryChanged); words.shutdownNow()
        view?.cancelGestures(); view = null; engine.surface = null; dictation.close(); super.onDestroy()
    }
}
