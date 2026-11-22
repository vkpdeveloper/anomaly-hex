package ly.anoma.hex.keyboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ly.anoma.hex.MainActivity
import ly.anoma.hex.R
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.useSans
import kotlin.math.roundToInt

/** Gboard's toolbar above the keys: the grid button on the left, the mic in its circle on the right
 * (with the fix button before it, when that is on), and between them the three suggestions, the
 * clipboard chip for something just copied, or the recording's level while dictating. */
internal class Toolbar(context: Context, private val ime: HexKeyboard) : FrameLayout(context) {
    private companion object {
        const val SUGGESTION = 19.5f
        const val CHIP_WIDTH = 143f
        /** A copy older than this no longer gets the chip. */
        const val CLIP_FRESH_MS = 10 * 60_000L
        const val NOTE_MS = 3_000L
        const val HOLD_MS = 350L
    }
    private val density = resources.displayMetrics.density
    private fun px(value: Float) = (value * density).roundToInt()
    private fun color(id: Int) = context.getColor(id)
    private val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val toolbarHeight = px(if (landscape) KeyboardMetrics.TOOLBAR_LANDSCAPE else KeyboardMetrics.TOOLBAR)
    private val centre = px(if (landscape) KeyboardMetrics.TOOLBAR_CENTER_LANDSCAPE else KeyboardMetrics.TOOLBAR_CENTER)
    private val pill = px(KeyboardMetrics.PILL)

