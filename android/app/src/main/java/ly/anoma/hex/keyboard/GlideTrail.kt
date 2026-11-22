package ly.anoma.hex.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import ly.anoma.hex.typing.KeyPoint
import kotlin.math.hypot

/** A finger's drag from a letter key, in the rows' coordinates: tracked from touch-down, a glide once it
 * has gone far enough, and the trail it leaves, drawn thick at the finger and thinning to nothing
 * [TRAIL_MS] behind it, so it shrinks away once the finger lifts. Only one drag at a time: another
 * key's touch-down starts over unless a glide is already under way. */
internal class GlideTrail(color: Int, density: Float) {
    private companion object {
        /** How long a point of the trail stays drawn. */
        const val TRAIL_MS = 260L
        const val HEAD_DP = 5f
        const val TAIL_DP = 1.5f
        /** A drag this many keys long glides; right after a tapped letter it takes more, so a quick
         * tap that slides on into the next key stays a tap. */
        const val START_KEYS = .45
        const val START_KEYS_TYPING = 1.5
        const val TYPING_MS = 350L
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val head = HEAD_DP * density
    private val tail = TAIL_DP * density
    private var xs = FloatArray(256)
    private var ys = FloatArray(256)
    private var times = LongArray(256)
    private var size = 0
    private var owner: View? = null
    private var startX = 0f
    private var startY = 0f
    private var allowed = false
    private var threshold = 0.0
    private var lastTap = Long.MIN_VALUE / 2
    /** A glide is under way: its key no longer types, and other keys' touches wait. */
    var active = false
        private set

    /** A letter key went down at [p]. [allowed] while the field and page take glides; [pitch] is a key's width. */
    fun down(key: View, p: KeyPoint, time: Long, allowed: Boolean, pitch: Double) {
        owner = key; active = false; size = 0
        this.allowed = allowed && pitch > 0
        threshold = pitch * if (time - lastTap < TYPING_MS) START_KEYS_TYPING else START_KEYS
        startX = p.x.toFloat(); startY = p.y.toFloat()
        add(key, p, time)
    }

    /** The finger moved; true when this move makes the drag a glide. */
    fun move(key: View, p: KeyPoint, time: Long, choosing: Boolean): Boolean {
        if (key !== owner || !allowed) return false
        add(key, p, time)
        if (active || choosing || hypot(p.x - startX, p.y - startY) < threshold) return false
        active = true
        return true
    }

    fun add(key: View, p: KeyPoint, time: Long) {
        if (key !== owner || !allowed) return
        if (size == xs.size) { xs = xs.copyOf(size * 2); ys = ys.copyOf(size * 2); times = times.copyOf(size * 2) }
        xs[size] = p.x.toFloat(); ys[size] = p.y.toFloat(); times[size] = time; size++
    }

    /** The finger lifted: the glide's whole path, or null when the drag never became one (a tap). */
    fun up(key: View, time: Long): List<KeyPoint>? {
        if (key !== owner) return null
        owner = null
        if (!active) { lastTap = time; size = 0; return null }
        active = false
        return List(size) { KeyPoint(xs[it].toDouble(), ys[it].toDouble()) }
    }

    /** The touch was cancelled: [key]'s drag, or whatever is under way when null. */
    fun cancel(key: View? = null) {
        if (key != null && key !== owner) return
        owner = null; active = false; size = 0
    }

    /** Draws the trail offset by ([dx], [dy]); true while some of it is still to fade. */
    fun draw(canvas: Canvas, dx: Float, dy: Float, now: Long): Boolean {
        if (size < 2 || (owner != null && !active)) return false
        var first = size - 1
        while (first > 0 && now - times[first - 1] < TRAIL_MS) first--
        if (first >= size - 1 && now - times[size - 1] >= TRAIL_MS) { if (owner == null) size = 0; return false }
        for (i in maxOf(first, 1) until size) {
            val fresh = 1f - (now - times[i]).coerceIn(0, TRAIL_MS).toFloat() / TRAIL_MS
            paint.strokeWidth = tail + (head - tail) * fresh
            canvas.drawLine(xs[i - 1] + dx, ys[i - 1] + dy, xs[i] + dx, ys[i] + dy, paint)
        }
        return true
    }
}
