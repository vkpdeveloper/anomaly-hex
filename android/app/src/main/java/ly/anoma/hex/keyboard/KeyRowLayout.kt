package ly.anoma.hex.keyboard

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/** One row of keys on a grid of cells. Each key's keycap spans its cells less half the gap on each
 * side; its touch target runs to the middle of the gaps around it, and the first and last keys'
 * reach the row's ends, so every point of the row types a key. A key's padding is its keycap's
 * place inside its target. Add keys left to right. */
internal class KeyRowLayout(context: Context, private val cells: Float, private val side: Float,
                            private val gap: Float, private val insetTop: Int, private val insetBottom: Int) : ViewGroup(context) {
    private class Span(val start: Float, val width: Float) : LayoutParams(MATCH_PARENT, MATCH_PARENT)

    fun add(view: View, start: Float, width: Float) { addView(view, Span(start, width)); edgesWidth = -1 }

    // Each key's touch target and keycap edges, worked out once per width and used by measuring and
    // laying out alike, not again for every pass of both.
    private var edgesWidth = -1
    private var hitLeft = IntArray(0)
    private var hitRight = IntArray(0)

    private fun edges(width: Int) {
        if (width == edgesWidth && hitLeft.size == childCount) return
        edgesWidth = width
        val cell = (width - 2 * side) / cells
        val n = childCount
        val capLeft = IntArray(n); val capRight = IntArray(n)
        for (i in 0 until n) {
            val span = getChildAt(i).layoutParams as Span
            capLeft[i] = (side + span.start * cell + gap / 2).roundToInt()
            capRight[i] = (side + (span.start + span.width) * cell - gap / 2).roundToInt()
        }
        hitLeft = IntArray(n) { if (it == 0) 0 else (capRight[it - 1] + capLeft[it]) / 2 }
        hitRight = IntArray(n) { if (it == n - 1) width else (capRight[it] + capLeft[it + 1]) / 2 }
        for (i in 0 until n) getChildAt(i).setPadding(capLeft[i] - hitLeft[i], insetTop, hitRight[i] - capRight[i], insetBottom)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        edges(width)
        for (i in 0 until childCount) getChildAt(i).measure(
            MeasureSpec.makeMeasureSpec(hitRight[i] - hitLeft[i], MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        edges(r - l)
        for (i in 0 until childCount) getChildAt(i).layout(hitLeft[i], 0, hitRight[i], b - t)
    }
}
