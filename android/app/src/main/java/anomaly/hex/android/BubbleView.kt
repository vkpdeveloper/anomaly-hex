package anomaly.hex.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The floating Hex button: a white circle with Hex's hexagon mark. While
 * recording, five bars move with your voice and soft rings pulse out as you
 * talk; while the computer transcribes, it grows leftward into a "Thinking"
 * pill; a check confirms the text went in. Every motion is a spring stepped
 * on the display's frame clock, and the clock stops once nothing moves.
 */
@SuppressLint("ViewConstructor")
class BubbleView(
    context: Context,
    private val onTap: () -> Unit,
    private val onDrag: (dy: Float) -> Unit,
    private val onDragEnd: () -> Unit,
) : View(context), Choreographer.FrameCallback {
    enum class Mode { IDLE, RECORDING, THINKING, MESSAGE, DONE }

    var mode = Mode.IDLE
        private set
    private var label = ""

    private val dp = resources.displayMetrics.density
    private val diameter = 40 * dp
    private val radius = diameter / 2
    /** Room around the circle for its shadow and the pulse rings. */
    private val margin = 12 * dp

    // Springs: presence (pop in and out), press, rest (the mark, only while
    // idle), recording (the bars), expand (circle to pill), done (the check).
    private val presence = Spring(0f, stiffness = 520f, ratio = 0.62f)
    private val press = Spring(1f, stiffness = 900f, ratio = 0.5f)
    private val rest = Spring(1f, stiffness = 420f, ratio = 0.8f)
    private val recording = Spring(0f, stiffness = 420f, ratio = 0.85f)
    private val expand = Spring(0f, stiffness = 380f, ratio = 0.78f)
    private val done = Spring(0f, stiffness = 260f, ratio = 1f)
    private val bars = Array(5) { Spring(0f, stiffness = 700f, ratio = 0.62f) }
    private val springs = listOf(presence, press, rest, recording, expand, done) + bars

    /** Voice loudness, 0 to 1: the latest reading and the smoothed one drawn. */
    private var heard = 0f
    private var loudness = 0f

    /** Pulse rings: age in seconds and strength 0 to 1, oldest first. */
    private val rings = ArrayDeque<FloatArray>()
    private var sinceRing = 0f
    private var clock = 0f

    private var sweep = 0f
    private var lastFrame = 0L
    private var ticking = false
    private var onGone: (() -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(5 * dp, 0f, 1.5f * dp, 0x38000000)
    }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PULSE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PULSE
        style = Paint.Style.STROKE
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        textSize = 14 * dp
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val path = Path()
    private val check = Path()
    private val checkPart = Path()
    private val measure = PathMeasure()
    private val pill = RectF()
    private val matrix = Matrix()
    private var sweepShader: LinearGradient? = null

    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        describe()
    }

    /** Pops in (again, if it was leaving). */
    fun appear() {
        onGone = null
        presence.target = 1f
        tick()
    }

    /** Pops out, then [gone] runs once it has. */
    fun disappear(gone: () -> Unit) {
        if (presence.target == 0f) return
        onGone = gone
        presence.target = 0f
        press.target = 1f
        tick()
    }

    fun show(next: Mode, message: String = "") {
        mode = next
        when (next) {
            Mode.THINKING -> label = "Thinking"
            Mode.MESSAGE -> label = message
            else -> {} // Keep the last label while the pill folds away.
        }
        rest.target = if (next == Mode.IDLE) 1f else 0f
        recording.target = if (next == Mode.RECORDING) 1f else 0f
        expand.target = if (next == Mode.THINKING || next == Mode.MESSAGE) 1f else 0f
        done.target = if (next == Mode.DONE) 1f else 0f
        if (next == Mode.DONE) done.value = 0f
        if (next != Mode.RECORDING) heard = 0f
        describe()
        requestLayout()
        tick()
    }

    /** A peak from 0 to 1, about every 20 ms while recording. */
    fun level(peak: Float) {
        if (mode != Mode.RECORDING) return
        val db = 20 * log10(peak.coerceAtLeast(1e-4f))
        heard = ((db + 52f) / 42f).coerceIn(0f, 1f)
    }

    private fun describe() {
        contentDescription = when (mode) {
            Mode.RECORDING -> "Stop and send to Hex"
            Mode.THINKING -> "Hex is transcribing"
            Mode.MESSAGE -> label
            else -> "Dictate with Hex"
        }
    }

    private fun pillWidth() = maxOf(diameter, text.measureText(label) + 36 * dp)

    private fun bodyWidth() = diameter + (pillWidth() - diameter) * expand.value.coerceAtLeast(0f)

    // The frame clock.

    private fun tick() {
        if (ticking || !isAttachedToWindow) return
        ticking = true
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        val dt = if (lastFrame == 0L) 1 / 60f else ((frameTimeNanos - lastFrame) / 1e9f).coerceIn(0f, 1 / 30f)
        lastFrame = frameTimeNanos
        clock += dt

        val widthBefore = measuredWidth
        var moving = false
        // Small steps keep the stiffest spring stable at any refresh rate.
        val steps = (dt / (1 / 240f)).toInt().coerceAtLeast(1)
        repeat(steps) {
            moving = false
            for (spring in springs) moving = spring.step(dt / steps) or moving
        }
        moving = listen(dt) or moving
        moving = pulse(dt) or moving
        if (mode == Mode.THINKING) {
            sweep = (sweep + dt / 1.1f) % 1f
            moving = true
        }

        if (presence.target == 0f && presence.value < 0.02f) {
            presence.value = 0f
            ticking = false
            onGone?.also { onGone = null }?.invoke()
            return
        }
        if ((bodyWidth() + margin * 2).toInt() + 1 != widthBefore) requestLayout()
        invalidate()
        if (moving) {
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            ticking = false
        }
    }

    /** Smooths loudness and sets each bar's target: they dance while you talk. */
    private fun listen(dt: Float): Boolean {
        val rate = if (heard > loudness) 22f else 7f
        loudness += (heard - loudness) * (1 - exp(-rate * dt))
        if (mode != Mode.RECORDING) {
            for (bar in bars) bar.target = 0f
            return loudness > 0.005f
        }
        for ((i, bar) in bars.withIndex()) {
            val wobble = 0.62f + 0.38f * sin(clock * SPEEDS[i] + i * 1.7f)
            bar.target = loudness * WEIGHTS[i] * wobble
        }
        return true
    }

    /**
     * While recording, rings leave the circle: faint and slow when it's
     * quiet, stronger and quicker while you talk.
     */
    private fun pulse(dt: Float): Boolean {
        // Once recording stops, rings still out finish quickly.
        val pace = if (mode == Mode.RECORDING) 1f else 3f
        val iterator = rings.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            item[0] += dt * pace
            if (item[0] >= RING_LIFE) iterator.remove()
        }
        if (mode == Mode.RECORDING) {
            sinceRing += dt
            val every = 1.25f - 0.7f * loudness
            if (sinceRing >= every && rings.size < 4) {
                sinceRing = 0f
                rings.addLast(floatArrayOf(0f, 0.45f + 0.55f * loudness))
            }
        } else {
            sinceRing = 1f // The first ring comes right after a start.
        }
        return rings.isNotEmpty() || mode == Mode.RECORDING
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        tick()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Choreographer.getInstance().removeFrameCallback(this)
        ticking = false
    }

    // Layout and drawing.

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((bodyWidth() + margin * 2).toInt() + 1, (diameter + margin * 2).toInt() + 1)
    }

    override fun onDraw(canvas: Canvas) {
        val shown = presence.value.coerceIn(0f, 1f)
        if (shown <= 0f) return
        val right = width - margin
        val cx = right - radius
        val cy = margin + radius
        val alpha = (shown * 255).toInt()

        canvas.save()
        val scale = (0.55f + 0.45f * presence.value) * press.value
        canvas.scale(scale, scale, cx, cy)

        drawPulse(canvas, cx, cy, shown)

        pill.set(right - bodyWidth(), margin, right, margin + diameter)
        fill.alpha = alpha
        canvas.drawRoundRect(pill, radius, radius, fill)

        val talk = recording.value.coerceIn(0f, 1f)
        val wide = expand.value.coerceIn(0f, 1f)
        val check = done.value.coerceIn(0f, 1f)
        val markShown = rest.value.coerceIn(0f, 1f)
        if (markShown > 0.01f) drawMark(canvas, cx, cy, markShown * shown, 0.7f + 0.3f * markShown)
        if (talk > 0.01f) drawBars(canvas, cx, cy, talk * shown)
        if (wide > 0.5f) drawLabel(canvas, (wide - 0.5f) * 2 * shown, cy)
        if (check > 0.01f) drawCheck(canvas, cx, cy, check, shown)
        canvas.restore()
    }

    private fun drawPulse(canvas: Canvas, cx: Float, cy: Float, shown: Float) {
        val talk = recording.value.coerceIn(0f, 1f)
        if (talk > 0.01f && loudness > 0.01f) {
            halo.alpha = (120 * loudness * talk * shown).toInt()
            canvas.drawCircle(cx, cy, radius + 1.5f * dp + 4.5f * dp * loudness, halo)
        }
        for (item in rings) {
            val t = item[0] / RING_LIFE
            val eased = 1 - (1 - t).pow(3)
            ring.strokeWidth = (2.4f - 1.4f * t) * dp
            ring.alpha = (220 * item[1] * (1 - t).pow(1.4f) * shown).toInt()
            canvas.drawCircle(cx, cy, radius + (margin - 1.5f * dp) * eased, ring)
        }
    }

    private fun drawMark(canvas: Canvas, cx: Float, cy: Float, opacity: Float, size: Float) {
        val r = 8.5f * dp * size
        val alpha = (opacity * 255).toInt()
        ink.alpha = alpha
        ink.strokeWidth = 1.6f * dp * size
        path.rewind()
        hexagon(path, cx, cy, r)
        hexagon(path, cx, cy, r * 0.6f)
        canvas.drawPath(path, ink)
        solid.alpha = alpha
        solid.strokeWidth = 0.9f * dp * size
        path.rewind()
        hexagon(path, cx, cy, r * 0.3f)
        canvas.drawPath(path, solid)
    }

    private fun drawBars(canvas: Canvas, cx: Float, cy: Float, opacity: Float) {
        val gap = 4.2f * dp
        val thickness = 2.6f * dp
        ink.alpha = (opacity * 255).toInt()
        ink.strokeWidth = thickness
        val tallest = 17 * dp
        for ((i, bar) in bars.withIndex()) {
            val x = cx + (i - 2) * gap * (0.6f + 0.4f * opacity)
            val half = (tallest * bar.value.coerceIn(0f, 1.15f)) / 2
            canvas.drawLine(x, cy - half, x, cy + half, ink)
        }
    }

    private fun drawLabel(canvas: Canvas, opacity: Float, cy: Float) {
        val width = text.measureText(label)
        val x = pill.centerX() - width / 2
        if (label == "Thinking") {
            val shader = sweepShader ?: LinearGradient(
                0f, 0f, width, 0f,
                intArrayOf(INK, SWEEP, INK),
                floatArrayOf(0.3f, 0.5f, 0.7f),
                Shader.TileMode.CLAMP,
            ).also { sweepShader = it }
            matrix.setTranslate(x + (sweep * 2 - 1) * width, 0f)
            shader.setLocalMatrix(matrix)
            text.shader = shader
        } else {
            text.shader = null
        }
        text.alpha = (opacity * 255).toInt()
        val baseline = cy - (text.descent() + text.ascent()) / 2
        canvas.drawText(label, x, baseline, text)
    }

    /** A check that draws itself on, then sits. */
    private fun drawCheck(canvas: Canvas, cx: Float, cy: Float, progress: Float, shown: Float) {
        val r = 8 * dp
        check.rewind()
        check.moveTo(cx - 0.62f * r, cy + 0.02f * r)
        check.lineTo(cx - 0.16f * r, cy + 0.46f * r)
        check.lineTo(cx + 0.66f * r, cy - 0.44f * r)
        measure.setPath(check, false)
        checkPart.rewind()
        measure.getSegment(0f, measure.length * progress, checkPart, true)
        ink.alpha = (min(1f, progress * 3) * shown * 255).toInt()
        ink.strokeWidth = 2.4f * dp
        canvas.drawPath(checkPart, ink)
    }

    // Touch.

    @SuppressLint("ClickableViewAccessibility") // performClick runs on tap.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (presence.target == 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.rawY
                lastY = event.rawY
                dragging = false
                press.target = 0.86f
                tick()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.rawY - downY) > slop) {
                    dragging = true
                    press.target = 1.06f
                    tick()
                }
                if (dragging) {
                    onDrag(event.rawY - lastY)
                    lastY = event.rawY
                }
            }
            MotionEvent.ACTION_UP -> {
                press.target = 1f
                tick()
                if (dragging) onDragEnd() else performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                press.target = 1f
                tick()
                if (dragging) onDragEnd()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap()
        return true
    }

    /** A damped spring; [step] reports whether it is still moving. */
    private class Spring(var value: Float, private val stiffness: Float, ratio: Float) {
        var target = value
        private var velocity = 0f
        private val damping = 2 * ratio * sqrt(stiffness)

        fun step(dt: Float): Boolean {
            velocity += (stiffness * (target - value) - damping * velocity) * dt
            value += velocity * dt
            if (abs(target - value) < 0.0015f && abs(velocity) < 0.02f) {
                value = target
                velocity = 0f
                return false
            }
            return true
        }
    }

    private companion object {
        const val INK = 0xFF0B0C0E.toInt()
        const val SWEEP = 0xFFB4B4B0.toInt()
        /** Mid grey, so the pulse reads over light and dark apps alike. */
        const val PULSE = 0xFFA9A9A5.toInt()
        const val RING_LIFE = 1.1f
        val WEIGHTS = floatArrayOf(0.5f, 0.82f, 1f, 0.82f, 0.5f)
        /** Each bar's own rhythm, so speech looks spoken, not mirrored. */
        val SPEEDS = floatArrayOf(9.1f, 12.7f, 10.3f, 13.9f, 8.3f)

        /** A pointy-top hexagon, like Hex's mark. */
        fun hexagon(path: Path, cx: Float, cy: Float, r: Float) {
            for (corner in 0 until 6) {
                val angle = (corner * 60 - 90) * PI / 180
                val x = cx + r * cos(angle).toFloat()
                val y = cy + r * sin(angle).toFloat()
                if (corner == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
        }
    }
}
