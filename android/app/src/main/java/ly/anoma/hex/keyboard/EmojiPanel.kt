package ly.anoma.hex.keyboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ly.anoma.hex.R
import ly.anoma.hex.useSans
import kotlin.math.roundToInt

/** Gboard's emoji panel: back and the category tabs on top, the emoji in eight columns under section
 * headings (recent first), and ABC, emoji, emoticons and delete along the bottom. It takes the place
 * of the toolbar and the keys, and is taller than they are, as Gboard's is. */
internal class EmojiPanel(context: Context, private val ime: HexKeyboard, private val close: () -> Unit, search: () -> Unit) : LinearLayout(context) {
    companion object {
        /** The panel's height above the system's bar, in dp. */
        const val HEIGHT = 378f
        private val EMOTICONS = listOf(":-)", ";-)", ":-(", ":-D", ":-P", ":-O", ":'(", ":-*", "<3", "B-)",
            "^_^", "-_-", "o_O", "xD", ">_<", "T_T", "¯\\_(ツ)_/¯", "(╯°□°)╯︵ ┻━┻", "ʕ•ᴥ•ʔ", "(¬‿¬)", "ಠ_ಠ", "(づ｡◕‿‿◕｡)づ")
        /** The asset's groups in order, with their tab icon and heading. */
        private val GROUPS = listOf(
            Triple("Smileys & Emotion", R.drawable.ic_emoji_smileys, "Smileys and emotions"),
            Triple("People & Body", R.drawable.ic_emoji_people, "People"),
            Triple("Animals & Nature", R.drawable.ic_emoji_nature, "Animals and nature"),
            Triple("Food & Drink", R.drawable.ic_emoji_food, "Food and drink"),
            Triple("Travel & Places", R.drawable.ic_emoji_travel, "Travel and places"),
            Triple("Activities", R.drawable.ic_emoji_activities, "Activities"),
            Triple("Objects", R.drawable.ic_emoji_objects, "Objects"),
            Triple("Symbols", R.drawable.ic_emoji_symbols, "Symbols"),
            Triple("Flags", R.drawable.ic_emoji_flags, "Flags"),
        )
        /** Read and checked against the system's emoji font once per process. */
        @Volatile private var groups: Map<String, List<String>>? = null
        // Checking some three thousand emoji against the font takes long enough to be a visible stall in
        // the first press of the emoji key, so [warm] does it in the background as the keyboard is made.
        // A press before it finishes waits for it here, never starts a second pass.
        @Synchronized private fun load(context: Context): Map<String, List<String>> = groups ?: run {
            val paint = Paint()
            context.assets.open("emoji.txt").bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { line ->
                    val (name, list) = line.split('\t', limit = 2)
                    name to list.split(' ').filter { it.isNotEmpty() && paint.hasGlyph(it) }
                }.also { groups = it }
        }
        private fun warm(context: Context) {
            if (groups != null) return
            val app = context.applicationContext
            Thread(null, { load(app) }, "emoji-load").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
        }
    }
    private val density = resources.displayMetrics.density
    private fun px(value: Float) = (value * density).roundToInt()
    private fun color(id: Int) = context.getColor(id)
    private val scroll = ScrollView(context)
    private val grid = EmojiGrid(context, ::pick, ::hold, ime.emojiTones::tone)
    // The skin tones of the emoji being held, over the grid, and behind it a shield a touch outside closes.
    private val body = FrameLayout(context)
    private val shield = View(context)
    private val tones = LinearLayout(context)
    private val tabs = LinearLayout(context)
    private val tabViews = ArrayList<Pair<FrameLayout, ImageView>>()
    private var emoticons = false
    private val emojiPill: FrameLayout
    private val emoticonPill: FrameLayout

