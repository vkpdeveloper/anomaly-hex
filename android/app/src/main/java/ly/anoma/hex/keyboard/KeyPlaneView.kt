package ly.anoma.hex.keyboard

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ly.anoma.hex.R
import ly.anoma.hex.typing.KeyGrid
import ly.anoma.hex.typing.KeyPoint
import ly.anoma.hex.typing.KeyRect
import ly.anoma.hex.typing.TypingWord
import kotlin.math.roundToInt

/** The keys, laid out as Gboard's: Keyboard > Number row's digits over the letters when it is on,
 * three rows from the page's layout (or the number pad), then a bottom row that changes with the page.
 * Every point of the area types a key (KeyRowLayout); space and enter stay mounted across page changes,
 * so a held space keeps its slide. Keys that type as they lift do so in the order the fingers went down
 * ([Rollover]). */
internal class KeyPlaneView(context: Context, private val ime: HexKeyboard, private val host: KeyboardView,
                            private val feedback: LetterFeedback) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private fun px(value: Float) = (value * density).roundToInt()
    private val caps = KeyCaps(context) { ime.feedback.haptics }
    private val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private val rowHeight = px(if (landscape) KeyboardMetrics.ROW_LANDSCAPE else KeyboardMetrics.ROW)
    private val keyHeight = px(if (landscape) KeyboardMetrics.KEY_HEIGHT_LANDSCAPE else KeyboardMetrics.KEY_HEIGHT)
    private val insetTop = (rowHeight - keyHeight) / 2
    private val insetBottom = rowHeight - keyHeight - insetTop
    private val lastHeight = if (landscape) rowHeight else insetTop + px(KeyboardMetrics.LAST_KEY_HEIGHT) + px(KeyboardMetrics.LAST_BOTTOM)
    private val side = KeyboardMetrics.SIDE * density
    private val gap = KeyboardMetrics.GAP * density
    private var plane = PlaneRows(rowHeight, lastHeight, numberRow = false)
    /** The keys' whole height, which the emoji panel's grid fills in their place. */
    val keysHeight get() = plane.height
    private val letters = LinkedHashMap<Char, KeyCap>()
    private var grid: KeyGrid? = null
    private var pendingAccents: Runnable? = null
    private val roll = Rollover()
    // Outside [rows], so the letters' coordinates, which touches, glides and the space bar are measured
    // in, are the same with it or without it (PlaneRows).
    private val numberRow = KeyRowLayout(context, 10f, side, gap, insetTop, insetBottom)
    private val rows = LinearLayout(context)
    private val bottom = KeyRowLayout(context, 10f, side, gap, insetTop, lastHeight - insetTop - (if (landscape) keyHeight else px(KeyboardMetrics.LAST_KEY_HEIGHT)))
    private val modeKey = caps.cap(context.getString(R.string.keyboard_numbers_label), context.getString(R.string.keyboard_numbers),
        KeyFill.Function, KeyboardMetrics.LABEL, pill = true, weight = 500) { ime.engine.mode() }
    // Gboard's @ in email fields and / in web addresses, else a comma (FieldKind).
    private var commaText = ","
    private val comma = caps.cap(",", context.getString(R.string.keyboard_comma), KeyFill.Function) { ime.engine.type(commaText) }
    private val period = caps.cap(".", context.getString(R.string.keyboard_period), KeyFill.Function) { ime.engine.type(".") }
    private val emojiKey = caps.icon(R.drawable.ic_emoji, context.getString(R.string.keyboard_emoji), KeyFill.Letter, { host.openEmoji() })
    // Gboard sets its two lines tight and its digits apart.
    private val numpadKey = caps.cap("12\n34", context.getString(R.string.keyboard_numpad), KeyFill.Letter, 13f, weight = 500) { ime.engine.numpad() }
        .apply { label?.apply { letterSpacing = 0.2f; setLineSpacing(0f, 0.8f) } }
    private val lessKey = caps.cap("<", "<", KeyFill.Function, KeyboardMetrics.SYMBOL) { ime.engine.type("<") }
    private val moreKey = caps.cap(">", ">", KeyFill.Function, KeyboardMetrics.SYMBOL) { ime.engine.type(">") }
    private val spaceKey: KeyCap
    private val enterKey: KeyCap
    private val enterIcon = ImageView(context)
    private var deleting = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val spaceGesture = SpaceGesture(KeyboardMetrics.CURSOR_START * density, KeyboardMetrics.CURSOR_STEP * density, slop.toFloat())
    private var cursorSlide: CursorSlide? = null
    private val glide = GlideTrail(context.getColor(R.color.kb_enter), density)
    private var spacePoint: KeyPoint? = null
    // The page the keys show; a change of shift alone relabels them in place, so a held shift key
    // stays mounted and its own finger-up ends a chord.
    private var current: KeyboardPage? = null
    private var bottomKind = -1
    private val caseKeys = ArrayList<Pair<Char, KeyCap>>()
    private var shiftIcon: ImageView? = null
    private val deleteKeys = ArrayList<KeyCap>()
    private val pickKeyboard = Runnable {
        if (spaceKey.isPressed && spaceGesture.still) {
            spaceKey.isPressed = false; cursorSlide = null
            context.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
    }
    private val deleteGesture = DeleteGesture(KeyboardMetrics.DELETE_START * density, KeyboardMetrics.DELETE_STEP * density,
        KeyboardMetrics.DELETE_CANCEL * density, KeyboardMetrics.DELETE_STILL * density)
    private var deleteSlide: DeleteSlide? = null
    private val deleteRepeat = DeleteRepeat()
    // Every repeat goes through the engine's delete, so its undos and the word in progress stay right.
    private val repeatDelete = object : Runnable {
        override fun run() {
            if (!deleting) return
            if (deleteRepeat.next()) ime.engine.deleteWord() else ime.engine.delete()
            postDelayed(this, deleteRepeat.delay)
        }
    }

    init {
        orientation = VERTICAL
        rows.orientation = VERTICAL
        setWillNotDraw(false) // The glide trail draws over the keys.
        numberRow.visibility = View.GONE
        addView(numberRow, LayoutParams(LayoutParams.MATCH_PARENT, 0))
        for ((i, d) in "1234567890".withIndex()) {
            val digit = caps.cap(d.toString(), d.toString()) { ime.engine.type(d.toString()) }
            rolls(digit); numberRow.add(digit, i.toFloat(), 1f)
        }
        addView(rows, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight * 3))
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, lastHeight))
        spaceKey = caps.cap("", context.getString(R.string.keyboard_space), KeyFill.Letter) {
            ime.engine.space(spacePoint, if (current?.symbols == false) geometry() else null); spacePoint = null
        }
        // A press that leaves the key still types its space on lifting: a quick tap that drifts must
        // never lose it. Only a slide that moved the cursor, or the long press's picker, types none.
        spaceKey.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Another key going down while space is held types the space first, unless it moved the cursor.
                    roll.down(v) {
                        removeCallbacks(pickKeyboard); val typing = v.isPressed && cursorSlide == null
                        v.isPressed = false; cursorSlide = null
                        if (typing) v.performClick()
                    }
                    v.isPressed = true; cursorSlide = null; spaceGesture.down(e.x, e.y, e.eventTime)
                    spacePoint = KeyPoint((v.left + e.x).toDouble(), (bottom.top - rows.top + e.y).toDouble())
                    postDelayed(pickKeyboard, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> if (v.isPressed) {
                    val characters = spaceGesture.move(e.x, e.y, e.eventTime)
                    if (!spaceGesture.still) removeCallbacks(pickKeyboard)
                    if (characters != null) (cursorSlide ?: ime.engine.cursorSlide().also { cursorSlide = it }).moveTo(characters)
                }
                MotionEvent.ACTION_UP -> if (roll.up(v)) {
                    removeCallbacks(pickKeyboard); val pressed = v.isPressed; v.isPressed = false
                    if (pressed && spaceGesture.up()) v.performClick()
                    cursorSlide = null
                }
                MotionEvent.ACTION_CANCEL -> { roll.up(v); removeCallbacks(pickKeyboard); v.isPressed = false; cursorSlide = null }
            }
            true
        }
        spaceKey.sound = AudioManager.FX_KEYPRESS_SPACEBAR
        enterKey = caps.cap("", context.getString(R.string.keyboard_return), KeyFill.Enter, KeyboardMetrics.LABEL, pill = true) { ime.engine.enter() }
        rolls(comma); periodKey()
        enterKey.sound = AudioManager.FX_KEYPRESS_RETURN
        enterKey.textEntry = false
        modeKey.textEntry = false
        emojiKey.textEntry = false
        numpadKey.textEntry = false
        enterIcon.imageTintList = ColorStateList.valueOf(context.getColor(R.color.kb_enter_ink))
        enterKey.face.addView(enterIcon, FrameLayout.LayoutParams(px(27f), px(27f), Gravity.CENTER))
    }

    /** Comma and period type as they lift, in the order the fingers went down with the letters around
     * them ([Rollover]), and a fast tap that drifts off the key still types, as a letter's does. */
    private fun rolls(key: KeyCap) = key.setOnTouchListener { v, e ->
        fun type() { if (v.isPressed) { v.isPressed = false; v.performClick() } }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { roll.down(v) { type() }; v.isPressed = true }
            MotionEvent.ACTION_UP -> if (roll.up(v)) type()
            MotionEvent.ACTION_CANCEL -> { roll.up(v); v.isPressed = false }
        }
        true
    }

    /** Period types as it lifts, as comma does ([rolls]). Held on the letters page it opens Gboard's
     * punctuation ([LetterFeedback.periodHolds]): lifting on one types it through the engine, as the
     * symbols page's key would, and lifting on the period itself types the period. */
    private fun periodKey() = period.setOnTouchListener { v, e ->
        fun type() {
            val picked = if (feedback.choosing) feedback.selected() else null
            val pressed = v.isPressed; v.isPressed = false; clearCallout()
            if (pressed && picked != null) ime.engine.type(picked) else if (pressed) v.performClick()
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                roll.down(v) { type() }; clearCallout(); v.isPressed = true
                if (current?.symbols == false) {
                    pendingAccents = Runnable { if (v.isPressed) feedback.pick(period.face, LetterFeedback.periodHolds, LetterFeedback.PERIOD_DEFAULT) }
                    postDelayed(pendingAccents!!, KeyboardMetrics.LONG_PRESS)
                }
            }
            MotionEvent.ACTION_MOVE -> if (feedback.choosing) feedback.move(left + bottom.left + v.left + e.x, top + bottom.top + v.top + e.y)
            MotionEvent.ACTION_UP -> if (roll.up(v)) type()
            MotionEvent.ACTION_CANCEL -> { roll.up(v); v.isPressed = false; clearCallout() }
        }
        true
    }

    private fun cased(c: Char) = if (current?.let { !it.symbols && it.shifted } == true) c.uppercaseChar().toString() else c.toString()

    /** Rebuilds the page on screen, so turning the number row on or off shows at once. */
    fun numberRowChanged() { current?.let(::showPage) }

    fun showPage(page: KeyboardPage) {
        val shown = current
        val numbers = ime.feedback.numberRow
        if (shown != null && numbers == plane.numberRow && !page.symbols && page.copy(shifted = shown.shifted, locked = shown.locked) == shown) {
            current = page; relabel(page); return
        }
        current = page
        clearCallout(); grid = null; letters.clear(); caseKeys.clear(); shiftIcon = null; deleteKeys.clear()
        plane = PlaneRows(rowHeight, lastHeight, numbers)
        numberRow.visibility = if (plane.numbers(page) > 0) View.VISIBLE else View.GONE
        numberRow.layoutParams = numberRow.layoutParams.apply { height = plane.numbers(page) }
        rows.layoutParams = rows.layoutParams.apply { height = plane.rows(page) }
        rows.removeAllViews()
        if (page.numpad) showNumpad() else showRows(page)
        showBottom(page)
        val action = page.enter.label
        enterKey.label?.text = action.orEmpty()
        enterIcon.setImageResource(enterIcon(page.enter.action))
        enterIcon.visibility = if (action == null) View.VISIBLE else View.GONE
        enterKey.contentDescription = action ?: actionLabel(page.enter.action) ?: context.getString(R.string.keyboard_return)
    }

    private fun showRows(page: KeyboardPage) {
        val language = page.language
        val symbols = page.symbols
        val lines = page.lines
        val columns = KeyboardMetrics.columns(lines).toFloat()
        val side = KeyboardMetrics.sideWidth(page, lines)
        for ((index, line) in lines.withIndex()) {
            val row = KeyRowLayout(context, columns, this.side, gap, insetTop, insetBottom)
            rows.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            // Rows 1 and 2 centre their keys; row 3's share what shift and delete leave.
            var at = if (index < 2) (columns - line.length) / 2f else 0f
            val width = if (index < 2) 1f else (columns - 2 * side) / line.length
            if (index == 2) {
                val switch = if (symbols) caps.cap(context.getString(if (page.more) R.string.keyboard_numbers_label else R.string.keyboard_page_symbols),
                    context.getString(R.string.keyboard_more_symbols), KeyFill.Function,
                    if (page.more) KeyboardMetrics.LABEL else 17f, weight = 500) { ime.engine.shiftUp() }.also { it.textEntry = false }
                else shiftKey(page)
                row.add(switch, 0f, side); at = side
            }
            for (c in line) {
                // With the number row on, row 1 drops its small digits and their long press, as Gboard's does.
                val digit = if (index == 0 && !symbols && !plane.numberRow) KeyboardLetters.digit(language.layoutCode, c) else null
                row.add(letterKey(c, row, symbols, language.layoutCode, digit), at, width)
                at += width
            }
            if (index == 2) row.add(deleteKey(), columns - side, side)
        }
    }

    private fun shiftKey(page: KeyboardPage): KeyCap {
        val key = caps.icon(shiftIconFor(page), shiftDescription(page), KeyFill.Function, { ime.engine.shiftDown(); ime.engine.shiftUp() })
        val icon = key.face.getChildAt(0) as ImageView
        // Gboard's arrow is wider and flatter than the symbol's: 22 by 21.5 dp.
        icon.layoutParams = FrameLayout.LayoutParams(px(34f), px(28f), Gravity.CENTER)
        icon.scaleType = ImageView.ScaleType.FIT_XY
        shiftIcon = icon
        // Shift acts as it goes down and again as it lifts: a chord types capitals.
        key.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { roll.down(v); v.isPressed = true; ime.engine.shiftDown() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { roll.up(v); if (v.isPressed) { v.isPressed = false; ime.engine.shiftUp() } }
            }
            true
        }
        return key
    }

    /** A letter or symbol key. A long press shows row 1's digit, preselected, then the letter's accents. */
    private fun letterKey(c: Char, row: View, symbols: Boolean, layoutCode: String, digit: Char?): KeyCap {
        var touch: KeyPoint? = null
        // The letter as it was when the finger went down, so shift lifting mid-press can't change it.
        var downText: String? = null
        // Google Sans draws © at superscript size; Gboard shows it as tall as %.
        val size = if (!symbols) KeyboardMetrics.LETTER else if (c == '©') KeyboardMetrics.SYMBOL * 1.5f else KeyboardMetrics.SYMBOL
        val k = caps.cap(cased(c), cased(c), size = size) {
            val g = if (symbols) null else geometry()
            val p = touch ?: g?.centres?.get(c)
            val text = (downText ?: cased(c)).let { if (symbols || touch == null) it else likelier(c, it, touch!!) }
            if (g != null && p != null) ime.engine.letter(text, p.x, p.y, g) else ime.engine.type(text)
        }
        if (digit != null) caps.hint(k, digit)
        if (!symbols) { letters[c] = k; caseKeys.add(c to k) }
        fun options(): List<String> {
            if (symbols) return LetterFeedback.symbolHolds(c)
            val accents = LetterFeedback.options(cased(c).single(), layoutCode)
            return if (digit == null) accents else listOf(digit.toString()) + accents.drop(1)
        }
        if (options().isNotEmpty()) k.longClickLabel = context.getString(R.string.keyboard_more_characters)
        // Types the key as its finger lifts, or earlier when another key goes down first (Rollover).
        fun finish() {
            val text = downText ?: cased(c)
            val selected = feedback.selected()
            val pressed = k.isPressed; k.isPressed = false; clearCallout()
            if (pressed && selected != null && selected != text) {
                val g = geometry(); val p = touch
                if (g != null && p != null && selected.single().isLetter()) ime.engine.letter(selected, p.x, p.y, g, explicit = true)
                else ime.engine.type(selected)
            } else if (pressed) k.performClick()
            touch = null; downText = null
        }
        k.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    // A second finger during a glide types nothing.
                    if (glide.active) return@setOnTouchListener true
                    // A second finger on the same key is a second press: the first types now.
                    if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN && roll.up(k)) finish()
                    roll.down(k) { finish() }
                    clearCallout(); v.isPressed = true
                    downText = cased(c)
                    touch = KeyPoint((v.left + e.x).toDouble(), (row.top + e.y).toDouble())
                    glide.down(v, touch!!, e.eventTime, !symbols && ime.engine.glides, geometry()?.pitchX ?: 0.0)
                    if (ime.feedback.popups && ime.previewAllowed) feedback.present(k.face, listOf(cased(c)))
                    val options = options()
                    if (options.isNotEmpty()) {
                        pendingAccents = Runnable { if (v.isPressed) feedback.present(k.face, options) }
                        postDelayed(pendingAccents!!, KeyboardMetrics.LONG_PRESS)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until e.historySize)
                        glide.add(v, KeyPoint((v.left + e.getHistoricalX(i)).toDouble(), (row.top + e.getHistoricalY(i)).toDouble()), e.getHistoricalEventTime(i))
                    val p = KeyPoint((v.left + e.x).toDouble(), (row.top + e.y).toDouble())
                    // A glide is no longer a held key: the next key down mustn't type its first letter.
                    // A key a later one already typed (Rollover) can't become one, and only its own
                    // hold is released, never a space or delete held since.
                    if ((v.isPressed || glide.active) && glide.move(v, p, e.eventTime, feedback.choosing)) { clearCallout(); v.isPressed = false; roll.up(k) }
                    if (glide.active) { if (ime.feedback.glideTrail) postInvalidateOnAnimation() }
                    else if (feedback.choosing) feedback.move(xInHost(row, v, e.x), yInHost(row, v, e.y))
                    // A fast tap drifts off its key as the finger lifts or moves on: it still types,
                    // only its pop-up and long press go. Drifting far enough is a glide, above.
                    else if (e.x < -slop || e.x > v.width + slop || e.y < -slop || e.y > v.height + slop) clearCallout()
                }
                MotionEvent.ACTION_UP -> {
                    glide.add(v, KeyPoint((v.left + e.x).toDouble(), (row.top + e.y).toDouble()), e.eventTime)
                    val path = glide.up(v, e.eventTime)
                    if (path != null) {
                        touch = null; downText = null
                        geometry()?.let { ime.engine.glide(path, it) }
                        if (ime.feedback.glideTrail) postInvalidateOnAnimation()
                    } else if (roll.up(k)) finish()
                }
                MotionEvent.ACTION_CANCEL -> {
                    glide.cancel(v)
                    if (roll.up(k)) { v.isPressed = false; clearCallout(); touch = null; downText = null }
                }
            }
            true
        }
        return k
    }

    // Two fingers swiped sideways: left undoes, right redoes (KeyboardEngine.undoStep). Both must travel
    // the same way, mostly sideways, so two thumbs typing never swipe; the keys under them are cancelled.
    private val swipeStart = KeyboardMetrics.SWIPE_START * density
    private val swipeStep = KeyboardMetrics.SWIPE_STEP * density
    private var pair: List<Triple<Int, Float, Float>>? = null
    private var pairDownAt = 0L
    private var swipeX: Float? = null

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pair = null; swipeX = null }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pair = if (e.pointerCount == 2) (0..1).map { Triple(e.getPointerId(it), e.getX(it), e.getY(it)) } else null
                pairDownAt = e.eventTime
            }
            MotionEvent.ACTION_POINTER_UP -> pair = null
            MotionEvent.ACTION_MOVE -> {
                val from = pair ?: return false
                val moves = from.map { (id, x, y) ->
                    val i = e.findPointerIndex(id).takeIf { it >= 0 } ?: return false
                    e.getX(i) - x to e.getY(i) - y
                }
                val sideways = moves.all { (dx, dy) -> kotlin.math.abs(dx) >= swipeStart && kotlin.math.abs(dx) > 2 * kotlin.math.abs(dy) }
                if (sideways && moves[0].first > 0 == moves[1].first > 0) {
                    // Intercepting cancels the keys under both fingers, each clearing its own press.
                    val left = moves[0].first < 0
                    swipeX = middle(e)
                    swipe(left, first = true)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val from = swipeX ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val x = middle(e)
                if (kotlin.math.abs(x - from) >= swipeStep) { swipe(x < from, first = false); swipeX = x }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { swipeX = null; pair = null }
        }
        return true
    }

    private fun middle(e: MotionEvent) = (0 until e.pointerCount).sumOf { e.getX(it).toDouble() }.toFloat() / e.pointerCount

    private fun swipe(left: Boolean, first: Boolean) {
        // The second finger's key typed the first finger's at once (Rollover); the first undo takes it back too.
        val stray = if (first) pairDownAt else null
        val done = if (left) ime.engine.undoStep(stray) else ime.engine.redoStep(stray)
        if (done && vibrates(context, ime.feedback.haptics)) performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (ime.feedback.glideTrail && glide.draw(canvas, rows.left.toFloat(), rows.top.toFloat(), SystemClock.uptimeMillis()))
            postInvalidateOnAnimation()
    }

    private fun deleteKey(): KeyCap {
        val key = caps.icon(R.drawable.ic_delete, context.getString(R.string.keyboard_delete), KeyFill.Function, { ime.engine.delete() }, size = 35f)
        deleteKeys += key
        key.sound = AudioManager.FX_KEYPRESS_DELETE
        key.textEntry = false
        fun lift() {
            removeCallbacks(repeatDelete); key.isPressed = false
            val slide = deleteSlide
            when {
                slide != null -> if (slide.words > 0) ime.engine.deleteSelection()
                deleting && !deleteRepeat.repeating && deleteGesture.tap -> key.performClick()
            }
            deleting = false; deleteSlide = null
        }
        key.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    roll.down(v) { lift() }
                    deleting = true; v.isPressed = true; deleteSlide = null
                    deleteGesture.down(e.x)
                    postDelayed(repeatDelete, deleteRepeat.start())
                }
                MotionEvent.ACTION_MOVE -> if (deleting) {
                    val words = deleteGesture.move(e.x)
                    // Before it repeats, a slide sideways stops the repeat and a slide left selects
                    // words. Once it repeats it keeps on until the finger lifts, however the thumb rolls.
                    if (!deleteRepeat.repeating) {
                        if (!deleteGesture.still) removeCallbacks(repeatDelete)
                        if (words > 0 && deleteSlide == null) deleteSlide = ime.engine.deleteSlide()
                    }
                    deleteSlide?.select(words)
                }
                MotionEvent.ACTION_UP -> if (roll.up(v)) lift()
                MotionEvent.ACTION_CANCEL -> {
                    roll.up(v); removeCallbacks(repeatDelete); deleting = false; v.isPressed = false
                    deleteSlide?.select(0); deleteSlide = null
                }
            }
            true
        }
        return key
    }

    /** Gboard's number pad: an operator list down the left, the digits, then %, space and delete. */
    private fun showNumpad() {
        val digitInsetBottom = rowHeight - insetTop - px(if (landscape) KeyboardMetrics.KEY_HEIGHT_LANDSCAPE else KeyboardMetrics.NUMPAD_KEY_HEIGHT)
        val pad = KeyGridLayout(context, 10f, 3, side, gap, insetTop, digitInsetBottom)
        rows.addView(pad, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        pad.add(operators(), 0, 0f, 1.5f, rowSpan = 3)
        val digit = 7f / 3
        for (r in 0..2) for (i in 0..2) {
            val d = (r * 3 + i + 1).toString()
            pad.add(caps.cap(d, d, size = KeyboardMetrics.NUMPAD_DIGIT) { ime.engine.type(d) }, r, 1.5f + i * digit, digit)
        }
        pad.add(caps.cap("%", "%", KeyFill.Function, KeyboardMetrics.SYMBOL) { ime.engine.type("%") }, 0, 8.5f, 1.5f)
        pad.add(caps.cap("␣", context.getString(R.string.keyboard_space), KeyFill.Function, KeyboardMetrics.SYMBOL) { ime.engine.type(" ") }, 1, 8.5f, 1.5f)
        pad.add(deleteKey(), 2, 8.5f, 1.5f)
    }

    /** The number pad's scrolling list of operators, one keycap tall across three rows. */
    private fun operators(): View {
        val holder = FrameLayout(context)
        val face = FrameLayout(context).apply {
            background = GradientDrawable().apply { cornerRadius = KeyboardMetrics.CORNER * density; setColor(context.getColor(R.color.kb_function)) }
            clipToOutline = true
        }
        val list = LinearLayout(context).apply { orientation = VERTICAL }
        for (op in listOf("+", "-", "*", "/", "(", ")", "#", ",", ";", "N")) {
            list.addView(caps.text(TextView(context), KeyboardMetrics.SYMBOL).apply {
                text = op; gravity = Gravity.CENTER; setTextColor(context.getColor(R.color.kb_ink)); contentDescription = op
                isFocusable = true
                setOnClickListener { ime.engine.type(op) }
            }, LinearLayout.LayoutParams(-1, px(36f)))
        }
        face.addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = true; scrollBarStyle = SCROLLBARS_INSIDE_INSET; overScrollMode = OVER_SCROLL_NEVER
            addView(list)
        }, FrameLayout.LayoutParams(-1, -1))
        holder.addView(face, FrameLayout.LayoutParams(-1, -1))
        return holder
    }

    /** Row 4 for each kind of page: letters, the two symbol pages, and the number pad. */
    private fun showBottom(page: KeyboardPage) {
        val kind = when { page.numpad -> 3; !page.symbols -> 0; page.more -> 2; else -> 1 }
        modeKey.label?.text = context.getString(if (page.symbols) R.string.keyboard_letters_label else R.string.keyboard_numbers_label)
        modeKey.contentDescription = context.getString(if (page.symbols) R.string.keyboard_letters else R.string.keyboard_numbers)
        commaText = if (kind == 0) page.kind.mark else ","
        comma.label?.text = commaText
        comma.contentDescription = context.getString(when (commaText) {
            "@" -> R.string.keyboard_at; "/" -> R.string.keyboard_slash; else -> R.string.keyboard_comma })
        if (kind == bottomKind) return
        bottomKind = kind
        // A held space keeps its press: it is re-added, never recreated.
        bottom.removeAllViews()
        val keys = when (kind) {
            0 -> listOf(modeKey to 1.5f, comma to 1f, emojiKey to 1f, spaceKey to 4f, period to 1f, enterKey to 1.5f)
            1 -> listOf(modeKey to 1.5f, comma to 1f, numpadKey to 1f, spaceKey to 4f, period to 1f, enterKey to 1.5f)
            2 -> listOf(modeKey to 1.5f, lessKey to 1f, numpadKey to 1f, spaceKey to 4f, moreKey to 1f, enterKey to 1.5f)
            else -> listOf(modeKey to 1.5f, comma to 1f,
                caps.cap("!?#", context.getString(R.string.keyboard_numbers), KeyFill.Letter, 19f) { ime.engine.symbolPage() } to 4f / 3,
                caps.cap("0", "0", size = KeyboardMetrics.NUMPAD_DIGIT) { ime.engine.type("0") } to 7f / 3,
                caps.cap("=", "=", KeyFill.Letter, KeyboardMetrics.SYMBOL) { ime.engine.type("=") } to 4f / 3,
                period to 1f, enterKey to 1.5f)
        }
        var at = 0f
        for ((key, width) in keys) { bottom.add(key, at, width); at += width }
    }

    private fun shiftIconFor(page: KeyboardPage) = when { page.locked -> R.drawable.ic_caps_lock; page.shifted -> R.drawable.ic_shift_on; else -> R.drawable.ic_shift }
    private fun shiftDescription(page: KeyboardPage) = context.getString(if (page.locked) R.string.keyboard_caps_lock else R.string.keyboard_shift)
    /** Shift changed and nothing else: new labels on the same keys. */
    private fun relabel(page: KeyboardPage) {
        for ((c, key) in caseKeys) { val text = cased(c); key.label?.text = text; key.contentDescription = text }
        shiftIcon?.setImageResource(shiftIconFor(page))
        (shiftIcon?.parent?.parent as? View)?.contentDescription = shiftDescription(page)
    }

    private fun enterIcon(action: Int) = when (action) {
        EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_search
        EditorInfo.IME_ACTION_SEND -> R.drawable.ic_send
        EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> R.drawable.ic_go
        EditorInfo.IME_ACTION_DONE -> R.drawable.ic_check
        else -> R.drawable.ic_enter
    }
    private fun actionLabel(action: Int) = when (action) {
        EditorInfo.IME_ACTION_DONE -> R.string.keyboard_action_done
        EditorInfo.IME_ACTION_GO -> R.string.keyboard_action_go
        EditorInfo.IME_ACTION_NEXT -> R.string.keyboard_action_next
        EditorInfo.IME_ACTION_SEARCH -> R.string.keyboard_action_search
        EditorInfo.IME_ACTION_SEND -> R.string.keyboard_action_send
        else -> null
    }?.let(context::getString)

    /** The letter a touch types: its own key's, or a neighbour's the word list makes likelier when the
     * touch fell in the gap beside its keycap, within TypingWord.REACH dp of that neighbour's touch
     * area ([nearKeys]). A touch on the keycap itself always types its key. */
    private fun likelier(c: Char, text: String, touch: KeyPoint): String {
        val own = letters[c] ?: return text
        val areas = HashMap<Char, KeyRect>()
        for ((other, cap) in letters) if (other != c) {
            val row = cap.parent as View
            areas[if (text.single().isUpperCase()) other.uppercaseChar() else other] =
                KeyRect(cap.left.toDouble(), row.top.toDouble(), cap.right.toDouble(), row.bottom.toDouble())
        }
        val row = own.parent as View
        val face = KeyRect((own.left + own.paddingLeft).toDouble(), (row.top + own.paddingTop).toDouble(),
            (own.right - own.paddingRight).toDouble(), (row.top + own.height - own.paddingBottom).toDouble())
        val near = nearKeys(touch, face, areas, TypingWord.REACH * density).mapValues { it.value / density }
        if (near.isEmpty()) return text
        return ime.engine.likelierLetter(text.single(), near, letters.keys).toString()
    }

    /** A touch's x in the host's coordinates, where the accent callout is drawn. */
    private fun xInHost(row: View, key: View, x: Float) = left + rows.left + row.left + key.left + x
    private fun yInHost(row: View, key: View, y: Float) = top + rows.top + row.top + key.top + y

    /** Each letter's drawn keycap centre, in the rows' coordinates, which the touches use too. */
    private fun capCentre(cap: KeyCap) = KeyPoint(cap.left + cap.paddingLeft + (cap.width - cap.paddingLeft - cap.paddingRight) / 2.0,
        (cap.parent as View).top + cap.paddingTop + (cap.height - cap.paddingTop - cap.paddingBottom) / 2.0)
    private fun spaceRect(): KeyRect? {
        if (spaceKey.width == 0) return null
        val top = bottom.top - rows.top + spaceKey.paddingTop.toDouble()
        return KeyRect(spaceKey.left + spaceKey.paddingLeft.toDouble(), top, spaceKey.right - spaceKey.paddingRight.toDouble(),
            top + spaceKey.height - spaceKey.paddingTop - spaceKey.paddingBottom)
    }
    private fun geometry(): KeyGrid? {
        grid?.let { return it }
        val q = letters['q'] ?: letters['a'] ?: return null
        val w = letters['w'] ?: letters['z'] ?: return null
        val a = letters['a'] ?: return null
        if (q.width == 0 || w.left == q.left) return null
        val centres = letters.mapValues { (_, cap) -> capCentre(cap) }
        return KeyGrid(centres, centres.getValue(if ('w' in letters) 'w' else 'z').x - centres.getValue(if ('q' in letters) 'q' else 'a').x,
            capCentre(a).y - capCentre(q).y, spaceRect()).also { grid = it }
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (changed) grid = null
    }
    fun clearCallout() {
        pendingAccents?.let(::removeCallbacks); pendingAccents = null; feedback.clear()
    }
    fun cancelGestures() {
        clearCallout(); roll.clear()
        glide.cancel(); invalidate()
        removeCallbacks(repeatDelete); removeCallbacks(pickKeyboard)
        spaceKey.isPressed = false
        deleting = false
        for (key in deleteKeys) key.isPressed = false
    }
    override fun onDetachedFromWindow() { cancelGestures(); super.onDetachedFromWindow() }
}
