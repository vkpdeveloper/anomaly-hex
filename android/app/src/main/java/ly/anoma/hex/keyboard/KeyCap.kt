package ly.anoma.hex.keyboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioManager
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import ly.anoma.hex.R
import ly.anoma.hex.useSans
import kotlin.math.roundToInt

/** A keycap's colours: Gboard's letter keys, its darker function keys, and the blue enter key. */
internal enum class KeyFill(val fill: Int, val pressed: Int, val ink: Int) {
    Letter(R.color.kb_key, R.color.kb_key_pressed, R.color.kb_ink),
    Function(R.color.kb_function, R.color.kb_function_pressed, R.color.kb_ink),
    Enter(R.color.kb_enter, R.color.kb_enter_pressed, R.color.kb_enter_ink),
}

/** One key. The whole view is its touch target; its padding places the drawn keycap (KeyRowLayout sets it). */
internal class KeyCap(context: Context, fill: KeyFill, description: String, pill: Boolean, private val haptics: () -> Boolean,
                      click: () -> Unit) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    var label: TextView? = null
    val face = FrameLayout(context)
    /** The system's key click this key plays as it goes down, when the system's Touch sounds are on. */
    var sound = AudioManager.FX_KEYPRESS_STANDARD
    /** TalkBack types a text entry key as the finger lifts from it (its Typing preference, as for Gboard's
     * keys); switches and enter take a double tap. */
    var textEntry = true
    /** What TalkBack names the key's long press, when it has one. */
    var longClickLabel: String? = null
    init {
        contentDescription = description
        // The key click below replaces View's own, which would sound again as performClick runs on lifting.
        isFocusable = true; isSoundEffectsEnabled = false; setOnClickListener { click() }
        val corner = KeyboardMetrics.CORNER * resources.displayMetrics.density
        // A pill's radius is half its height; GradientDrawable clamps a large radius to that.
        fun shape(c: Int) = GradientDrawable().apply { cornerRadius = if (pill) 1e4f else corner; setColor(context.getColor(c)) }
        face.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(fill.pressed))
            addState(intArrayOf(), shape(fill.fill))
        }
        face.isDuplicateParentStateEnabled = true
        face.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(face, LayoutParams(-1, -1))
    }
    // A tap as each key goes down, as on Gboard. The click is the system's own, so it follows Touch
    // sounds (AudioManager.playSoundEffect checks the setting).
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            if (vibrates(context, haptics())) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            context.getSystemService(AudioManager::class.java)?.playSoundEffect(sound)
        }
        return super.dispatchTouchEvent(event)
    }
    // View normally posts its click after UP. Letter gestures commit on UP itself;
    // posting space or shift behind the next letter can reorder fast input.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> isPressed = true
            MotionEvent.ACTION_MOVE -> if (event.x < -slop || event.x > width + slop ||
                event.y < -slop || event.y > height + slop) isPressed = false
            MotionEvent.ACTION_UP -> { val click = isPressed; isPressed = false; if (click) performClick() }
            MotionEvent.ACTION_CANCEL -> isPressed = false
        }
        return true
    }
    // No "Button" after every key, as Gboard's keys say nothing but their names.
    override fun getAccessibilityClassName(): CharSequence = View::class.java.name
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isTextEntryKey = textEntry
        val label = longClickLabel
        if (label != null && isLongClickable) info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, label))
    }
}

/** Hex's Haptics setting and the system's Keyboard vibration both on. */
internal fun vibrates(context: Context, haptics: Boolean) =
    haptics && Settings.System.getInt(context.contentResolver, "keyboard_vibration_enabled", 1) != 0

/** Builds keys with the keyboard's colours, font and haptics setting. Sizes are in dp, not sp:
 * Gboard's keys keep their size whatever the system's font scale. */
internal class KeyCaps(private val context: Context, private val haptics: () -> Boolean) {
    private val density = context.resources.displayMetrics.density
    private fun px(value: Float) = (value * density).roundToInt()

    fun text(view: TextView, dp: Float, weight: Int = 400) = view.apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, dp); useSans(weight)
        includeFontPadding = false
    }

    fun cap(s: String, desc: String, fill: KeyFill = KeyFill.Letter, size: Float = KeyboardMetrics.LETTER,
            pill: Boolean = false, weight: Int = 400, click: () -> Unit): KeyCap {
        val c = KeyCap(context, fill, desc, pill, haptics, click)
        c.label = text(TextView(context), size, weight).apply {
            text = s; gravity = Gravity.CENTER
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setTextColor(context.getColor(fill.ink))
            c.face.addView(this, FrameLayout.LayoutParams(-1, -1))
        }
        return c
    }
    fun icon(res: Int, desc: String, fill: KeyFill, click: () -> Unit, pill: Boolean = false, size: Float = 24f): KeyCap {
        val c = KeyCap(context, fill, desc, pill, haptics, click)
        val image = ImageView(context).apply {
            setImageResource(res); imageTintList = ColorStateList.valueOf(context.getColor(fill.ink))
        }
        c.face.addView(image, FrameLayout.LayoutParams(px(size), px(size), Gravity.CENTER))
        return c
    }
    /** Row 1's digit in the keycap's top-right corner, as Gboard draws it. */
    fun hint(cap: KeyCap, digit: Char) {
        val view = text(TextView(context), KeyboardMetrics.HINT, 500).apply {
            text = digit.toString(); setTextColor(context.getColor(R.color.kb_hint))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        cap.face.addView(view, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = px(0.8f); rightMargin = px(2.2f)
        })
    }
}