    init {
        warm(context)
        orientation = VERTICAL
        setBackgroundColor(color(R.color.kb_emoji_background))
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(7.5f), 0, 0, 0) }
        top.addView(round(R.drawable.ic_arrow_back, R.string.keyboard_back, close), LayoutParams(px(34f), px(34f)))
        tabs.gravity = Gravity.CENTER_VERTICAL
        tabs.setPadding(px(8f), 0, px(8f), 0)
        addTab(R.drawable.ic_emoji_recent, context.getString(R.string.keyboard_emoji_recent))
        for ((_, icon, heading) in GROUPS) addTab(icon, heading)
        top.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; addView(tabs)
        }, LayoutParams(0, -1, 1f))
        top.addView(round(R.drawable.ic_search, R.string.keyboard_emoji_search, search), LayoutParams(px(34f), px(34f)).apply { marginEnd = px(7.5f) })
        addView(top, LayoutParams(-1, px(42f)))

        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        // Gboard's columns stop 5 dp short of each edge; headings still start 12.5 dp in.
        scroll.setPadding(px(5f), 0, px(5f), 0)
        scroll.addView(grid)
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> grid.scrolled(y); if (!emoticons) select(grid.sectionAt(y)) }
        body.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        shield.visibility = GONE
        shield.setOnClickListener { closeTones() }
        body.addView(shield, FrameLayout.LayoutParams(-1, -1))
        tones.apply {
            visibility = GONE; orientation = HORIZONTAL; elevation = px(4f).toFloat()
            background = GradientDrawable().apply { setColor(color(R.color.kb_key)); cornerRadius = px(12f).toFloat() }
            setPadding(px(4f), px(4f), px(4f), px(4f))
        }
        body.addView(tones, FrameLayout.LayoutParams(-2, -2))
        addView(body, LayoutParams(-1, 0, 1f))

        val bottom = FrameLayout(context)
        bottom.addView(TextView(context).apply {
            text = context.getString(R.string.keyboard_letters_label); setTextSize(TypedValue.COMPLEX_UNIT_DIP, KeyboardMetrics.LABEL); useSans(500)
            setTextColor(color(R.color.kb_ink)); gravity = Gravity.CENTER; includeFontPadding = false
            contentDescription = context.getString(R.string.keyboard_letters); isFocusable = true
            background = pressable(android.graphics.Color.TRANSPARENT)
            setOnClickListener { close() }
        }, FrameLayout.LayoutParams(px(53f), -1, Gravity.START).apply { leftMargin = px(4f) })
        emojiPill = pill(R.drawable.ic_emoji, null, R.string.keyboard_emoji) { showEmoji() }
        bottom.addView(emojiPill, FrameLayout.LayoutParams(px(53f), -1, Gravity.START).apply { leftMargin = px(67.4f) })
        emoticonPill = pill(null, ":-)", R.string.keyboard_emoticons) { showEmoticons() }
        bottom.addView(emoticonPill, FrameLayout.LayoutParams(px(53f), -1, Gravity.START).apply { leftMargin = px(125.7f) })
        val delete = ImageView(context).apply {
            setImageResource(R.drawable.ic_delete); imageTintList = ColorStateList.valueOf(color(R.color.kb_ink))
            scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(px(8.5f), px(1f), px(8.5f), px(1f))
            contentDescription = context.getString(R.string.keyboard_delete); isFocusable = true
            background = pressable(android.graphics.Color.TRANSPARENT)
        }
        repeatOnHold(delete) { ime.engine.delete() }
        bottom.addView(delete, FrameLayout.LayoutParams(px(53f), -1, Gravity.END).apply { rightMargin = px(4f) })
        addView(bottom, LayoutParams(-1, px(38f)).apply { bottomMargin = px(3f) })
    }

    /** Opens on recent emoji, or on smileys when there are none yet. */
    fun open() { closeTones(); showEmoji(); scroll.scrollTo(0, 0); select(grid.sectionAt(0)) }

    private fun showEmoji() {
        emoticons = false
        val all = load(context)
        val recent = ime.emojiRecent.list()
        val sections = ArrayList<EmojiGrid.Section>()
        sections += EmojiGrid.Section(context.getString(R.string.keyboard_emoji_recent), recent, 0)
        GROUPS.forEachIndexed { i, (name, _, heading) -> sections += EmojiGrid.Section(heading, all[name].orEmpty(), i + 1) }
        grid.show(sections.filter { it.items.isNotEmpty() }, columns = 8, emoji = true)
        styleBottom()
    }
    private fun showEmoticons() {
        emoticons = true
        grid.show(listOf(EmojiGrid.Section(null, EMOTICONS, -1)), columns = 4, emoji = false)
        scroll.scrollTo(0, 0); select(-1)
        styleBottom()
    }
    private fun styleBottom() {
        fun style(pill: FrameLayout, on: Boolean) {
            (pill.getChildAt(0) as View).background = pressable(color(if (on) R.color.kb_enter else R.color.kb_emoji_pill), radius = 1e4f)
            val content = pill.getChildAt(1)
            val ink = color(if (on) R.color.kb_selected_ink else R.color.kb_ink)
            if (content is ImageView) content.imageTintList = ColorStateList.valueOf(ink) else (content as TextView).setTextColor(ink)
        }
        style(emojiPill, !emoticons); style(emoticonPill, emoticons)
    }

    private fun pick(text: String) {
        if (emoticons) { ime.engine.type(text); return }
        ime.engine.emoji(text)
        ime.emojiRecent.add(text)
    }

    /** An emoji held in the grid: its skin tones, when it has them, over it. Picking one types it and
     * keeps it as that emoji's tone, in the panel, the search and the strip. */
    private fun hold(emoji: String, cell: android.graphics.RectF): Boolean {
        if (emoticons) return false
        val variants = EmojiTones.variants(emoji, toneGlyphs::hasGlyph)
        if (variants.isEmpty()) return false
        tones.removeAllViews()
        val chosen = ime.emojiTones.tone(emoji)
        for (v in variants) tones.addView(TextView(context).apply {
            text = v; setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28f); gravity = Gravity.CENTER; includeFontPadding = false
            contentDescription = v; isFocusable = true
            background = if (v == chosen) GradientDrawable().apply { setColor(color(R.color.kb_key_pressed)); cornerRadius = px(8f).toFloat() } else null
            setOnClickListener { ime.emojiTones.choose(emoji, v); closeTones(); pick(emoji) }
        }, LayoutParams(px(44f), px(48f)))
        val width = px(44f) * variants.size + px(8f)
        val height = px(56f)
        val x = cell.centerX() + scroll.paddingLeft - width / 2f
        val y = cell.top - scroll.scrollY - height
        tones.translationX = x.coerceIn(px(4f).toFloat(), (body.width - width - px(4f)).toFloat().coerceAtLeast(0f))
        tones.translationY = if (y >= 0) y else cell.bottom - scroll.scrollY
        tones.visibility = VISIBLE; shield.visibility = VISIBLE
        return true
    }
    private fun closeTones() {
        if (tones.visibility != VISIBLE) return
        tones.visibility = GONE; shield.visibility = GONE; grid.invalidate()
    }
    private val toneGlyphs by lazy { Paint() }

    private fun addTab(icon: Int, description: String) {
        val index = tabViews.size
        val image = ImageView(context).apply { setImageResource(icon) }
        val tab = FrameLayout(context).apply {
            contentDescription = description; isFocusable = true
            setOnClickListener {
                if (emoticons) showEmoji()
                scroll.post { scroll.scrollTo(0, grid.offsetOf(index)); select(index) }
            }
            addView(View(context), FrameLayout.LayoutParams(px(34f), px(34f), Gravity.CENTER))
            addView(image, FrameLayout.LayoutParams(px(19f), px(19f), Gravity.CENTER))
        }
        tabViews += tab to image
        tabs.addView(tab, LayoutParams(px(34f), px(34f)))
    }
    private var selected = -2
    // Shared by every tab: only one is ever selected, and a selection is no reason to make new ones.
    private val tabDisc = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(R.color.kb_enter)) }
    private val tabInkOn = ColorStateList.valueOf(color(R.color.kb_selected_ink))
    private val tabInkOff = ColorStateList.valueOf(color(R.color.kb_hint))
    private fun select(index: Int) {
        if (index == selected) return
        selected = index
        tabViews.forEachIndexed { i, (tab, image) ->
            val on = i == index
            (tab.getChildAt(0) as View).background = if (on) tabDisc else null
            image.imageTintList = if (on) tabInkOn else tabInkOff
            tab.isSelected = on
        }
    }

    private fun round(icon: Int, description: Int, click: () -> Unit) = FrameLayout(context).apply {
        contentDescription = context.getString(description); isFocusable = true
        background = pressable(color(R.color.kb_emoji_pill), radius = 1e4f)
        setOnClickListener { click() }
        addView(ImageView(context).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(color(R.color.kb_ink)) },
            FrameLayout.LayoutParams(px(24f), px(24f), Gravity.CENTER))
    }
    private fun pill(icon: Int?, label: String?, description: Int, click: () -> Unit) = FrameLayout(context).apply {
        contentDescription = context.getString(description); isFocusable = true
        setOnClickListener { click() }
        addView(View(context).apply { isDuplicateParentStateEnabled = true }, FrameLayout.LayoutParams(-1, -1))
        if (icon != null) addView(ImageView(context).apply { setImageResource(icon) }, FrameLayout.LayoutParams(px(24f), px(24f), Gravity.CENTER))
        else addView(TextView(context).apply {
            text = label; setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17f); useSans(500); includeFontPadding = false; gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -1))
    }
    private fun pressable(fill: Int, radius: Float = 0f) = StateListDrawable().apply {
        fun shape(c: Int) = GradientDrawable().apply {
            if (radius == 0f) shape = GradientDrawable.OVAL else cornerRadius = radius
            setColor(c)
        }
        addState(intArrayOf(android.R.attr.state_pressed), shape(color(R.color.kb_key_pressed)))
        addState(intArrayOf(), shape(fill))
    }
    /** Delete repeats while held, as the keys' delete does. */
    private fun repeatOnHold(view: View, action: () -> Unit) {
        val repeat = object : Runnable {
            override fun run() { action(); view.postDelayed(this, KeyboardMetrics.DELETE_REPEAT_EVERY) }
        }
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; action(); v.postDelayed(repeat, KeyboardMetrics.DELETE_REPEAT_DELAY) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; v.removeCallbacks(repeat) }
            }
            true
        }
    }
}

