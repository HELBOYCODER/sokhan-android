package app.sokhan.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.Choreographer
import android.view.View
import kotlin.math.pow

/**
 * Nine-bar spectrum meter, matching the desktop recording overlay: 4 dp bars with
 * 2 dp corners, height `3 + level^0.7 * 15` dp, each level eased with
 * `previous * 0.7 + target * 0.3` (RecordingOverlay.tsx) once per frame, so the
 * display stays smooth even though audio only arrives ten times a second.
 */
class WaveView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barWidthDp = 4f
    private val gapDp = 3f
    private val minHeightDp = 3f
    private val maxHeightDp = 18f

    private val targets = FloatArray(AudioBands.BIN_COUNT)
    private val current = FloatArray(AudioBands.BIN_COUNT)
    private var running = false

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            var moving = false
            for (i in current.indices) {
                val next = current[i] * 0.7f + targets[i] * 0.3f
                if (kotlin.math.abs(next - current[i]) > 0.001f) moving = true
                current[i] = next
            }
            if (moving) invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        paint.style = Paint.Style.FILL
    }

    var barColor: Int = 0xFF5EEAD4.toInt()
        set(value) {
            field = value
            invalidate()
        }

    fun setBands(bands: FloatArray) {
        for (i in targets.indices) targets[i] = bands.getOrElse(i) { 0f }
        if (!running) start()
    }

    fun start() {
        if (running) return
        running = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        targets.fill(0f)
        current.fill(0f)
        invalidate()
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (AudioBands.BIN_COUNT * dp(barWidthDp) +
            (AudioBands.BIN_COUNT - 1) * dp(gapDp)).toInt()
        setMeasuredDimension(
            resolveSizeAndState(w, widthMeasureSpec, 0),
            resolveSizeAndState(dp(maxHeightDp).toInt(), heightMeasureSpec, 0)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = barColor
        val bar = dp(barWidthDp)
        val gap = dp(gapDp)
        val min = dp(minHeightDp)
        val max = dp(maxHeightDp)
        val radius = bar / 2f
        val total = current.size * bar + (current.size - 1) * gap
        var x = (width - total) / 2f
        val midY = height / 2f
        val rect = RectF()
        for (v in current) {
            val h = min + v.coerceIn(0f, 1f).pow(0.7f) * (max - min)
            rect.set(x, midY - h / 2f, x + bar, midY + h / 2f)
            canvas.drawRoundRect(rect, radius, radius, paint)
            x += bar + gap
        }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
