package ly.anoma.hex.keyboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import ly.anoma.hex.R
import ly.anoma.hex.useSans
import kotlin.math.roundToInt

/** Gboard's clipboard, in place of the toolbar and the keys at their height: back, the title and Clear
 * on top, then new photos in a row and the clips in two columns, pinned first. A tap puts a clip in and
 * goes back to the keys; touch and hold shows Pin and Delete on the clip. */
internal class ClipboardPanel(context: Context, private val ime: HexKeyboard, private val close: () -> Unit) : LinearLayout(context) {
    private companion object {
        const val CARD = 80f
        const val PHOTO = 72f
        /** Enough of a long clip to fill a card; the rest would only cost layout. */
        const val PREVIEW_CHARS = 300
        const val NOTE_MS = 3_000L
        /** A card's kind or site icon, and its pieces' row. */
        const val ICON = 14f
        const val PIECES = 24f
        const val PIECE_TEXT_WIDTH = 96f
    }
    private val density = resources.displayMetrics.density
    private fun px(value: Float) = (value * density).roundToInt()
    private fun color(id: Int) = context.getColor(id)
    private val title = TextView(context)
    private val clear = TextView(context)
    private val scroll = ScrollView(context)
    private val content = LinearLayout(context)
    /** The clip showing Pin and Delete, after a touch and hold. */
    private var acting: Long? = null
    private val resetTitle = Runnable { title.setText(R.string.keyboard_clipboard) }