/** The emoji, drawn straight onto one tall view inside the scroll: only the rows on screen are drawn,
 * so a couple of thousand emoji open at once. Headings span the row; a tap types its cell. */
internal class EmojiGrid(
    context: Context,
    private val pick: (String) -> Unit,
    /** A cell held: true when it opened something, so the release types nothing. */
    private val hold: (String, android.graphics.RectF) -> Boolean = { _, _ -> false },
    /** What a cell shows for its emoji (its chosen skin tone). */
    private val label: (String) -> String = { it },
) : View(context) {
    class Section(val heading: String?, val items: List<String>, val tab: Int)
    private val density = resources.displayMetrics.density
    private val rowHeight = 50.6f * density
    private val headingHeight = 21.8f * density
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 33.5f * density }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 19f * density; color = context.getColor(R.color.kb_ink)
        typeface = resources.getFont(R.font.google_sans)
    }
    private val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12.6f * density; color = context.getColor(R.color.kb_quiet); typeface = resources.getFont(R.font.google_sans)
    }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.kb_key) }
    private var sections: List<Section> = emptyList()
    private var columns = 8
    private var emoji = true
    /** Each section's top, then each of its rows follows the heading. */
    private var tops = IntArray(0)
    private var total = 0
    private var pressedSection = -1
    private var pressedIndex = -1
    // The rows the last draw recorded. A view inside a scroll is recorded whole, whatever shows, so
    // drawing all of the thousands of emoji meant recording every one again for each press. Only the rows
    // within a screen of what shows are drawn; [scrolled] draws again once the scroll leaves them.
    private var drawnFrom = 0f
    private var drawnTo = 0f
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    fun show(sections: List<Section>, columns: Int, emoji: Boolean) {
        this.sections = sections; this.columns = columns; this.emoji = emoji
        tops = IntArray(sections.size)
        var y = 0f
        sections.forEachIndexed { i, s ->
            tops[i] = y.roundToInt()
            y += (if (s.heading != null) headingHeight else 8f * density) + rowsOf(s) * rowHeight
        }
        total = (y + 8f * density).roundToInt()
        requestLayout(); invalidate()
    }
    private fun rowsOf(s: Section) = (s.items.size + columns - 1) / columns
    private fun headingOf(s: Section) = if (s.heading != null) headingHeight else 8f * density
    /** The scroll offset of the section a tab names. */
    fun offsetOf(tab: Int) = sections.indexOfFirst { it.tab == tab }.let { if (it < 0) 0 else tops[it] }
    /** The tab of the section showing at the top of the scroll. */
    fun sectionAt(y: Int): Int {
        var tab = sections.firstOrNull()?.tab ?: -1
        sections.forEachIndexed { i, s -> if (tops[i] <= y + headingHeight) tab = s.tab }
        return tab
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), total)

    /** The scroll moved to [y]: the rows now showing must have been drawn. */
    fun scrolled(y: Int) {
        val shown = (parent as? View)?.height ?: return
        if (y < drawnFrom || y + shown > drawnTo) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val viewport = parent as? View
        val shown = viewport?.height ?: height
        val top = viewport?.scrollY ?: 0
        drawnFrom = (top - shown).toFloat(); drawnTo = (top + 2 * shown).toFloat()
        val cell = width / columns.toFloat()
        sections.forEachIndexed { i, s ->
            val start = tops[i] + headingOf(s)
            val end = start + rowsOf(s) * rowHeight
            if (end < drawnFrom || tops[i] > drawnTo) return@forEachIndexed
            s.heading?.let { canvas.drawText(it, 7.5f * density, tops[i] + 18.5f * density, headingPaint) }
            val paint = if (emoji) emojiPaint else textPaint
            val baseline = rowHeight / 2 - (paint.ascent() + paint.descent()) / 2
            val first = ((drawnFrom - start) / rowHeight).toInt().coerceAtLeast(0)
            val last = ((drawnTo - start) / rowHeight).toInt().coerceAtMost(rowsOf(s) - 1)
            for (row in first..last) for (col in 0 until columns) {
                val index = row * columns + col
                if (index >= s.items.size) break
                val x = cell * col + cell / 2
                val top = start + row * rowHeight
                if (pressedSection == i && pressedIndex == index) canvas.drawRoundRect(cell * col + 2 * density, top + 2 * density,
                    cell * (col + 1) - 2 * density, top + rowHeight - 2 * density, 8 * density, 8 * density, pressPaint)
                canvas.drawText(label(s.items[index]), x, top + baseline, paint)
            }
        }
    }

    /** Presses the cell under ([x], [y]), if there is one. */
    private fun press(x: Float, y: Float) {
        pressedSection = -1; pressedIndex = -1
        val cell = width / columns.toFloat()
        sections.forEachIndexed { i, s ->
            val start = tops[i] + headingOf(s)
            if (y >= start && y < start + rowsOf(s) * rowHeight) {
                val index = ((y - start) / rowHeight).toInt() * columns + (x / cell).toInt().coerceIn(0, columns - 1)
                if (index < s.items.size) { pressedSection = i; pressedIndex = index }
                return
            }
        }
    }
    private fun release() { pressedSection = -1; pressedIndex = -1; removeCallbacks(holding) }
    private var held = false
    private val holding = Runnable {
        if (pressedSection < 0) return@Runnable
        val s = sections[pressedSection]
        val cell = width / columns.toFloat()
        val col = pressedIndex % columns
        val top = tops[pressedSection] + headingOf(s) + (pressedIndex / columns) * rowHeight
        if (hold(s.items[pressedIndex], android.graphics.RectF(cell * col, top, cell * (col + 1), top + rowHeight))) {
            held = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            release(); invalidate()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; held = false; press(e.x, e.y); invalidate()
                if (pressedSection >= 0) postDelayed(holding, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (pressedSection >= 0 && (Math.abs(e.x - downX) > slop || Math.abs(e.y - downY) > slop)) {
                release(); invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (pressedSection >= 0 && !held) {
                    pick(sections[pressedSection].items[pressedIndex])
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                }
                release(); invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { release(); invalidate() }
        }
        return true
    }
}
