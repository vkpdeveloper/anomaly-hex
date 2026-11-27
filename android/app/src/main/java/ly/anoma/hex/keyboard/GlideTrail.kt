package ly.anoma.hex.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import ly.anoma.hex.typing.KeyPoint
import kotlin.math.hypot

/** A finger's drag from a letter key, in the rows' coordinates: tracked from touch-down, a glide once it
 * has gone far enough, and the trail it leaves, drawn thick at the finger and thinning to nothing
 * [TRAIL_MS] behind it, so it shrinks away once the finger lifts. Only one drag at a time: another
 * key's touch-down starts over unless a glide is already under way. Keys are told apart by identity. */
internal class GlideTrail(private val ink: Int, density: Float) {
    companion object {
        /** How long a point of the trail stays drawn. */
        private const val TRAIL_MS = 260L
        private const val HEAD_DP = 5f
        private const val TAIL_DP = 1.5f
        /** A drag this many keys long glides once the finger has been down [SETTLE_MS]. */
        const val START_KEYS = .45
        /** What it takes before then, easing down to [START_KEYS]: a quick tap drifts toward the next
         * key as the finger lifts, and a glide that early would swallow the tapped letter (AOSP's
         * gesture detection likewise wants more distance the sooner after touch-down). */
        const val START_KEYS_TAP = 1.5
        const val SETTLE_MS = 250L
        /** While typing (another letter still held, or one lifted within [TYPING_MS]) a drag always
         * takes this much, so a tap that slides on into the next key stays a tap. */
        const val START_KEYS_TYPING = 1.5
        const val TYPING_MS = 350L

        /** How far, in keys, a drag must have gone [elapsed] ms after its touch-down to glide. */
        fun startKeys(elapsed: Long, typing: Boolean): Double {
            if (typing) return START_KEYS_TYPING
            val early = 1 - (elapsed.toDouble() / SETTLE_MS).coerceIn(0.0, 1.0)
            return START_KEYS + (START_KEYS_TAP - START_KEYS) * early
        }
    }
    // Made on first draw, so the touch logic runs without Android's graphics (unit tests).
    private val paint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    }
    private val head = HEAD_DP * density
    private val tail = TAIL_DP * density
    private var xs = FloatArray(256)
    private var ys = FloatArray(256)
    private var times = LongArray(256)
    private var size = 0
    private var owner: Any? = null
    private var startX = 0f
    private var startY = 0f
    private var allowed = false
    private var pitch = 0.0
    private var downTime = 0L
    private var typing = false
    private var lastTap = Long.MIN_VALUE / 2
    /** A glide is under way: its key no longer types, and other keys' touches wait. */
    var active = false
        private set

    /** A letter key went down at [p]. [allowed] while the field and page take glides; [pitch] is a key's width. */
    fun down(key: Any, p: KeyPoint, time: Long, allowed: Boolean, pitch: Double) = down(key, p.x.toFloat(), p.y.toFloat(), time, allowed, pitch)

    // The touch handler goes through the float forms: a point object for every move of every finger would
    // be garbage made on the way to a frame.
    fun down(key: Any, x: Float, y: Float, time: Long, allowed: Boolean, pitch: Double) {
        // A drag still owning the trail here is a letter still held: fast typing rolls over, so the
        // previous key often lifts only after this one went down.
        typing = owner != null || time - lastTap < TYPING_MS
        owner = key; active = false; size = 0
        this.allowed = allowed && pitch > 0
        this.pitch = pitch; downTime = time
        startX = x; startY = y
        add(key, x, y, time)
    }

    /** The finger moved; true when this move makes the drag a glide. */
    fun move(key: Any, p: KeyPoint, time: Long, choosing: Boolean) = move(key, p.x.toFloat(), p.y.toFloat(), time, choosing)

    fun move(key: Any, x: Float, y: Float, time: Long, choosing: Boolean): Boolean {
        if (key !== owner || !allowed) return false
        add(key, x, y, time)
        if (active || choosing || hypot((x - startX).toDouble(), (y - startY).toDouble()) < pitch * startKeys(time - downTime, typing)) return false
        active = true
        return true
    }

    fun add(key: Any, p: KeyPoint, time: Long) = add(key, p.x.toFloat(), p.y.toFloat(), time)

    fun add(key: Any, x: Float, y: Float, time: Long) {
        if (key !== owner || !allowed) return
        if (size == xs.size) { xs = xs.copyOf(size * 2); ys = ys.copyOf(size * 2); times = times.copyOf(size * 2) }
        xs[size] = x; ys[size] = y; times[size] = time; size++
    }

    /** The finger lifted: the glide's whole path, or null when the drag never became one (a tap). */
    fun up(key: Any, time: Long): List<KeyPoint>? {
        // A key that lifts after the next went down was still typed: it counts as typing too.
        if (key !== owner) { if (!active) lastTap = time; return null }
        owner = null
        if (!active) { lastTap = time; size = 0; return null }
        active = false
        return List(size) { KeyPoint(xs[it].toDouble(), ys[it].toDouble()) }
    }

    /** The touch was cancelled: [key]'s drag, or whatever is under way when null. */
    fun cancel(key: Any? = null) {
        if (key != null && key !== owner) return
        owner = null; active = false; size = 0
    }

    /** Draws the trail offset by ([dx], [dy]); true while some of it is still to fade. */
    fun draw(canvas: Canvas, dx: Float, dy: Float, now: Long): Boolean {
        if (size < 2 || (owner != null && !active)) return false
        var first = size - 1
        while (first > 0 && now - times[first - 1] < TRAIL_MS) first--
        if (first >= size - 1 && now - times[size - 1] >= TRAIL_MS) { if (owner == null) size = 0; return false }
        val paint = paint
        for (i in maxOf(first, 1) until size) {
            val fresh = 1f - (now - times[i]).coerceIn(0, TRAIL_MS).toFloat() / TRAIL_MS
            paint.strokeWidth = tail + (head - tail) * fresh
            canvas.drawLine(xs[i - 1] + dx, ys[i - 1] + dy, xs[i] + dx, ys[i] + dy, paint)
        }
        return true
    }
}
