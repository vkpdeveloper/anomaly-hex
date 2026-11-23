package ly.anoma.hex.keyboard

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ly.anoma.hex.MainActivity
import ly.anoma.hex.R
import ly.anoma.hex.relay.HexClient
import ly.anoma.hex.useSans
import kotlin.math.roundToInt

/** The grid button's menu, as Gboard's: close on top, then a tile for each tool, three to a row. It
 * takes the toolbar's and the keys' place at their height. A tile acts and the menu goes. */
internal class MenuPanel(
    context: Context,
    private val ime: HexKeyboard,
    private val close: () -> Unit,
    private val openClipboard: () -> Unit,
) : LinearLayout(context) {
    private companion object {
        const val COLUMNS = 3
        const val TILE = 76f
        const val TONE = 38f
    }
    private val density = resources.displayMetrics.density
    private fun px(value: Float) = (value * density).roundToInt()
    private fun color(id: Int) = context.getColor(id)
    private val tiles = LinearLayout(context)

    init {
        orientation = VERTICAL
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(7.5f), 0, px(4f), 0) }
        top.addView(FrameLayout(context).apply {
            contentDescription = context.getString(R.string.keyboard_close); isFocusable = true
            background = pressable(color(R.color.kb_emoji_pill), 1e4f)
            setOnClickListener { close() }
            addView(icon(R.drawable.ic_close), FrameLayout.LayoutParams(px(22f), px(22f), Gravity.CENTER))
        }, LayoutParams(px(34f), px(34f)))
        addView(top, LayoutParams(-1, px(42f)))
        tiles.orientation = VERTICAL
        tiles.setPadding(px(8f), px(4f), px(8f), px(8f))
        addView(tiles, LayoutParams(-1, -2))
    }

    /** The tiles as things are now: Fix mistakes only where the fix button works, Rewrite and Reply only
     * with a computer paired and never in a password field, and with them a row of tones to rewrite in. */
    fun open() {
        val tools = buildList {
            add(Triple(R.drawable.ic_clipboard, R.string.keyboard_clipboard) { openClipboard() })
            if (ime.fixShown) add(Triple(R.drawable.ic_spellcheck, R.string.keyboard_fix) { close(); ime.fix() })
            if (ime.rewriteShown) add(Triple(R.drawable.ic_rewrite, R.string.keyboard_rewrite) { close(); ime.rewrite() })
            if (ime.rewriteShown) add(Triple(R.drawable.ic_sms, R.string.keyboard_reply) { close(); ime.reply() })
            add(Triple(R.drawable.ic_settings, R.string.keyboard_settings) {
                close()
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
        }
        tiles.removeAllViews()
        for (row in tools.chunked(COLUMNS)) {
            val line = LinearLayout(context)
            for (i in 0 until COLUMNS) {
                val tool = row.getOrNull(i)
                val params = LayoutParams(0, px(TILE), 1f).apply { if (i < COLUMNS - 1) marginEnd = px(8f) }
                line.addView(if (tool == null) FrameLayout(context) else tile(tool.first, tool.second, tool.third), params)
            }
            tiles.addView(line, LayoutParams(-1, -2).apply { bottomMargin = px(8f) })
        }
        if (ime.rewriteShown) tiles.addView(tones(), LayoutParams(-1, px(TONE)))
    }

    private fun tones() = LinearLayout(context).apply {
        val styles = listOf(HexClient.Style.Shorter to R.string.keyboard_shorter, HexClient.Style.Friendlier to R.string.keyboard_friendlier,
            HexClient.Style.Formal to R.string.keyboard_formal, HexClient.Style.English to R.string.keyboard_english)
        for ((i, tone) in styles.withIndex()) addView(TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f); useSans(500); includeFontPadding = false
            setTextColor(color(R.color.kb_ink)); setText(tone.second); gravity = Gravity.CENTER
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(px(4f), 0, px(4f), 0)
            isFocusable = true; background = pressable(color(R.color.kb_key), 1e4f)
            setOnClickListener { close(); ime.rewrite(tone.first) }
        }, LayoutParams(0, -1, 1f).apply { if (i < styles.size - 1) marginEnd = px(8f) })
    }

    private fun tile(drawable: Int, label: Int, click: () -> Unit) = LinearLayout(context).apply {
        orientation = VERTICAL; gravity = Gravity.CENTER; isFocusable = true
        background = pressable(color(R.color.kb_key), px(16f).toFloat())
        setOnClickListener { click() }
        addView(icon(drawable), LayoutParams(px(24f), px(24f)).apply { bottomMargin = px(8f) })
        addView(TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f); useSans(500); includeFontPadding = false
            setTextColor(color(R.color.kb_ink)); setText(label); gravity = Gravity.CENTER
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(px(6f), 0, px(6f), 0)
        }, LayoutParams(-1, -2))
    }
    private fun icon(id: Int) = ImageView(context).apply {
        setImageResource(id); imageTintList = ColorStateList.valueOf(color(R.color.kb_ink))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun pressable(fill: Int, radius: Float) = StateListDrawable().apply {
        fun shape(c: Int) = GradientDrawable().apply { cornerRadius = radius; setColor(c) }
        addState(intArrayOf(android.R.attr.state_pressed), shape(color(R.color.kb_key_pressed)))
        addState(intArrayOf(), shape(fill))
    }
}
