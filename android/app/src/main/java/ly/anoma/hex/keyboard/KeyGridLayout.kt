package ly.anoma.hex.keyboard

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/** Keys on a grid of cells and rows where a key may span rows, as the number pad's operator list
 * does. Like [KeyRowLayout], each keycap is its cells less half the gap on each side, its touch
 * target reaches the middle of the gaps around it and the edges, and its padding places the keycap. */
internal class KeyGridLayout(context: Context, private val cells: Float, private val rows: Int, private val side: Float,
                             private val gap: Float, private val insetTop: Int, private val insetBottom: Int) : ViewGroup(context) {
    private class Span(val row: Int, val rowSpan: Int, val start: Float, val width: Float) : LayoutParams(MATCH_PARENT, MATCH_PARENT)

    fun add(view: View, row: Int, start: Float, width: Float, rowSpan: Int = 1) {
        addView(view, Span(row, rowSpan, start, width)); edgesWidth = -1
    }

    private fun edge(at: Float, cell: Float, width: Int) = when {
        at <= 0f -> 0
        at >= cells -> width
        else -> (side + at * cell).roundToInt()
    }

    // Each key's touch target, worked out once per width and shared by measuring and laying out.
    private var edgesWidth = -1
    private var targetLeft = IntArray(0)
    private var targetRight = IntArray(0)

    private fun edges(width: Int) {
        if (width == edgesWidth && targetLeft.size == childCount) return
        edgesWidth = width
        val cell = (width - 2 * side) / cells
        targetLeft = IntArray(childCount); targetRight = IntArray(childCount)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val span = child.layoutParams as Span
            val left = edge(span.start, cell, width); val right = edge(span.start + span.width, cell, width)
            val capLeft = (side + span.start * cell + gap / 2).roundToInt()
            val capRight = (side + (span.start + span.width) * cell - gap / 2).roundToInt()
            targetLeft[i] = left; targetRight[i] = right
            child.setPadding(capLeft - left, insetTop, right - capRight, insetBottom)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        edges(width)
        val row = height / rows
        for (i in 0 until childCount) {
            val span = getChildAt(i).layoutParams as Span
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(targetRight[i] - targetLeft[i], MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(row * span.rowSpan, MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        edges(r - l)
        val row = (b - t) / rows
        for (i in 0 until childCount) {
            val span = getChildAt(i).layoutParams as Span
            getChildAt(i).layout(targetLeft[i], span.row * row, targetRight[i], (span.row + span.rowSpan) * row)
        }
    }
}
