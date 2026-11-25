package ly.anoma.hex.keyboard

import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import ly.anoma.hex.R
import ly.anoma.hex.typing.Suggestion
import kotlin.math.roundToInt

/** The keyboard's root view: the toolbar and the keys, or the emoji panel, the menu or the clipboard in their place. */
internal class KeyboardView(context: Context, private val ime: HexKeyboard) : LinearLayout(context), KeyboardSurface {
    private val feedback = LetterFeedback(this)
    private val toolbar = Toolbar(context, ime, ::openMenu)
    private val keys = KeyPlaneView(context, ime, this, feedback)
    private val emoji = EmojiPanel(context, ime, ::closeEmoji, ::openSearch)
    private val search = EmojiSearchBar(context, back = ::openEmoji, clear = { ime.engine.stopSearch(); ime.engine.startSearch() })
    private val clipboard = ClipboardPanel(context, ime, ::closeClipboard)
    private val menu = MenuPanel(context, ime, ::closeMenu, ::openClipboard)
    private var phase = DictationPhase.Idle

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
        search.visibility = GONE
        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, toolbar.toolbarHeight))
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, toolbar.toolbarHeight))
        addView(keys, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        emoji.visibility = GONE
        addView(emoji, LayoutParams(LayoutParams.MATCH_PARENT, (EmojiPanel.HEIGHT * resources.displayMetrics.density).roundToInt()))
        clipboard.visibility = GONE
        addView(clipboard, LayoutParams(LayoutParams.MATCH_PARENT, 0))
        menu.visibility = GONE
        addView(menu, LayoutParams(LayoutParams.MATCH_PARENT, 0))
    }

    override fun showPage(page: KeyboardPage) {
        keys.showPage(page)
        // What TalkBack names the keyboard as it appears, as Gboard's "English (US) (QWERTY)".
        val title = context.getString(R.string.keyboard_pane, page.language.label)
        if (accessibilityPaneTitle != title) accessibilityPaneTitle = title
    }
    override fun suggestions(slots: List<Suggestion>) = toolbar.suggestions(slots)
    override fun undo(offer: UndoOffer?) = toolbar.undo(offer)
    override fun actions(list: List<StripAction>) = toolbar.actions(list)
    override fun emojiRow(emoji: List<String>) = toolbar.found(emoji)
    override fun emojiSearch(query: String?) {
        toolbar.searching(query != null)
        if (query == null) search.visibility = GONE else { search.visibility = VISIBLE; search.show(query) }
    }

    fun openEmoji() {
        ime.engine.stopSearch()
        keys.cancelGestures(); ime.engine.pause()
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
    /** The emoji search, from the panel: the toolbar and keys come back under its bar, and the keys type into it. */
    private fun openSearch() {
        emoji.visibility = GONE; toolbar.visibility = VISIBLE; keys.visibility = VISIBLE
        setBackgroundColor(context.getColor(R.color.kb_background))
        ime.engine.configureTyping(keepWord = true)
        ime.engine.startSearch()
    }
    /** The menu takes the toolbar's and the keys' place at their height, so the editor doesn't move. */
    private fun openMenu() {
        keys.cancelGestures(); toolbar.cancelGestures(); ime.engine.pause()
        menu.layoutParams = menu.layoutParams.apply { height = toolbar.height + keys.height }
        toolbar.visibility = GONE; keys.visibility = GONE; menu.visibility = VISIBLE
        menu.open()
    }
    private fun closeMenu() {
        if (menu.visibility != VISIBLE) return
        menu.visibility = GONE; toolbar.visibility = VISIBLE; keys.visibility = VISIBLE
        ime.engine.configureTyping(keepWord = true)
    }
    /** The clipboard, from the menu, in its place at the same height. */
    private fun openClipboard() {
        clipboard.layoutParams = clipboard.layoutParams.apply { height = menu.height }
        menu.visibility = GONE; clipboard.visibility = VISIBLE
        clipboard.open()
    }
    fun closeClipboard() {
        if (clipboard.visibility != VISIBLE) return
        clipboard.visibility = GONE; toolbar.visibility = VISIBLE; keys.visibility = VISIBLE
        ime.engine.configureTyping(keepWord = true)
    }
    /** The clips, the photos or the chip's offer changed ([ClipKeeper]). */
    fun clipsChanged() { toolbar.update(); if (clipboard.visibility == VISIBLE) clipboard.show() }
    fun dictation(phase: DictationPhase) {
        cue(this.phase, phase); this.phase = phase
        if (phase != DictationPhase.Idle) { ime.engine.stopSearch(); closeEmoji(); closeMenu(); closeClipboard() }
        toolbar.dictation(phase)
    }
    /** A light tap as the microphone opens and a lighter tick as it closes, so someone using TalkBack,
     * which is quiet while Hex records, feels both ends. Android's own pair for a gesture's start and end. */
    private fun cue(from: DictationPhase, to: DictationPhase) {
        fun recording(p: DictationPhase) = p == DictationPhase.Listening
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val effect = when {
            !recording(from) && recording(to) -> if (modern) HapticFeedbackConstants.GESTURE_START else HapticFeedbackConstants.CONFIRM
            recording(from) && !recording(to) -> if (modern) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.CONTEXT_CLICK
            else -> return
        }
        if (vibrates(context, ime.feedback.haptics)) performHapticFeedback(effect)
    }
    fun level(peak: Float) = toolbar.level(peak)
    fun autofill(unpinned: List<View>, pinned: List<View>) = toolbar.autofill(unpinned, pinned)
    fun note(text: String) = toolbar.note(text)
    /** The fix button was turned on or off in Settings. */
    fun fixChanged() = toolbar.update()
    fun numberRowChanged() = keys.numberRowChanged()
    /** The keyboard came up in a field. */
    fun shown() { ime.engine.stopSearch(); closeEmoji(); closeMenu(); closeClipboard(); toolbar.update() }
    fun cancelGestures() { toolbar.cancelGestures(); keys.cancelGestures() }
}
