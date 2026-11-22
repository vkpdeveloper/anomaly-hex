package ly.anoma.hex.keyboard

import android.content.Context
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import ly.anoma.hex.R
import ly.anoma.hex.typing.Suggestion
import kotlin.math.roundToInt

/** The keyboard's root view: the toolbar and the keys, or the emoji panel in their place. */
internal class KeyboardView(context: Context, private val ime: HexKeyboard) : LinearLayout(context), KeyboardSurface {
    private val feedback = LetterFeedback(this)
    private val toolbar = Toolbar(context, ime)
    private val keys = KeyPlaneView(context, ime, this, feedback)
    private val emoji = EmojiPanel(context, ime, ::closeEmoji)

    init {
        orientation = VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        // On edge-to-edge IME windows the system draws its back and switcher buttons and the
        // gesture handle over the bottom of our window: 48 dp, which Android 14 and later report
        // as the caption bar inside systemBars (navigationBars alone is the 24 dp handle). Keys stay
        // above it. Where the window already ends above the navigation bar (Android 12) it is 0.
        setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }
        setBackgroundColor(context.getColor(R.color.kb_background))
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, toolbar.toolbarHeight))
        addView(keys, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        emoji.visibility = GONE
        addView(emoji, LayoutParams(LayoutParams.MATCH_PARENT, (EmojiPanel.HEIGHT * resources.displayMetrics.density).roundToInt()))
    }

    override fun showPage(page: KeyboardPage) = keys.showPage(page)
    override fun suggestions(slots: List<Suggestion>) = toolbar.suggestions(slots)

    fun openEmoji() {
        keys.cancelGestures(); ime.engine.leave()
        toolbar.visibility = GONE; keys.visibility = GONE; emoji.visibility = VISIBLE
        setBackgroundColor(context.getColor(R.color.kb_emoji_background))
        emoji.open()
    }
    fun closeEmoji() {
        if (emoji.visibility != VISIBLE) return
        emoji.visibility = GONE; toolbar.visibility = VISIBLE; keys.visibility = VISIBLE
        setBackgroundColor(context.getColor(R.color.kb_background))
        ime.engine.configureTyping(keepWord = true)
    }
    fun dictation(phase: DictationPhase) {
        if (phase != DictationPhase.Idle) closeEmoji()
        toolbar.dictation(phase)
    }
    fun level(peak: Float) = toolbar.level(peak)
    fun note(text: String) = toolbar.note(text)
    /** The keyboard came up in a field. */
    fun shown() { closeEmoji(); toolbar.refresh() }
    fun cancelGestures() { toolbar.cancelGestures(); keys.cancelGestures() }
}
