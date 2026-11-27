package ly.anoma.hex.keyboard

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import ly.anoma.hex.R
import ly.anoma.hex.useSans
import kotlin.math.roundToInt

/** What the clipboard's search found, under its bar: the matching text clips in a row that scrolls, pinned first,
 * or a line saying nothing matched. A tap puts the clip in the field and goes back to the keys. */
internal class ClipResults(context: Context, private val paste: (String) -> Unit) : FrameLayout(context) {
    private companion object {
        const val CARD_WIDTH = 160f
        /** Enough of a long clip to fill a card; the rest would only cost layout. */
        const val PREVIEW_CHARS = 200
    }
    private val density = resources.displayMetrics.density
    private fun px(value: Float) = (value * density).roundToInt()
    private fun color(id: Int) = context.getColor(id)
    private val row = LinearLayout(context)
    private val scroll = HorizontalScrollView(context)
    private val none = TextView(context)
    /** The row's height, in pixels: a clipboard card's and its margins. */
    val wanted = px(92f)

    init {
        scroll.isHorizontalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.setPadding(px(8f), px(6f), px(8f), px(6f))
        scroll.clipToPadding = false
        scroll.addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, -1))
        addView(scroll, LayoutParams(-1, -1))
        none.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f); useSans(); setTextColor(color(R.color.kb_quiet))
            gravity = Gravity.CENTER; setText(R.string.keyboard_clipboard_none); visibility = GONE
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addView(none, LayoutParams(-1, -1))
    }

    fun show(found: List<Clip>) {
        row.removeAllViews()
        for (clip in found) {
            val text = (clip.content as? ClipContent.Text)?.text ?: continue
            row.addView(TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f); useSans(); includeFontPadding = false
                setTextColor(color(R.color.kb_ink)); this.text = text.take(PREVIEW_CHARS)
                maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setPadding(px(12f), px(10f), px(12f), px(10f))
                isFocusable = true
                contentDescription = (if (clip.pinned) context.getString(R.string.keyboard_pinned) + ", " else "") + text.take(PREVIEW_CHARS)
                background = StateListDrawable().apply {
                    fun shape(c: Int) = GradientDrawable().apply { cornerRadius = px(12f).toFloat(); setColor(color(c)) }
                    addState(intArrayOf(android.R.attr.state_pressed), shape(R.color.kb_key_pressed))
                    addState(intArrayOf(), shape(R.color.kb_key))
                }
                setOnClickListener { paste(text) }
            }, LinearLayout.LayoutParams(px(CARD_WIDTH), -1).apply { marginEnd = px(6f) })
        }
        scroll.scrollTo(0, 0)
        none.visibility = if (found.isEmpty()) VISIBLE else GONE
    }
}
