package ly.anoma.hex.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ly.anoma.hex.R
import ly.anoma.hex.useSans
import kotlin.math.roundToInt

/** The emoji panel's recent emoji, newest first, shared by the panel and the search. */
internal class EmojiRecent(private val prefs: SharedPreferences) {
    fun list() = prefs.getString(KEY, "").orEmpty().split(' ').filter { it.isNotEmpty() }
    fun add(emoji: String) {
        val recent = listOf(emoji) + list().filter { it != emoji }
        prefs.edit().putString(KEY, recent.take(MAX).joinToString(" ")).apply()
    }
    private companion object {
        const val KEY = "recent"
        const val MAX = 32
    }
}

/** The skin tone you chose for each emoji that has them, by touching and holding it in the panel. Hex
 * keeps every emoji without a tone everywhere else and gives it its tone only as it shows or goes in. */
internal class EmojiTones(private val prefs: SharedPreferences) {
    private val chosen = HashMap<String, String>().apply {
        for (pair in prefs.getString(KEY, "").orEmpty().split(' ')) {
            val (base, toned) = pair.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
            put(base, toned)
        }
    }
    fun tone(emoji: String) = chosen[emoji] ?: emoji
    fun choose(emoji: String, toned: String) {
        if (toned == emoji) chosen.remove(emoji) else chosen[emoji] = toned
        prefs.edit().putString(KEY, chosen.entries.joinToString(" ") { "${it.key}=${it.value}" }).apply()
    }
    companion object {
        private const val KEY = "tones"
        private val MODIFIERS = listOf("\uD83C\uDFFB", "\uD83C\uDFFC", "\uD83C\uDFFD", "\uD83C\uDFFE", "\uD83C\uDFFF")
        /** [emoji] and its five skin tones, each after its first person and without the emoji style mark
         * there ("✌️" is "✌🏽"), or none when the font draws no toned [emoji] as one picture. */
        fun variants(emoji: String, shows: (String) -> Boolean): List<String> {
            val first = Character.charCount(emoji.codePointAt(0))
            val rest = emoji.substring(first).removePrefix("\uFE0F")
            val toned = MODIFIERS.map { emoji.substring(0, first) + it + rest }
            return if (toned.all(shows)) listOf(emoji) + toned else emptyList()
        }
    }
}

/** The emoji search's bar while the keys type into it: back to the panel, what was typed with a
 * cursor after it, and clear. Its outline and cursor show the keys type here, not in the app. */
internal class EmojiSearchBar(context: Context, back: () -> Unit, clear: () -> Unit) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private fun px(value: Float) = (value * density).roundToInt()
    private fun color(id: Int) = context.getColor(id)
    private var empty = true
    // A still cursor, not a blinking one, so the bar never redraws while you aren't typing.
    private val cursor = Paint().apply { color = context.getColor(R.color.kb_enter) }
    private val query = object : TextView(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val line = layout ?: return
            // Before the hint, or right after what was typed.
            val x = if (empty) paddingLeft - px(5f).toFloat() else paddingLeft + line.getLineRight(0) + px(1f)
            val h = px(20f)
            val top = (height - h) / 2f
            canvas.drawRect(x, top, x + px(2f), top + h, cursor)
        }
    }
    private val clearButton = button(R.drawable.ic_close, R.string.keyboard_clear, clear)

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(px(6f), px(6f), px(6f), px(2f))
        val field = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(color(R.color.kb_key)); cornerRadius = 1e4f; setStroke(px(2f), color(R.color.kb_enter))
            }
            addView(button(R.drawable.ic_arrow_back, R.string.keyboard_back, back), LayoutParams(px(40f), -1))
            addView(query.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16.5f); useSans(); includeFontPadding = false
                maxLines = 1; ellipsize = TextUtils.TruncateAt.START; gravity = Gravity.CENTER_VERTICAL
                setPadding(px(7f), 0, 0, 0)
                accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
            }, LayoutParams(0, -1, 1f))
            addView(clearButton, LayoutParams(px(40f), -1))
        }
        addView(field, LayoutParams(-1, -1))
    }

    fun show(text: String) {
        empty = text.isEmpty()
        query.text = text.ifEmpty { context.getString(R.string.keyboard_emoji_search) }
        query.setTextColor(color(if (empty) R.color.kb_hint else R.color.kb_ink))
        clearButton.visibility = if (empty) INVISIBLE else VISIBLE
        query.invalidate()
    }

    private fun button(icon: Int, description: Int, click: () -> Unit) = FrameLayout(context).apply {
        isFocusable = true; contentDescription = context.getString(description)
        setOnClickListener { click() }
        addView(ImageView(context).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(color(R.color.kb_ink)) },
            FrameLayout.LayoutParams(px(22f), px(22f), Gravity.CENTER))
    }
}

/** What the emoji search found, under its bar: two rows of the panel's grid that scroll, or a line
 * saying nothing matched. A tap types the emoji and the search stays open. */
internal class EmojiResults(context: Context, ime: HexKeyboard) : FrameLayout(context) {
    companion object {
        /** Rows that show at once. */
        const val ROWS = 2
    }
    private val scroll = ScrollView(context)
    private val grid = EmojiGrid(context, { ime.engine.pickFound(it) }, label = { ime.emojiTones.tone(it) })
    private val none = TextView(context)
    /** The height that shows [ROWS] rows, in pixels. */
    val wanted = grid.heightFor(ROWS)

    init {
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.setPadding((5 * resources.displayMetrics.density).roundToInt(), 0, (5 * resources.displayMetrics.density).roundToInt(), 0)
        scroll.addView(grid)
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> grid.scrolled(y) }
        addView(scroll, LayoutParams(-1, -1))
        none.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f); useSans(); setTextColor(context.getColor(R.color.kb_quiet))
            gravity = Gravity.CENTER; setText(R.string.keyboard_emoji_none); visibility = GONE
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addView(none, LayoutParams(-1, -1))
    }

    fun show(found: List<String>) {
        grid.show(listOf(EmojiGrid.Section(null, found, -1)), columns = 8, emoji = true)
        scroll.scrollTo(0, 0)
        none.visibility = if (found.isEmpty()) VISIBLE else GONE
    }
}
