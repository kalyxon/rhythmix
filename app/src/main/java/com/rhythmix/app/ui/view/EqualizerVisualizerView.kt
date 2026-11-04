package com.rhythmix.app.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import com.rhythmix.app.R
import com.rhythmix.app.ui.UiMotion
import kotlin.random.Random

/**
 * Animated sound equalizer visualizer view.
 * Renders 4 bouncing bars in theme accent color when music is playing.
 */
class EqualizerVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rectF = RectF()
    private val barCount = 4
    private val barHeights = FloatArray(barCount) { 0.3f }
    private var animator: ValueAnimator? = null

    var barColor: Int = 0
        set(value) {
            field = value
            paint.color = value
            invalidate()
        }

    var isPlaying: Boolean = false
        set(value) {
            field = value
            if (value && !UiMotion.isReduced(context)) startAnimation() else stopAnimation()
        }

    init {
        val tv = TypedValue()
        context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true)
        barColor = tv.data
        paint.color = barColor
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val gap = w * 0.12f
        val totalGaps = gap * (barCount - 1)
        val barWidth = (w - totalGaps) / barCount
        val minHeight = h * 0.15f
        val cornerRadius = barWidth / 2f

        for (i in 0 until barCount) {
            val left = i * (barWidth + gap)
            val currentH = (h * barHeights[i]).coerceAtLeast(minHeight)
            val top = h - currentH
            val right = left + barWidth
            rectF.set(left, top, right, h)
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)
        }
    }

    private fun startAnimation() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                for (i in 0 until barCount) {
                    val target = Random.nextFloat() * 0.7f + 0.3f
                    barHeights[i] = barHeights[i] * 0.6f + target * 0.4f
                }
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
        for (i in 0 until barCount) {
            barHeights[i] = 0.2f
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isPlaying && !UiMotion.isReduced(context)) startAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }
}