    private val grid = button(R.drawable.ic_grid, 25f, R.string.keyboard_open_hex) {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private val cancel = button(R.drawable.ic_cancel, 22f, R.string.keyboard_cancel_dictation) { ime.dictation.cancel() }
    private val fix = button(R.drawable.ic_spellcheck, 24f, R.string.keyboard_fix) { ime.fix() }
    private val mic = FrameLayout(context)
    private val micFace = View(context)
    private val micIcon = ImageView(context)

    private val slots = LinearLayout(context)
    private val slotViews = List(3) { FrameLayout(context) }
    private val words = List(3) { TextView(context) }
    private val dividers = List(2) { View(context) }
    private var offered = arrayOfNulls<String>(3)

    private val chip = LinearLayout(context)
    private val chipText = TextView(context)
    private val noteText = TextView(context)
    private val undo = LinearLayout(context)
    private val undoText = TextView(context)
    private var undoOffer: UndoOffer? = null
    private val well = FrameLayout(context)
    private val scope = DotScopeView(context)
    private val wellText = TextView(context)

    private var phase = DictationPhase.Idle
    private var note: String? = null
    private val clearNote = Runnable { note = null; update() }
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private var clip: String? = null
    private var clipAt = 0L
    private val clipChanged = ClipboardManager.OnPrimaryClipChangedListener { readClip(fresh = true) }

    // The mic: a tap starts and a second tap finishes; a hold of 350 ms is push to talk, finishing on lift.
    private var held = false
    private val hold = Runnable { if (mic.isPressed && phase == DictationPhase.Idle) held = ime.dictation.start(stopsItself = false) }

    init {
        // Everything centres on the line Gboard's toolbar icons sit on, a little above the middle.
        setPadding(0, 0, 0, (toolbarHeight - 2 * centre).coerceAtLeast(0))

        for (i in 0..2) {
            if (i > 0) slots.addView(dividers[i - 1].apply { setBackgroundColor(color(R.color.kb_function)) },
                LinearLayout.LayoutParams(px(1f), px(24f)).apply { gravity = Gravity.CENTER_VERTICAL })
            words[i].apply {
                text(this, SUGGESTION); gravity = Gravity.CENTER; setTextColor(color(R.color.kb_ink))
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding(px(6f), 0, px(6f), 0); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            slotViews[i].apply {
                isFocusable = true
                background = pressable(android.graphics.Color.TRANSPARENT, false)
                setOnClickListener { offered[i]?.let(ime.engine::pickSuggestion) }
                // As wide as its slot, so a new word only redraws the strip; a word sized to its text
                // would measure the whole keyboard again on every key.
                addView(words[i], LayoutParams(-1, -1, Gravity.CENTER))
            }
            slots.addView(slotViews[i], LinearLayout.LayoutParams(0, -1, 1f))
        }
        // Gboard's dividers sit at a third and two thirds of the span between the icons' edges.
        addView(slots, LayoutParams(-1, -1).apply { marginStart = px(32.6f); marginEnd = px(34.4f) })

        chip.apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pressable(color(R.color.kb_key), false, pill / 2f)
            setPadding(px(12f), 0, px(14f), 0); isFocusable = true
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_clipboard); imageTintList = ColorStateList.valueOf(color(R.color.kb_ink))
            }, LinearLayout.LayoutParams(px(20.2f), px(20.2f)).apply { marginEnd = px(8.2f) })
            chipText.apply {
                text(this, 16.5f); useSans(500); setTextColor(color(R.color.kb_ink)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            addView(chipText, LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener { clip?.let { ime.engine.paste(it) }; clip = null; update() }
        }
        addView(chip, LayoutParams(px(CHIP_WIDTH), pill, Gravity.CENTER))

        // Undo dictation or the fix button's fixes, in place of the suggestions until the next edit (KeyboardEngine.UNDO_MS).
        undo.apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pressable(color(R.color.kb_key), false, pill / 2f)
            setPadding(px(12f), 0, px(14f), 0); isFocusable = true
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_undo); imageTintList = ColorStateList.valueOf(color(R.color.kb_ink))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(px(20.2f), px(20.2f)).apply { marginEnd = px(8.2f) })
            addView(undoText.apply {
                text(this, 16.5f); useSans(500); setTextColor(color(R.color.kb_ink)); maxLines = 1
            }, LinearLayout.LayoutParams(-2, -2))
            setOnClickListener { ime.undo() }
        }
        addView(undo, LayoutParams(-2, pill, Gravity.CENTER))

        noteText.apply {
            text(this, 15f); setTextColor(color(R.color.kb_quiet)); gravity = Gravity.CENTER
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addView(noteText, LayoutParams(-1, -1).apply { marginStart = px(48f); marginEnd = px(48f) })

        well.background = GradientDrawable().apply { setColor(color(R.color.kb_key)); cornerRadius = pill / 2f }
        well.clipToOutline = true
        well.addView(scope, LayoutParams(-1, -1).apply { marginStart = px(12f); marginEnd = px(12f) })
        wellText.apply {
            text(this, 15f); setTextColor(color(R.color.kb_quiet)); gravity = Gravity.CENTER
            setText(R.string.keyboard_transcribing)
        }
        well.addView(wellText, LayoutParams(-1, -1))
        addView(well, LayoutParams(-1, pill, Gravity.CENTER_VERTICAL).apply { marginStart = px(48f); marginEnd = px(50f) })

        addView(grid, LayoutParams(px(45.4f), -1, Gravity.START))
        addView(cancel, LayoutParams(px(45.4f), -1, Gravity.START))

        mic.apply {
            isFocusable = true
            micFace.isDuplicateParentStateEnabled = true
            addView(micFace, LayoutParams(pill, pill, Gravity.CENTER))
            micIcon.setImageResource(R.drawable.ic_mic)
            addView(micIcon, LayoutParams(px(24f), px(24f), Gravity.CENTER))
            setOnClickListener { ime.dictation.toggle() }
            setOnTouchListener { v, e -> micTouch(v, e) }
        }
        addView(mic, LayoutParams(px(46.2f), -1, Gravity.END))
        addView(fix, LayoutParams(px(45.4f), -1, Gravity.END).apply { marginEnd = px(46.2f) })
        dictation(DictationPhase.Idle)
    }

    private fun micTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { held = false; v.isPressed = true; postDelayed(hold, HOLD_MS) }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(hold)
                val pressed = v.isPressed; v.isPressed = false
                if (held) ime.dictation.finish() else if (pressed) v.performClick()
                held = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold); if (held) ime.dictation.cancel()
                held = false; v.isPressed = false
            }
        }
        return true
    }

    /** The typing model's slots in its order. With what was typed among them they show as given: typed
     * on the left, then the fix or the likeliest word in the middle. Without it, the likeliest word takes
     * the middle and the next the left, as Gboard places them. */
    fun suggestions(list: List<Suggestion>) {
        val shown = arrayOfNulls<Suggestion>(3)
        if (list.any { it.kind == Suggestion.Kind.Typed }) list.take(3).forEachIndexed { i, slot -> shown[i] = slot }
        else { shown[1] = list.getOrNull(0); shown[0] = list.getOrNull(1); shown[2] = list.getOrNull(2) }
        offered = Array(3) { shown[it]?.text }
        for (i in 0..2) {
            val slot = shown[i]
            val s = slot?.text.orEmpty()
            words[i].text = s
            // The word space will put in gets Gboard's heavier weight.
            words[i].useSans(if (slot?.kind == Suggestion.Kind.Fix) 600 else 400)
            slotViews[i].contentDescription = when {
                s.isEmpty() -> null
                slot?.kind == Suggestion.Kind.Typed -> context.getString(R.string.keyboard_keep, s)
                slot?.kind == Suggestion.Kind.Fix -> context.getString(R.string.keyboard_correction, s)
                else -> context.getString(R.string.keyboard_suggest, s)
            }
            slotViews[i].isEnabled = s.isNotEmpty()
        }
        update()
    }

    fun dictation(phase: DictationPhase) {
        this.phase = phase
        val listening = phase == DictationPhase.Listening
        micFace.background = pressable(color(if (listening) R.color.kb_enter else R.color.kb_key), true)
        micIcon.imageTintList = ColorStateList.valueOf(color(when (phase) {
            DictationPhase.Listening -> R.color.kb_enter_ink
            DictationPhase.Sending -> R.color.kb_off
            DictationPhase.Idle -> R.color.kb_ink
        }))
        mic.contentDescription = context.getString(when (phase) {
            DictationPhase.Idle -> R.string.keyboard_dictate
            DictationPhase.Listening -> R.string.keyboard_finish_dictation
            DictationPhase.Sending -> R.string.keyboard_transcribing
        })
        scope.clear()
        scope.visibility = if (listening) VISIBLE else GONE
        wellText.visibility = if (phase == DictationPhase.Sending) VISIBLE else GONE
        scope.contentDescription = context.getString(R.string.keyboard_recording)
        if (phase != DictationPhase.Idle) { removeCallbacks(clearNote); note = null }
        update()
    }
    fun level(peak: Float) = scope.push(peak)
    fun undo(offer: UndoOffer?) {
        undoOffer = offer
        undoText.setText(if (offer == UndoOffer.Fixes) R.string.keyboard_undo_fixes else R.string.keyboard_undo_dictation)
        update()
    }

    /** A short line in the toolbar's middle, such as why dictation couldn't start. */
    fun note(text: String) {
        note = text; removeCallbacks(clearNote); postDelayed(clearNote, NOTE_MS); update()
    }

    fun update() {
        val idle = phase == DictationPhase.Idle
        val fixShown = idle && ime.fixShown
        fix.visibility = if (fixShown) VISIBLE else GONE
        fix.alpha = if (ime.fixing) 0.4f else 1f
        // The suggestions and notes make room for the fix button.
        endMargin(slots, if (fixShown) 80f else 34.4f)
        endMargin(noteText, if (fixShown) 94f else 48f)
        val suggesting = offered.any { it != null }
        val noting = idle && note != null
        well.visibility = if (idle) GONE else VISIBLE
        cancel.visibility = if (idle) GONE else VISIBLE
        grid.visibility = if (idle) VISIBLE else GONE
        noteText.visibility = if (noting) VISIBLE else GONE
        noteText.text = note
        val undoing = idle && !noting && undoOffer != null
        undo.visibility = if (undoing) VISIBLE else GONE
        slots.visibility = if (idle && !noting && !undoing && suggesting) VISIBLE else GONE
        val clipShown = idle && !noting && !undoing && !suggesting && clip != null &&
            System.currentTimeMillis() - clipAt < CLIP_FRESH_MS
        chip.visibility = if (clipShown) VISIBLE else GONE
    }

    /** What was last copied, if it is text: [fresh] when it was copied just now, else the clip's own time. */
    private fun readClip(fresh: Boolean) {
        val primary = runCatching { clipboard.primaryClip }.getOrNull()
        val description = primary?.description
        val text = primary?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
        if (text.isNullOrEmpty() || description == null) { if (fresh) { clip = null; update() }; return }
        val at = if (fresh) System.currentTimeMillis() else description.timestamp
        if (!fresh && at == clipAt) return
        val sensitive = Build.VERSION.SDK_INT >= 33 &&
            description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        clip = text; clipAt = at
        chipText.text = if (sensitive) "••••••" else text.replace('\n', ' ')
        chip.contentDescription = context.getString(R.string.keyboard_paste_clip, if (sensitive) "" else chipText.text)
        update()
    }
    /** The keyboard came up: offer a recent copy, even one made while it was hidden. */
    fun refresh() { readClip(fresh = false); update() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); clipboard.addPrimaryClipChangedListener(clipChanged) }
    override fun onDetachedFromWindow() {
        clipboard.removePrimaryClipChangedListener(clipChanged); cancelGestures(); super.onDetachedFromWindow()
    }
    fun cancelGestures() { removeCallbacks(hold); held = false; mic.isPressed = false }

    private fun endMargin(view: View, dp: Float) {
        val params = view.layoutParams as LayoutParams
        if (params.marginEnd != px(dp)) { params.marginEnd = px(dp); view.layoutParams = params }
    }
    private fun text(view: TextView, dp: Float) = view.apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, dp); useSans(); includeFontPadding = false
    }
    private fun button(icon: Int, size: Float, description: Int, click: () -> Unit) = FrameLayout(context).apply {
        isFocusable = true; contentDescription = context.getString(description)
        setOnClickListener { click() }
        addView(View(context).apply { isDuplicateParentStateEnabled = true; background = pressable(android.graphics.Color.TRANSPARENT, true) },
            LayoutParams(pill + px(4f), pill + px(4f), Gravity.CENTER))
        addView(ImageView(context).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(color(R.color.kb_ink)) },
            LayoutParams(px(size), px(size), Gravity.CENTER))
    }
    private fun pressable(fill: Int, round: Boolean, radius: Float = 0f) = StateListDrawable().apply {
        fun shape(c: Int) = GradientDrawable().apply {
            if (round) shape = GradientDrawable.OVAL else cornerRadius = radius
            setColor(c)
        }
        addState(intArrayOf(android.R.attr.state_pressed), shape(color(R.color.kb_key_pressed)))
        addState(intArrayOf(), shape(fill))
    }
}
