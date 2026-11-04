package com.rhythmix.app.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import com.rhythmix.app.ui.UiMotion

class ListeningRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private var animationProgress = 0f
    private var isMotionEnabled = !UiMotion.isReduced(context)
    private var isAttached = false
    private var ringShader: SweepGradient? = null
    private var accentColor = Color.WHITE
    private var accentHighlight = Color.WHITE
    private var secondaryColor = Color.WHITE
    private val shaderMatrix = Matrix()
    private val wavePath = Path()
    private val waveformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 9000L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener {
            animationProgress = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        val value = TypedValue()
        if (context.theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, value, true)) {
            accentColor = value.data
            accentHighlight = ColorUtils.blendARGB(accentColor, Color.WHITE, 0.24f)
        }
        if (context.theme.resolveAttribute(com.google.android.material.R.attr.colorSecondary, value, true)) {
            secondaryColor = value.data
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val centerX = width / 2f
        val centerY = height / 2f
        ringShader = SweepGradient(
            centerX,
            centerY,
            intArrayOf(
                accentHighlight,
                secondaryColor,
                accentColor,
                ColorUtils.blendARGB(accentColor, secondaryColor, 0.5f),
                accentHighlight
            ),
            floatArrayOf(0f, 0.22f, 0.5f, 0.78f, 1f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (minOf(width, height) / 2f) - 18f * density
        if (radius <= 0f) return

        val phase = animationProgress * Math.PI * 2
        drawWaveBand(canvas, centerX, centerY, radius, phase)
    }

    private fun drawWaveBand(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        baseRadius: Float,
        phase: Double
    ) {
        val points = 180
        val halfWidth = 5f * density
        wavePath.reset()
        for (index in 0..points) {
            val angle = index * Math.PI * 2.0 / points
            val wave = (
                kotlin.math.sin(angle * 3.0 + phase) * 4.2 +
                    kotlin.math.sin(angle * 5.0 - phase * 2.0) * 2.2 +
                    kotlin.math.cos(angle * 2.0 + phase) * 1.4
                ) * density
            val outerRadius = baseRadius + halfWidth + wave.toFloat()
            val x = centerX + kotlin.math.cos(angle).toFloat() * outerRadius
            val y = centerY + kotlin.math.sin(angle).toFloat() * outerRadius
            if (index == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
        }
        for (index in points downTo 0) {
            val angle = index * Math.PI * 2.0 / points
            val wave = (
                kotlin.math.sin(angle * 3.0 + phase) * 4.2 +
                    kotlin.math.sin(angle * 5.0 - phase * 2.0) * 2.2 +
                    kotlin.math.cos(angle * 2.0 + phase) * 1.4
                ) * density
            val innerRadius = baseRadius - halfWidth + wave.toFloat()
            wavePath.lineTo(
                centerX + kotlin.math.cos(angle).toFloat() * innerRadius,
                centerY + kotlin.math.sin(angle).toFloat() * innerRadius
            )
        }
        wavePath.close()

        waveformPaint.style = Paint.Style.FILL
        waveformPaint.shader = ringShader
        shaderMatrix.setRotate(animationProgress * 360f, centerX, centerY)
        ringShader?.setLocalMatrix(shaderMatrix)
        canvas.drawPath(wavePath, waveformPaint)
        waveformPaint.shader = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isAttached = true
        isMotionEnabled = !UiMotion.isReduced(context)
        updateAnimationState()
    }

    override fun onDetachedFromWindow() {
        isAttached = false
        updateAnimationState()
        super.onDetachedFromWindow()
    }

    fun setMotionEnabled(enabled: Boolean) {
        if (isMotionEnabled == enabled) return
        isMotionEnabled = enabled
        updateAnimationState()
    }

    private fun updateAnimationState() {
        if (!isAttached || !isMotionEnabled) {
            if (animator.isStarted) animator.cancel()
            if (!isMotionEnabled) {
                animationProgress = 0f
                invalidate()
            }
        } else if (!animator.isStarted) {
            animator.start()
        }
    }
}
