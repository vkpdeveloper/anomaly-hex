package ly.anoma.hex.keyboard

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
        /** The chip's widest: it fits its text up to this, so a short code leaves no gap. */
        const val CHIP_WIDTH = 143f
        /** The copy's chip at its widest beside its pieces, and a piece's icon and longest text. */
        const val PIECE_CHIP_WIDTH = 104f
        /** The chip's padding and icon, which its text's width leaves room for. */
        const val CHIP_CHROME = 12f + 14f + 20.2f + 8.2f
        const val PIECE_ICON = 16f
        const val PIECE_TEXT_WIDTH = 150f
        const val EMOJI = 21f
        const val EMOJI_SLOT = 46f
        /** A found emoji's size and cell, in dp. */
        const val FOUND = 26f
        const val FOUND_CELL = 48f
        const val NOTE_MS = 3_000L
        const val HOLD_MS = 350L
    }
    private val density = resources.displayMetrics.density
    private fun px(value: Float) = (value * density).roundToInt()
    private fun color(id: Int) = context.getColor(id)
    private val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val toolbarHeight = px(if (landscape) KeyboardMetrics.TOOLBAR_LANDSCAPE else KeyboardMetrics.TOOLBAR)
    private val shotHeight = toolbarHeight - px(6f)
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
    private val offered = arrayOfNulls<Suggestion>(5)
    /** What each slot's description was made for, so a key that leaves a slot as it was does no work on it. */
    private val describedKinds = arrayOfNulls<Suggestion.Kind>(5)
    private val fitPaint = TextPaint()
    private val clip = Rect()
    private var fitted = 0f
    /** The strip holds only next-word guesses, which a fresh copy or photo replaces. */
    private var guessing = false
    /** A word is being typed or the caret is in one: its suggestions take the autofill chips' place. */
    private var typingWord = false
    /** The slot whose word a long press offered to forget, or -1. */
    private var forgetting = -1

    // The autofill service's chips, which it draws and fills the field from: the rest scroll, the pinned
    // stay at the end by the mic.
    private val autofill = LinearLayout(context)
    private val autofillRest = LinearLayout(context)
    private val autofillScroll = HorizontalScrollView(context)
    private val autofillPinned = LinearLayout(context)

    /** The copy's chip, then its pieces ([ClipPieces]), scrolling if they don't fit. */
    private val clipScroll = HorizontalScrollView(context)
    private val clipRow = LinearLayout(context)
    private val chip = LinearLayout(context)
    private val chipIcon = ImageView(context)
    private val chipText = TextView(context)
    /** A copied image or new screenshot, shown whole at the strip's height in place of the chip. */
    private val shot = ImageView(context)
    private var chipOffer: ChipOffer? = null
    private val noteText = TextView(context)
    /** The selection's Cut and Copy, or a chip for what was just typed ([StripAction]). */
    private val actionScroll = HorizontalScrollView(context)
    private val actionRow = LinearLayout(context)
    private var actions = emptyList<StripAction>()
    private val undo = LinearLayout(context)
    private val undoText = TextView(context)
    private var undoOffer: UndoOffer? = null
    // The emoji a colon search found ([KeyboardEngine.pickFound]), in the strip's place.
    private val foundScroll = HorizontalScrollView(context)
    private val foundRow = LinearLayout(context)
    private var found = emptyList<String>()
    private val well = FrameLayout(context)
    private val scope = DotScopeView(context)
    private val wellText = TextView(context)

    // The mic's looks are made once: each dictation turns the button over twice.
    private val micIdle by lazy { pressable(color(R.color.kb_key), true) }
    private val micListening by lazy { pressable(color(R.color.kb_enter), true) }
    private val micInkIdle = ColorStateList.valueOf(color(R.color.kb_ink))
    private val micInkListening = ColorStateList.valueOf(color(R.color.kb_enter_ink))
    private val micInkSending = ColorStateList.valueOf(color(R.color.kb_off))
    private var phase = DictationPhase.Idle
    private var note: String? = null
    private val clearNote = Runnable { note = null; update() }
    private val expire = Runnable { update() }

    // The mic: a tap starts and a second tap finishes; a hold of 350 ms is push to talk, finishing on lift.
    private var held = false
    private val hold = Runnable { if (mic.isPressed && phase == DictationPhase.Idle) held = ime.dictation.start() }

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
                    when {
                        slot.kind == Suggestion.Kind.Emoji -> ime.engine.pickEmoji(slot.text)
                        forgetting == i -> { forgetting = -1; ime.engine.forget(slot.text) }
                        else -> ime.engine.pickSuggestion(slot.text)
                    }
                }
                // A long press offers to forget the word (the slot reads "Forget word"; a tap does it, the
                // next suggestions undo the offer). What you typed is yours to keep, and emoji aren't words.
                setOnLongClickListener {
                    val slot = offered[i]
                    if (slot == null || slot.kind == Suggestion.Kind.Emoji || slot.kind == Suggestion.Kind.Typed || slot.kind == Suggestion.Kind.Phrase)
                        return@setOnLongClickListener false
                    forgetting = i; describedKinds[i] = null
                    this@Toolbar.words[i].text = context.getString(R.string.keyboard_forget, slot.text)
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    fitWords()
                    true
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
            addView(autofillScroll.apply {
                isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER
                // Chips that fit sit in the middle, as the clipboard chip does; more scroll from the start.
                isFillViewport = true
                addView(autofillRest.apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER },
                    LayoutParams(-2, -1))
                setOnScrollChangeListener { _, _, _, _, _ -> clipChips() }
                // A new chip or a new width moves the chips without a scroll.
                autofillRest.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clipChips() }
            }, LinearLayout.LayoutParams(0, -1, 1f))
            addView(autofillPinned.apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL },
                LinearLayout.LayoutParams(-2, -1))
        }
        addView(autofill, LayoutParams(-1, -1).apply { marginStart = px(46f); marginEnd = px(48f) })

        chip.apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pressable(color(R.color.kb_key), false, pill / 2f)
            setPadding(px(12f), 0, px(14f), 0); isFocusable = true
            chipIcon.setImageResource(R.drawable.ic_clipboard); chipIcon.imageTintList = ColorStateList.valueOf(color(R.color.kb_ink))
            addView(chipIcon, LinearLayout.LayoutParams(px(20.2f), px(20.2f)).apply { marginEnd = px(8.2f) })
            chipText.apply {
                text(this, 16.5f); useSans(500); setTextColor(color(R.color.kb_ink)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            addView(chipText, LinearLayout.LayoutParams(-2, -2))
            setOnClickListener { useOffer() }
        }
        clipRow.orientation = LinearLayout.HORIZONTAL; clipRow.gravity = Gravity.CENTER_VERTICAL
        clipRow.addView(chip, LinearLayout.LayoutParams(-2, pill))
        clipScroll.apply { isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; addView(clipRow, LayoutParams(-2, -1)) }
        addView(clipScroll, LayoutParams(-2, -1, Gravity.CENTER).apply { marginStart = px(46f); marginEnd = px(48f) })
        shot.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; isFocusable = true
            background = GradientDrawable().apply { setColor(color(R.color.kb_function)); cornerRadius = px(6f).toFloat() }
            foreground = GradientDrawable().apply {
                setStroke(px(1f), color(R.color.kb_key_pressed)); cornerRadius = px(6f).toFloat()
            }
            setOnClickListener { useOffer() }
        }
        addView(shot, LayoutParams(shotHeight * 9 / 16, shotHeight, Gravity.CENTER))

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

        foundScroll.apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER
            addView(foundRow.apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }, LayoutParams(-2, -1))
        }
        addView(foundScroll, LayoutParams(-1, -1).apply { marginStart = px(46f); marginEnd = px(48f) })

        actionScroll.apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; isFillViewport = true
            addView(actionRow.apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }, LayoutParams(-2, -1))
        }
        addView(actionScroll, LayoutParams(-1, -1).apply { marginStart = px(46f); marginEnd = px(48f) })

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
        val shown = offered
        shown.fill(null)
        val emoji = list.filter { it.kind == Suggestion.Kind.Emoji }.take(2)
        val words = list.filter { it.kind != Suggestion.Kind.Emoji }
        // A phrase takes the middle slot at twice its width, the third slot's room, so it is shown whole.
        val phrase = words.firstOrNull { it.kind == Suggestion.Kind.Phrase }
        if (phrase != null) {
            shown[1] = phrase; shown[0] = words.firstOrNull { it !== phrase }
        } else if (emoji.isNotEmpty() || words.any { it.kind == Suggestion.Kind.Typed }) {
            words.take(if (emoji.isEmpty()) 3 else 2).forEachIndexed { i, slot -> shown[i] = slot }
        } else { shown[1] = words.getOrNull(0); shown[0] = words.getOrNull(1); shown[2] = words.getOrNull(2) }
        emoji.forEachIndexed { i, slot -> shown[3 + i] = slot }
        forgetting = -1
        guessing = words.all { it.kind == Suggestion.Kind.Next || it.kind == Suggestion.Kind.Phrase }
        typingWord = wordInProgress(words)
        val emojiShown = emoji.isNotEmpty()
        slotViews[2].visibility = if (emojiShown || phrase != null) GONE else VISIBLE
        dividers[1].visibility = slotViews[2].visibility
        (slotViews[1].layoutParams as LinearLayout.LayoutParams).let { p ->
            val weight = if (phrase != null) 2f else 1f
            if (p.weight != weight) { p.weight = weight; slotViews[1].layoutParams = p }
        }
        dividers[2].visibility = if (emojiShown) VISIBLE else GONE
        slotViews[3].visibility = dividers[2].visibility
        dividers[3].visibility = if (emoji.size > 1) VISIBLE else GONE
        slotViews[4].visibility = dividers[3].visibility
        for (i in 0..4) {
            val slot = shown[i]
            val s = slot?.text?.let { if (slot.kind == Suggestion.Kind.Emoji) ime.emojiTones.tone(it) else it }.orEmpty()
            // A new layout is made for every setText, even of the same words, and most keys leave most
            // slots as they were (empty emoji slots above all).
            val view = this.words[i]
            val changed = !view.text.contentEquals(s)
            if (changed) view.text = s
            // The word space will put in gets Gboard's heavier weight.
            if (i < 3) view.useSans(if (slot?.kind == Suggestion.Kind.Fix) 600 else 400)
            if (changed || describedKinds[i] != slot?.kind) {
                describedKinds[i] = slot?.kind
                slotViews[i].contentDescription = when {
                    s.isEmpty() -> null
                    slot?.kind == Suggestion.Kind.Typed -> context.getString(R.string.keyboard_keep, s)
                    slot?.kind == Suggestion.Kind.Fix -> context.getString(R.string.keyboard_correction, s)
                    else -> context.getString(R.string.keyboard_suggest, s)
                }
                slotViews[i].isEnabled = s.isNotEmpty()
            }
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
            fitPaint.set(view.paint); fitPaint.textSize = px(SUGGESTION).toFloat()
            val wide = fitPaint.measureText(view.text, 0, view.text.length)
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
            // Keep the width Android measured the service's chip at: "wrap content" inside the scroll
            // would measure it at nothing, since the chip's own view can't size itself.
            val width = chip.layoutParams?.width?.takeIf { it > 0 } ?: LinearLayout.LayoutParams.WRAP_CONTENT
            row.addView(chip, LinearLayout.LayoutParams(width, pill).apply { marginStart = px(3f); marginEnd = px(3f) })
        }
        update()
    }

    /** A service's chip draws on its own surface, which the scroll doesn't clip: each is cut to the part
     * inside the strip, so none covers the mic or the pinned chips. */
    private fun clipChips() {
        val shown = autofillScroll.width
        for (i in 0 until autofillRest.childCount) {
            val chip = autofillRest.getChildAt(i)
            val surface = (chip as? ViewGroup)?.getChildAt(0) ?: continue
            val left = chip.left - autofillScroll.scrollX
            val from = (-left).coerceIn(0, chip.width)
            val to = (shown - left).coerceIn(from, chip.width)
            clip.set(from, 0, to, chip.height)
            surface.clipBounds = clip
        }
    }

    fun dictation(phase: DictationPhase) {
        this.phase = phase
        val listening = phase == DictationPhase.Listening
        micFace.background = if (listening) micListening else micIdle
        micIcon.imageTintList = when (phase) {
            DictationPhase.Listening -> micInkListening
            DictationPhase.Sending -> micInkSending
            DictationPhase.Idle -> micInkIdle
        }
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
        undoText.setText(when (offer) {
            UndoOffer.Fixes -> R.string.keyboard_undo_fixes
            else -> R.string.keyboard_undo_dictation
        })
        // A new offer is newer than any note, which would hide it.
        if (offer != null) { removeCallbacks(clearNote); note = null }
        update()
    }

    fun actions(list: List<StripAction>) {
        if (list == actions) { update(); return }
        actions = list
        actionRow.removeAllViews()
        for (action in actions) {
            val label = when (action) {
                StripAction.Cut -> context.getString(R.string.keyboard_cut)
                StripAction.Copy -> context.getString(R.string.keyboard_copy)
                is StripAction.Insert -> action.label
            }
            val view = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true
                background = pressable(color(R.color.kb_key), false, pill / 2f)
                setPadding(px(14f), 0, px(14f), 0)
                addView(TextView(context).apply {
                    text(this, 15f); useSans(500); setTextColor(color(R.color.kb_ink)); maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END; text = label
                    maxWidth = px(PIECE_TEXT_WIDTH)
                })
                contentDescription = label
                setOnClickListener { ime.engine.act(action) }
            }
            actionRow.addView(view, LinearLayout.LayoutParams(-2, pill).apply { marginStart = px(3f); marginEnd = px(3f) })
        }
        update()
    }

    /** The emoji a search found, or none to give the strip back. */
    fun found(emoji: List<String>) {
        if (emoji == found) { update(); return }
        found = emoji
        foundRow.removeAllViews()
        for (e in emoji) foundRow.addView(TextView(context).apply {
            text(this, FOUND); gravity = Gravity.CENTER; text = ime.emojiTones.tone(e); contentDescription = text; isFocusable = true
            background = pressable(android.graphics.Color.TRANSPARENT, false, px(8f).toFloat())
            setOnClickListener { ime.engine.pickFound(e) }
        }, LinearLayout.LayoutParams(px(FOUND_CELL), -1))
        foundScroll.scrollTo(0, 0)
        update()
    }

    /** A short line in the toolbar's middle, such as why dictation couldn't start. */
    fun note(text: String) {
        note = text; removeCallbacks(clearNote); postDelayed(clearNote, NOTE_MS); update()
    }

    fun update() {
        val idle = phase == DictationPhase.Idle
        val suggesting = offered.any { it != null }
        val finding = idle && found.isNotEmpty()
        foundScroll.visibility = if (finding) VISIBLE else GONE
        val noting = idle && !finding && note != null
        well.visibility = if (idle) GONE else VISIBLE
        cancel.visibility = if (idle) GONE else VISIBLE
        grid.visibility = if (idle) VISIBLE else GONE
        noteText.visibility = if (noting) VISIBLE else GONE
        if (!noteText.text.contentEquals(note.orEmpty())) noteText.text = note
        val undoing = idle && !finding && !noting && undoOffer != null
        undo.visibility = if (undoing) VISIBLE else GONE
        val acting = idle && !finding && !noting && !undoing && actions.isNotEmpty()
        actionScroll.visibility = if (acting) VISIBLE else GONE
        // The field's own saved passwords and codes come before a copy or next-word guesses.
        val filling = idle && !finding && !noting && !undoing && !acting && !typingWord &&
            (autofillRest.childCount > 0 || autofillPinned.childCount > 0)
        autofill.visibility = if (filling) VISIBLE else GONE
        // A fresh copy or photo takes the place of next-word guesses, as Gboard's does; a word being
        // typed keeps its suggestions.
        // Typing a word passes over the chip: it isn't offered again, and the clipboard keeps it.
        if (idle && !guessing) chipOffer?.let { passed -> chipOffer = null; ime.clips.used(passed) }
        val offer = if (idle && !finding && !noting && !undoing && !acting && !filling && guessing) ime.clips.offer else null
        // Unused, it goes once it is too old, while the keyboard stays up.
        removeCallbacks(expire)
        if (offer != null) postDelayed(expire, offer.staleIn(System.currentTimeMillis()).coerceAtLeast(0) + 100)
        slots.visibility = if (idle && !finding && !noting && !undoing && !acting && !filling && suggesting && offer == null) VISIBLE else GONE
        clipScroll.visibility = if (offer is ChipOffer.Text || offer is ChipOffer.Code) VISIBLE else GONE
        shot.visibility = if (offer is ChipOffer.Image || offer is ChipOffer.Photo) VISIBLE else GONE
        val fresh = offer != chipOffer
        // Set first: a cached thumbnail arrives at once and checks it is still this offer's.
        chipOffer = offer
        if (offer != null && fresh) offer(offer)
    }

    /** The chip for a copy, as it reads and masked when sensitive, a texted code, or the image itself for
     * a copied picture or a new screenshot ([ClipKeeper.offer]). */
    private fun offer(offer: ChipOffer) {
        when (offer) {
            is ChipOffer.Text -> {
                val link = if (offer.sensitive) null else Links.only(offer.text)
                chipText.text = when {
                    offer.sensitive -> "••••••"
                    link != null -> Links.display(link)
                    else -> offer.text.replace('\n', ' ')
                }
                chip.contentDescription = context.getString(R.string.keyboard_paste_clip, if (offer.sensitive) "" else chipText.text)
                val kind = offer.split?.kind ?: if (link != null) ClipPiece.Kind.Link else null
                icon(chipIcon, kind?.icon ?: R.drawable.ic_clipboard, link)
                pieces(offer)
            }
            is ChipOffer.Code -> {
                chipText.text = offer.code
                chip.contentDescription = context.getString(R.string.keyboard_paste_code, offer.code.toList().joinToString(" "))
                icon(chipIcon, R.drawable.ic_sms, null)
                pieces(offer)
            }
            is ChipOffer.Image -> {
                shot.contentDescription = context.getString(R.string.keyboard_paste_image)
                shot.setImageDrawable(null)
                ime.clips.thumbnail(offer.clip) { if (chipOffer == offer) show(it) }
            }
            is ChipOffer.Photo -> {
                shot.contentDescription = context.getString(R.string.keyboard_add_photo)
                shot.setImageDrawable(null)
                ime.clips.thumbnail(offer.photo) { if (chipOffer == offer) show(it) }
            }
        }
    }

    /** The whole image at the strip's height; one wider than the text chip is cropped to its width. */
    private fun show(bitmap: Bitmap?) {
        shot.setImageBitmap(bitmap)
        if (bitmap == null || bitmap.height == 0) return
        val params = shot.layoutParams
        params.width = (shotHeight * bitmap.width / bitmap.height).coerceIn(px(16f), px(CHIP_WIDTH))
        shot.layoutParams = params
    }

    /** The copy's pieces beside its chip, each pasting just that part. The chip's text narrows to make room. */
    private fun pieces(offer: ChipOffer) {
        while (clipRow.childCount > 1) clipRow.removeViewAt(1)
        val pieces = (offer as? ChipOffer.Text)?.split?.pieces.orEmpty()
        chipText.maxWidth = px((if (pieces.isEmpty()) CHIP_WIDTH else PIECE_CHIP_WIDTH) - CHIP_CHROME)
        for (piece in pieces) {
            val icon = ImageView(context)
            val view = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true
                background = pressable(color(R.color.kb_key), false, pill / 2f)
                setPadding(px(10f), 0, px(12f), 0)
                contentDescription = context.getString(R.string.keyboard_paste_clip, piece.text)
                addView(icon, LinearLayout.LayoutParams(px(PIECE_ICON), px(PIECE_ICON)).apply { marginEnd = px(6f) })
                addView(TextView(context).apply {
                    text(this, 15f); useSans(500); setTextColor(color(R.color.kb_ink)); maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END; maxWidth = px(PIECE_TEXT_WIDTH); text = piece.text
                })
                setOnClickListener {
                    if (chipOffer != offer) return@setOnClickListener
                    ime.engine.paste(piece.text); ime.clips.used(offer)
                }
            }
            icon(icon, piece.kind.icon, piece.text.takeIf { piece.kind == ClipPiece.Kind.Link })
            clipRow.addView(view, LinearLayout.LayoutParams(-2, pill).apply { marginStart = px(6f) })
        }
    }

    /** A kind's icon, in the keys' ink; for a [link], its site's own icon once it arrives, small and in colour. */
    private fun icon(view: ImageView, drawable: Int, link: String?) {
        view.setImageResource(drawable)
        view.imageTintList = ColorStateList.valueOf(color(R.color.kb_ink))
        if (link == null) return
        val offer = chipOffer
        ime.clips.favicons.get(link) { bitmap ->
            if (chipOffer !== offer) return@get
            view.imageTintList = null
            view.setImageBitmap(bitmap)
        }
    }

    private fun useOffer() {
        val offer = chipOffer ?: return
        when (offer) {
            is ChipOffer.Text -> ime.engine.paste(offer.text)
            is ChipOffer.Code -> ime.engine.paste(offer.code)
            is ChipOffer.Image -> ime.clips.insert(offer.clip)
            is ChipOffer.Photo -> ime.clips.insert(offer.photo)
        }
        ime.clips.used(offer)
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