    init {
        orientation = VERTICAL
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(7.5f), 0, px(4f), 0) }
        top.addView(round(R.drawable.ic_arrow_back, context.getString(R.string.keyboard_back), color(R.color.kb_emoji_pill), close),
            LayoutParams(px(34f), px(34f)))
        title.apply {
            text(this, 16f); useSans(500); setTextColor(color(R.color.kb_ink)); setText(R.string.keyboard_clipboard)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(px(12f), 0, px(8f), 0)
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }
        top.addView(title, LayoutParams(0, -2, 1f))
        clear.apply {
            text(this, KeyboardMetrics.LABEL); useSans(500); setTextColor(color(R.color.kb_ink)); setText(R.string.keyboard_clear)
            gravity = Gravity.CENTER; setPadding(px(14f), 0, px(14f), 0); isFocusable = true
            background = pressable(android.graphics.Color.TRANSPARENT, px(17f).toFloat())
            setOnClickListener { acting = null; ime.clips.clearRecent() }
        }
        top.addView(clear, LayoutParams(-2, px(34f)))
        addView(top, LayoutParams(-1, px(42f)))

        content.orientation = VERTICAL
        content.setPadding(px(8f), 0, px(8f), px(8f))
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.addView(content)
        addView(scroll, LayoutParams(-1, 0, 1f))
    }

    fun open() { acting = null; removeCallbacks(resetTitle); resetTitle.run(); show(); scroll.scrollTo(0, 0) }

    /** Builds the list again from the keeper's snapshot; it is short, so every view is made anew. */
    fun show() {
        val clips = ime.clips.clips
        val photos = ime.clips.photos
        if (clips.none { it.id == acting }) acting = null
        content.removeAllViews()
        clear.visibility = if (clips.any { !it.pinned }) VISIBLE else GONE
        if (photos.isNotEmpty()) {
            heading(R.string.keyboard_recent_photos)
            val row = LinearLayout(context)
            for (photo in photos) row.addView(photo(photo), LayoutParams(px(PHOTO), px(PHOTO)).apply { marginEnd = px(6f) })
            content.addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; addView(row)
            }, LayoutParams(-1, -2))
        }
        if (clips.isEmpty()) {
            content.addView(TextView(context).apply {
                text(this, 14f); setTextColor(color(R.color.kb_quiet)); gravity = Gravity.CENTER
                setText(R.string.keyboard_clipboard_empty); setPadding(px(24f), px(32f), px(24f), px(16f))
            }, LayoutParams(-1, -2))
            return
        }
        for ((pinned, group) in clips.groupBy { it.pinned }) {
            heading(if (pinned) R.string.keyboard_pinned else R.string.keyboard_recent)
            for (pair in group.chunked(2)) {
                val row = LinearLayout(context)
                pair.forEachIndexed { i, clip ->
                    row.addView(card(clip), LayoutParams(0, px(CARD), 1f).apply { if (i == 0) marginEnd = px(6f) })
                }
                if (pair.size == 1) row.addView(View(context), LayoutParams(0, px(CARD), 1f))
                content.addView(row, LayoutParams(-1, -2).apply { bottomMargin = px(6f) })
            }
        }
    }

    private fun heading(text: Int) = content.addView(TextView(context).apply {
        text(this, 12.6f); setTextColor(color(R.color.kb_quiet)); setText(text); setPadding(px(4f), px(10f), 0, px(6f))
    }, LayoutParams(-1, -2))

    private fun photo(photo: RecentImage) = FrameLayout(context).apply {
        background = GradientDrawable().apply { setColor(color(R.color.kb_key)); cornerRadius = px(12f).toFloat() }
        clipToOutline = true; isFocusable = true
        contentDescription = context.getString(R.string.keyboard_add_photo)
        val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        addView(image, FrameLayout.LayoutParams(-1, -1))
        addView(pressedShade(), FrameLayout.LayoutParams(-1, -1))
        ime.clips.thumbnail(photo) { image.setImageBitmap(it) }
        setOnClickListener { ime.clips.insert(photo); close() }
    }

    private fun card(clip: Clip): View {
        val card = FrameLayout(context).apply {
            background = pressable(color(R.color.kb_key), px(12f).toFloat())
            clipToOutline = true; isFocusable = true
        }
        val pinnedLabel = if (clip.pinned) context.getString(R.string.keyboard_pinned) + ", " else ""
        when (val c = clip.content) {
            is ClipContent.Text -> {
                card.addView(textCard(clip, c.text), FrameLayout.LayoutParams(-1, -1))
                card.contentDescription = pinnedLabel + c.text.take(PREVIEW_CHARS)
            }
            is ClipContent.Image -> {
                val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                card.addView(image, FrameLayout.LayoutParams(-1, -1))
                card.addView(pressedShade(), FrameLayout.LayoutParams(-1, -1))
                ime.clips.thumbnail(clip) { image.setImageBitmap(it) }
                card.contentDescription = pinnedLabel + context.getString(R.string.keyboard_image)
            }
        }
        if (clip.pinned) card.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_pin); imageTintList = ColorStateList.valueOf(color(R.color.kb_ink))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(R.color.kb_function)) }
            setPadding(px(3f), px(3f), px(3f), px(3f)); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(px(20f), px(20f), Gravity.TOP or Gravity.END).apply { topMargin = px(5f); marginEnd = px(5f) })
        if (acting == clip.id) card.addView(actions(clip), FrameLayout.LayoutParams(-1, -1))

        val pinLabel = context.getString(if (clip.pinned) R.string.keyboard_unpin else R.string.keyboard_pin)
        card.setOnClickListener {
            if (acting != null) { acting = null; show(); return@setOnClickListener }
            when (clip.content) {
                is ClipContent.Text -> ime.engine.paste(clip.content.text)
                is ClipContent.Image -> ime.clips.insert(clip)
            }
            close()
        }
        card.setOnLongClickListener { acting = clip.id; show(); true }
        // TalkBack reaches Pin and Delete from the clip's actions, without the touch and hold.
        ViewCompat.addAccessibilityAction(card, pinLabel) { _, _ -> pin(clip); true }
        ViewCompat.addAccessibilityAction(card, context.getString(R.string.keyboard_delete_clip)) { _, _ -> ime.clips.delete(clip); true }
        return card
    }

    /** A text clip as it reads, a link from its site's name with the site's icon, and below it the
     * phone numbers, dates and the like [ClipKeeper.split] found in it, each pasting just that part. */
    private fun textCard(clip: Clip, text: String): View {
        val link = Links.only(text)
        val split = ime.clips.split(clip)
        val pieces = split?.pieces.orEmpty()
        val kind = split?.kind ?: if (link != null) ClipPiece.Kind.Link else null
        val box = LinearLayout(context).apply { orientation = VERTICAL; setPadding(px(12f), px(10f), px(12f), px(8f)) }
        val line = LinearLayout(context)
        if (kind != null) line.addView(ImageView(context).also { icon(it, kind.icon, link) },
            LayoutParams(px(ICON), px(ICON)).apply { marginEnd = px(6f); topMargin = px(1.5f) })
        line.addView(TextView(context).apply {
            text(this, 14f); setTextColor(color(R.color.kb_ink)); this.text = link?.let(Links::display) ?: text.take(PREVIEW_CHARS)
            maxLines = if (pieces.isEmpty()) 3 else 2; ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(0, -2, 1f))
        box.addView(line, LayoutParams(-1, 0, 1f))
        if (pieces.isNotEmpty()) {
            val row = LinearLayout(context)
            for (piece in pieces) row.addView(piece(piece), LayoutParams(-2, -1).apply { marginEnd = px(4f) })
            box.addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; addView(row, LayoutParams(-2, -1))
            }, LayoutParams(-1, px(PIECES)))
        }
        return box
    }

    private fun piece(piece: ClipPiece) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL; isFocusable = true
        background = pressable(color(R.color.kb_function), px(PIECES / 2).toFloat())
        setPadding(px(8f), 0, px(9f), 0)
        contentDescription = context.getString(R.string.keyboard_paste_clip, piece.text)
        val icon = ImageView(context)
        addView(icon, LayoutParams(px(12f), px(12f)).apply { marginEnd = px(4f) })
        icon(icon, piece.kind.icon, piece.text.takeIf { piece.kind == ClipPiece.Kind.Link })
        addView(TextView(context).apply {
            text(this, 12f); setTextColor(color(R.color.kb_ink)); maxLines = 1; this.text = piece.text
            ellipsize = TextUtils.TruncateAt.END; maxWidth = px(PIECE_TEXT_WIDTH)
        })
        setOnClickListener { ime.engine.paste(piece.text); close() }
    }

    /** A kind's icon in the keys' ink; for a [link], its site's own icon once it arrives, in colour. */
    private fun icon(view: ImageView, drawable: Int, link: String?) {
        view.setImageResource(drawable)
        view.imageTintList = ColorStateList.valueOf(color(R.color.kb_ink))
        if (link == null) return
        view.tag = link
        ime.clips.favicons.get(link) { bitmap ->
            if (view.tag != link) return@get
            view.imageTintList = null
            view.setImageBitmap(bitmap)
        }
    }

    /** Pin and Delete over a clip after a touch and hold. */
    private fun actions(clip: Clip) = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        setBackgroundColor(color(R.color.kb_function))
        isClickable = true
        setOnClickListener { acting = null; show() }
        addView(round(R.drawable.ic_pin, context.getString(if (clip.pinned) R.string.keyboard_unpin else R.string.keyboard_pin),
            color(R.color.kb_key)) { pin(clip) }, LayoutParams(px(40f), px(40f)).apply { marginEnd = px(16f) })
        addView(round(R.drawable.ic_trash, context.getString(R.string.keyboard_delete_clip), color(R.color.kb_key)) {
            acting = null; ime.clips.delete(clip)
        }, LayoutParams(px(40f), px(40f)))
    }
    private fun pin(clip: Clip) {
        acting = null
        ime.clips.pin(clip, !clip.pinned) {
            title.text = context.getString(R.string.keyboard_pinned_full, ClipHistory.MAX_PINNED)
            removeCallbacks(resetTitle); postDelayed(resetTitle, NOTE_MS)
        }
    }

    private fun text(view: TextView, dp: Float) = view.apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, dp); useSans(); includeFontPadding = false
    }
    private fun round(icon: Int, description: String, fill: Int, click: () -> Unit) = FrameLayout(context).apply {
        contentDescription = description; isFocusable = true
        background = pressable(fill, 1e4f)
        setOnClickListener { click() }
        addView(ImageView(context).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(color(R.color.kb_ink)) },
            FrameLayout.LayoutParams(px(22f), px(22f), Gravity.CENTER))
    }
    private fun pressable(fill: Int, radius: Float) = StateListDrawable().apply {
        fun shape(c: Int) = GradientDrawable().apply { cornerRadius = radius; setColor(c) }
        addState(intArrayOf(android.R.attr.state_pressed), shape(color(R.color.kb_key_pressed)))
        addState(intArrayOf(), shape(fill))
    }
    /** A picture covers its card's background, so its press shows as a light shade over it. */
    private fun pressedShade() = View(context).apply {
        isDuplicateParentStateEnabled = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(0x33FFFFFF) })
        }
    }
}
