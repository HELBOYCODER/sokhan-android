package app.sokhan.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import kotlin.math.pow

/**
 * Nine-bar mic level meter, matching the desktop recording overlay: 4dp bars with
 * 2dp corners whose height is `3 + level^0.7 * 15` dp, smoothed with
 * `previous * 0.7 + target * 0.3`.
 */
class WaveView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barWidthDp = 4f
    private val gapDp = 3f
    private val minHeightDp = 3f
    private val maxHeightDp = 18f

    /** Symmetric envelope so the meter reads as one voice, not nine random bars. */
    private val weights = floatArrayOf(0.30f, 0.50f, 0.70f, 0.85f, 1.00f, 0.85f, 0.70f, 0.50f, 0.30f)
    private var smoothed = 0f

    var barColor: Int = 0xFF5EEAD4.toInt()
        set(value) {
            field = value
            invalidate()
        }

    init {
        paint.style = Paint.Style.FILL
    }

    fun setLevel(target: Float) {
        smoothed = smoothed * 0.7f + target.coerceIn(0f, 1f) * 0.3f
        invalidate()
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (weights.size * dp(barWidthDp) + (weights.size - 1) * dp(gapDp)).toInt()
        val h = dp(maxHeightDp).toInt()
        setMeasuredDimension(
            resolveSizeAndState(w, widthMeasureSpec, 0),
            resolveSizeAndState(h, heightMeasureSpec, 0)
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
        val total = weights.size * bar + (weights.size - 1) * gap
        var x = (width - total) / 2f
        val midY = height / 2f
        val rect = RectF()
        for (w in weights) {
            val v = (smoothed * w).coerceIn(0f, 1f)
            val h = min + v.pow(0.7f) * (max - min)
            rect.set(x, midY - h / 2f, x + bar, midY + h / 2f)
            canvas.drawRoundRect(rect, radius, radius, paint)
            x += bar + gap
        }
    }
}
