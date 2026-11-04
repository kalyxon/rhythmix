package com.rhythmix.app.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import com.rhythmix.app.ui.UiMotion

class LibraryArcGuideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val leftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = context.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        alpha = 40
    }

    private val rightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
        color = context.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        alpha = 28
    }

    private val leftPath = Path()
    private val rightPath = Path()
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 4200L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener { animation ->
            val pulse = animation.animatedValue as Float
            leftPaint.alpha = (34f + pulse * 12f).toInt()
            rightPaint.alpha = (24f + pulse * 10f).toInt()
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!UiMotion.isReduced(context) && !animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        rebuildPaths(width, height)
    }

    private fun rebuildPaths(width: Int, height: Int) {
        leftPath.reset()
        rightPath.reset()
        if (width <= 0 || height <= 0) return

        val margin = 10f * density
        val strokeInset = maxOf(leftPaint.strokeWidth, rightPaint.strokeWidth) / 2f
        val bounds = RectF(
            margin + strokeInset,
            margin + strokeInset,
            width - margin - strokeInset,
            height - margin - strokeInset
        )
        if (bounds.width() <= 0f || bounds.height() <= 0f) return

        leftPath.addArc(bounds, 108f, 144f)
        rightPath.addArc(bounds, 288f, 144f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (leftPath.isEmpty || rightPath.isEmpty) return
        canvas.drawPath(leftPath, leftPaint)
        canvas.drawPath(rightPath, rightPaint)
    }

    private fun Context.themeColor(attribute: Int): Int =
        TypedValue().let { value ->
            theme.resolveAttribute(attribute, value, true)
            value.data
        }
}
