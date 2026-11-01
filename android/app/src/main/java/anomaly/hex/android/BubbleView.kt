package anomaly.hex.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.log10

/**
 * The floating Hex button: a white circle with Hex's mark, five level bars
 * while recording, and a pill that grows leftward to read "Thinking" (or a
 * short message) while the computer transcribes.
 */
@SuppressLint("ViewConstructor")
class BubbleView(
    context: Context,
    private val onTap: () -> Unit,
    private val onDrag: (dy: Float) -> Unit,
    private val onDragEnd: () -> Unit,
) : View(context) {
    enum class Mode { IDLE, RECORDING, THINKING, MESSAGE }

    var mode = Mode.IDLE
        private set
    private var message = ""

    private val density = resources.displayMetrics.density
    private val diameter = 48 * density
    private val margin = 8 * density // Room for the shadow.

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(4 * density, 0f, 1.5f * density, 0x40000000)
    }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        textSize = 15 * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val mark = Path().apply {
        // site/public/favicon.svg's H, on a 64-unit grid.
        moveTo(17f, 16f); lineTo(17f, 48f)
        moveTo(47f, 16f); lineTo(47f, 48f)
        moveTo(17f, 32f); lineTo(47f, 32f)
    }
    private val markPath = Path()
    private val pill = RectF()

    /** 0 is the circle, 1 the full pill. */
    private var expand = 0f
    private var expandAnimator: ValueAnimator? = null

    /** Where the Thinking sweep is, 0 to 1, only animated while thinking. */
    private var sweep = 0f
    private var sweepAnimator: ValueAnimator? = null
    private val sweepMatrix = Matrix()
    private var sweepShader: LinearGradient? = null

    private val bars = FloatArray(5)
    private val history = FloatArray(3)

    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        contentDescription = "Dictate with Hex"
    }

    fun show(next: Mode, text: String = "") {
        val wasPill = pillWanted()
        mode = next
        message = text
        if (next != Mode.RECORDING) bars.fill(0f).also { history.fill(0f) }
        if (pillWanted() != wasPill || next == Mode.MESSAGE) animateExpand(if (pillWanted()) 1f else 0f)
        if (next == Mode.THINKING) startSweep() else stopSweep()
        requestLayout()
        invalidate()
    }

    /** A peak from 0 to 1, about every 20 ms while recording. */
    fun level(peak: Float) {
        if (mode != Mode.RECORDING) return
        val db = 20 * log10(peak.coerceAtLeast(1e-4f))
        val loud = ((db + 50f) / 44f).coerceIn(0f, 1f)
        history[2] = history[1]
        history[1] = history[0]
        history[0] = loud
        val targets = floatArrayOf(history[2], history[1], history[0], history[1], history[2])
        for (i in bars.indices) {
            val target = targets[i] * WEIGHTS[i]
            // Rise fast, fall slower, so speech reads as movement, not flicker.
            val rate = if (target > bars[i]) 0.6f else 0.25f
            bars[i] += (target - bars[i]) * rate
        }
        invalidate()
    }

    private fun pillWanted() = mode == Mode.THINKING || mode == Mode.MESSAGE

    private fun pillText() = when (mode) {
        Mode.THINKING -> "Thinking"
        Mode.MESSAGE -> message
        else -> ""
    }

    private fun pillWidth(): Float {
        val text = pillText().ifEmpty { "Thinking" }
        return maxOf(diameter, label.measureText(text) + 40 * density)
    }

    private fun animateExpand(to: Float) {
        expandAnimator?.cancel()
        expandAnimator = ValueAnimator.ofFloat(expand, to).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                expand = it.animatedValue as Float
                requestLayout()
                invalidate()
            }
            start()
        }
    }

    private fun startSweep() {
        if (sweepAnimator != null) return
        sweepAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                sweep = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopSweep() {
        sweepAnimator?.cancel()
        sweepAnimator = null
        label.shader = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        expandAnimator?.cancel()
        stopSweep()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = diameter + (pillWidth() - diameter) * expand + margin * 2
        setMeasuredDimension(width.toInt() + 1, (diameter + margin * 2).toInt() + 1)
    }

    override fun onDraw(canvas: Canvas) {
        val right = width - margin
        val left = right - (diameter + (pillWidth() - diameter) * expand)
        pill.set(left, margin, right, margin + diameter)
        canvas.drawRoundRect(pill, diameter / 2, diameter / 2, fill)
        val centerY = margin + diameter / 2
        when (mode) {
            Mode.IDLE -> drawMark(canvas, right - diameter / 2, centerY)
            Mode.RECORDING -> drawBars(canvas, right - diameter / 2, centerY)
            Mode.THINKING, Mode.MESSAGE -> if (expand > 0.6f) drawLabel(canvas, pill, centerY)
        }
    }

    private fun drawMark(canvas: Canvas, centerX: Float, centerY: Float) {
        val size = 22 * density
        val scale = size / 64f
        markPath.set(mark)
        sweepMatrix.setScale(scale, scale)
        sweepMatrix.postTranslate(centerX - size / 2, centerY - size / 2)
        markPath.transform(sweepMatrix)
        ink.strokeWidth = 6 * scale
        canvas.drawPath(markPath, ink)
    }

    private fun drawBars(canvas: Canvas, centerX: Float, centerY: Float) {
        val gap = 4.5f * density
        ink.strokeWidth = 3 * density
        val minimum = 3 * density
        val maximum = 20 * density
        for (i in bars.indices) {
            val x = centerX + (i - 2) * gap
            val half = (minimum + (maximum - minimum) * bars[i]) / 2
            canvas.drawLine(x, centerY - half, x, centerY + half, ink)
        }
    }

    private fun drawLabel(canvas: Canvas, bounds: RectF, centerY: Float) {
        val text = pillText()
        val textWidth = label.measureText(text)
        val x = bounds.centerX() - textWidth / 2
        if (mode == Mode.THINKING) {
            val shader = sweepShader ?: LinearGradient(
                0f, 0f, textWidth, 0f,
                intArrayOf(INK, SWEEP, INK),
                floatArrayOf(0.3f, 0.5f, 0.7f),
                Shader.TileMode.CLAMP,
            ).also { sweepShader = it }
            sweepMatrix.setTranslate(x + (sweep * 2 - 1) * textWidth, 0f)
            shader.setLocalMatrix(sweepMatrix)
            label.shader = shader
        } else {
            label.shader = null
        }
        val baseline = centerY - (label.descent() + label.ascent()) / 2
        canvas.drawText(text, x, baseline, label)
    }

    @SuppressLint("ClickableViewAccessibility") // performClick runs on tap.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.rawY
                lastY = event.rawY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.rawY - downY) > slop) dragging = true
                if (dragging) {
                    onDrag(event.rawY - lastY)
                    lastY = event.rawY
                }
            }
            MotionEvent.ACTION_UP -> if (dragging) onDragEnd() else performClick()
            MotionEvent.ACTION_CANCEL -> if (dragging) onDragEnd()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap()
        return true
    }

    private companion object {
        const val INK = 0xFF0B0C0E.toInt()
        const val SWEEP = 0xFFB4B4B0.toInt()
        val WEIGHTS = floatArrayOf(0.55f, 0.8f, 1f, 0.8f, 0.55f)
    }
}
