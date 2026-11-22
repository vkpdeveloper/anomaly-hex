package ly.anoma.hex.keyboard

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ly.anoma.hex.R
import ly.anoma.hex.typing.Suggestion
import ly.anoma.hex.useSans
import kotlin.math.roundToInt

/** Gboard's toolbar above the keys: the grid button for the menu on the left, the mic in its circle on
 * the right, and between them the suggestions (three words, or two and their emoji), the autofill
 * service's chips ([InlineAutofill]), the chip for something just copied or a screenshot just taken,
 * or the recording's level while dictating. */
internal class Toolbar(context: Context, private val ime: HexKeyboard, openMenu: () -> Unit) : FrameLayout(context) {
    private companion object {
        const val SUGGESTION = 19.5f
        const val SUGGESTION_MIN = 12f
        const val CHIP_WIDTH = 143f
        const val EMOJI = 21f
        const val EMOJI_SLOT = 46f
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

    private val grid = button(R.drawable.ic_grid, 25f, R.string.keyboard_menu, openMenu)
    private val cancel = button(R.drawable.ic_cancel, 22f, R.string.keyboard_cancel_dictation) { ime.dictation.cancel() }
    private val mic = FrameLayout(context)
    private val micFace = View(context)
    private val micIcon = ImageView(context)

    private val slots = LinearLayout(context)
    // Three word slots, then two narrower ones for emoji, which take the third word's place.
    private val slotViews = List(5) { FrameLayout(context) }
    private val words = List(5) { TextView(context) }
    private val dividers = List(4) { View(context) }
    private var offered = arrayOfNulls<Suggestion>(5)
    private var fitted = 0f
    /** The strip holds only next-word guesses, which a fresh copy or photo replaces. */
    private var guessing = false
    /** A word is being typed or the caret is in one: its suggestions take the autofill chips' place. */
    private var typingWord = false

    // The autofill service's chips, which it draws and fills the field from: the rest scroll, the pinned
    // stay at the end by the mic.
    private val autofill = LinearLayout(context)
    private val autofillRest = LinearLayout(context)
    private val autofillPinned = LinearLayout(context)

    private val chip = LinearLayout(context)
    private val chipIcon = ImageView(context)
    private val chipText = TextView(context)
    private val chipImage = ImageView(context)
    private var chipOffer: ChipOffer? = null
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
    private val expire = Runnable { update() }

    // The mic: a tap starts and a second tap finishes; a hold of 350 ms is push to talk, finishing on lift.
    private var held = false
    private val hold = Runnable { if (mic.isPressed && phase == DictationPhase.Idle) held = ime.dictation.start(stopsItself = false) }

    init {
        // Everything centres on the line Gboard's toolbar icons sit on, a little above the middle.
        setPadding(0, 0, 0, (toolbarHeight - 2 * centre).coerceAtLeast(0))

        for (i in 0..4) {
            if (i > 0) slots.addView(dividers[i - 1].apply { setBackgroundColor(color(R.color.kb_function)) },
                LinearLayout.LayoutParams(px(1f), px(24f)).apply { gravity = Gravity.CENTER_VERTICAL })
            words[i].apply {
                text(this, if (i < 3) SUGGESTION else EMOJI); gravity = Gravity.CENTER; setTextColor(color(R.color.kb_ink))
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding(px(6f), 0, px(6f), 0); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            slotViews[i].apply {
                isFocusable = true
                background = pressable(android.graphics.Color.TRANSPARENT, false)
                setOnClickListener {
                    val slot = offered[i] ?: return@setOnClickListener
                    if (slot.kind == Suggestion.Kind.Emoji) ime.engine.pickEmoji(slot.text) else ime.engine.pickSuggestion(slot.text)
                }
                // As wide as its slot, so a new word only redraws the strip; a word sized to its text
                // would measure the whole keyboard again on every key.
                addView(words[i], LayoutParams(-1, -1, Gravity.CENTER))
            }
            // The last emoji keeps clear of the mic's circle.
            if (i == 4) words[i].setPadding(px(6f), 0, px(14f), 0)
            slots.addView(slotViews[i], when (i) {
                in 0..2 -> LinearLayout.LayoutParams(0, -1, 1f)
                3 -> LinearLayout.LayoutParams(px(EMOJI_SLOT), -1)
                else -> LinearLayout.LayoutParams(px(EMOJI_SLOT + 8f), -1)
            })
        }
        // The words share one size, as Gboard's do: the largest at which each fits its slot.
        slots.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitWords() }
        // Gboard's dividers sit at a third and two thirds of the span between the icons' edges.
        addView(slots, LayoutParams(-1, -1).apply { marginStart = px(32.6f); marginEnd = px(34.4f) })

        autofill.apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER
                addView(autofillRest.apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL },
                    LayoutParams(-2, -1))
            }, LinearLayout.LayoutParams(0, -1, 1f))
            addView(autofillPinned.apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL },
                LinearLayout.LayoutParams(-2, -1))
        }
        addView(autofill, LayoutParams(-1, -1).apply { marginStart = px(46f); marginEnd = px(48f) })

        chip.apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pressable(color(R.color.kb_key), false, pill / 2f)
            setPadding(px(12f), 0, px(14f), 0); isFocusable = true
            chipIcon.imageTintList = ColorStateList.valueOf(color(R.color.kb_ink))
            addView(chipIcon, LinearLayout.LayoutParams(px(20.2f), px(20.2f)).apply { marginEnd = px(8.2f) })
            chipText.apply {
                text(this, 16.5f); useSans(500); setTextColor(color(R.color.kb_ink)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            addView(chipText, LinearLayout.LayoutParams(0, -2, 1f))
            chipImage.apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                background = GradientDrawable().apply { setColor(color(R.color.kb_function)); cornerRadius = px(5f).toFloat() }
            }
            addView(chipImage, LinearLayout.LayoutParams(px(44f), px(26f)))
            setOnClickListener {
                val offer = chipOffer ?: return@setOnClickListener
                when (offer) {
                    is ChipOffer.Text -> ime.engine.paste(offer.text)
                    is ChipOffer.Image -> ime.clips.insert(offer.clip)
                    is ChipOffer.Photo -> ime.clips.insert(offer.photo)
                }
                ime.clips.used(offer)
            }
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
     * the middle and the next the left, as Gboard places them. With emoji, two words come first, in
     * the order given, and up to two emoji after them in the third word's place. */
    fun suggestions(list: List<Suggestion>) {
        val shown = arrayOfNulls<Suggestion>(5)
        val emoji = list.filter { it.kind == Suggestion.Kind.Emoji }.take(2)
        val words = list.filter { it.kind != Suggestion.Kind.Emoji }
        if (emoji.isNotEmpty() || words.any { it.kind == Suggestion.Kind.Typed }) {
            words.take(if (emoji.isEmpty()) 3 else 2).forEachIndexed { i, slot -> shown[i] = slot }
        } else { shown[1] = words.getOrNull(0); shown[0] = words.getOrNull(1); shown[2] = words.getOrNull(2) }
        emoji.forEachIndexed { i, slot -> shown[3 + i] = slot }
        offered = shown
        guessing = words.all { it.kind == Suggestion.Kind.Next }
        typingWord = wordInProgress(words)
        val emojiShown = emoji.isNotEmpty()
        slotViews[2].visibility = if (emojiShown) GONE else VISIBLE
        dividers[1].visibility = slotViews[2].visibility
        dividers[2].visibility = if (emojiShown) VISIBLE else GONE
        slotViews[3].visibility = dividers[2].visibility
        dividers[3].visibility = if (emoji.size > 1) VISIBLE else GONE
        slotViews[4].visibility = dividers[3].visibility
        for (i in 0..4) {
            val slot = shown[i]
            val s = slot?.text.orEmpty()
            this.words[i].text = s
            // The word space will put in gets Gboard's heavier weight.
            if (i < 3) this.words[i].useSans(if (slot?.kind == Suggestion.Kind.Fix) 600 else 400)
            slotViews[i].contentDescription = when {
                s.isEmpty() -> null
                slot?.kind == Suggestion.Kind.Typed -> context.getString(R.string.keyboard_keep, s)
                slot?.kind == Suggestion.Kind.Fix -> context.getString(R.string.keyboard_correction, s)
                else -> context.getString(R.string.keyboard_suggest, s)
            }
            slotViews[i].isEnabled = s.isNotEmpty()
        }
        fitWords()
        update()
    }

    // One size for every word shown: the largest up to Gboard's 19.5 dp at which each fits its slot, down
    // to 12 dp; a word too long even then is cut. Set only when it changes, since a new size lays the
    // keyboard out again.
    private fun fitWords() {
        var size = px(SUGGESTION).toFloat()
        for (i in 0..2) {
            val view = words[i]
            val room = slotViews[i].width - view.paddingLeft - view.paddingRight
            if (slotViews[i].visibility != VISIBLE || view.text.isEmpty() || room <= 0) continue
            val paint = android.text.TextPaint(view.paint).apply { textSize = px(SUGGESTION).toFloat() }
            val wide = paint.measureText(view.text.toString())
            if (wide > room) size = minOf(size, px(SUGGESTION) * room / wide)
        }
        size = size.coerceAtLeast(px(SUGGESTION_MIN).toFloat())
        if (size == fitted) return
        fitted = size
        for (i in 0..2) words[i].setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
    }

    /** The autofill service's chips for this field, already inflated; none clears them. */
    fun autofill(unpinned: List<View>, pinned: List<View>) {
        autofillRest.removeAllViews(); autofillPinned.removeAllViews()
        for ((row, chips) in listOf(autofillRest to unpinned, autofillPinned to pinned)) for (chip in chips) {
            row.addView(chip, LinearLayout.LayoutParams(-2, pill).apply { marginStart = px(3f); marginEnd = px(3f) })
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
        val suggesting = offered.any { it != null }
        val noting = idle && note != null
        well.visibility = if (idle) GONE else VISIBLE
        cancel.visibility = if (idle) GONE else VISIBLE
        grid.visibility = if (idle) VISIBLE else GONE
        noteText.visibility = if (noting) VISIBLE else GONE
        noteText.text = note
        val undoing = idle && !noting && undoOffer != null
        undo.visibility = if (undoing) VISIBLE else GONE
        // The field's own saved passwords and codes come before a copy or next-word guesses.
        val filling = idle && !noting && !undoing && !typingWord &&
            (autofillRest.childCount > 0 || autofillPinned.childCount > 0)
        autofill.visibility = if (filling) VISIBLE else GONE
        // A fresh copy or photo takes the place of next-word guesses, as Gboard's does; a word being
        // typed keeps its suggestions.
        // Typing a word passes over the chip: it isn't offered again, and the clipboard keeps it.
        if (idle && !guessing) chipOffer?.let { passed -> chipOffer = null; ime.clips.used(passed) }
        val offer = if (idle && !noting && !undoing && !filling && guessing) ime.clips.offer else null
        // Unused, it goes once it is too old, while the keyboard stays up.
        removeCallbacks(expire)
        if (offer != null) postDelayed(expire, offer.staleIn(System.currentTimeMillis()).coerceAtLeast(0) + 100)
        slots.visibility = if (idle && !noting && !undoing && !filling && suggesting && offer == null) VISIBLE else GONE
        chip.visibility = if (offer != null) VISIBLE else GONE
        val fresh = offer != chipOffer
        // Set first: a cached thumbnail arrives at once and checks it is still this offer's.
        chipOffer = offer
        if (offer != null && fresh) offer(offer)
    }

    /** The chip for a copy or a photo ([ClipKeeper.offer]): text as it reads, masked when sensitive, or a
     * thumbnail with the clipboard's icon for a copied image and the photo icon for a new photo. */
    private fun offer(offer: ChipOffer) {
        chipIcon.setImageResource(if (offer is ChipOffer.Photo) R.drawable.ic_image else R.drawable.ic_clipboard)
        val text = offer as? ChipOffer.Text
        chipText.visibility = if (text != null) VISIBLE else GONE
        chipImage.visibility = if (text != null) GONE else VISIBLE
        val params = chip.layoutParams
        params.width = if (text != null) px(CHIP_WIDTH) else LayoutParams.WRAP_CONTENT
        chip.layoutParams = params
        when (offer) {
            is ChipOffer.Text -> {
                chipText.text = if (offer.sensitive) "••••••" else offer.text.replace('\n', ' ')
                chip.contentDescription = context.getString(R.string.keyboard_paste_clip, if (offer.sensitive) "" else chipText.text)
            }
            is ChipOffer.Image -> {
                chip.contentDescription = context.getString(R.string.keyboard_paste_image)
                chipImage.setImageDrawable(null)
                ime.clips.thumbnail(offer.clip) { if (chipOffer == offer) chipImage.setImageBitmap(it) }
            }
            is ChipOffer.Photo -> {
                chip.contentDescription = context.getString(R.string.keyboard_add_photo)
                chipImage.setImageDrawable(null)
                ime.clips.thumbnail(offer.photo) { if (chipOffer == offer) chipImage.setImageBitmap(it) }
            }
        }
    }

    override fun onDetachedFromWindow() { cancelGestures(); super.onDetachedFromWindow() }
    fun cancelGestures() { removeCallbacks(hold); removeCallbacks(expire); held = false; mic.isPressed = false }

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
