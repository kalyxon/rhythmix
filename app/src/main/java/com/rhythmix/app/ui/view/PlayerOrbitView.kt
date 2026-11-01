package com.rhythmix.app.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.rhythmix.app.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class PlayerOrbitView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcBounds = RectF()
    private val density = resources.displayMetrics.density
    private var progressValue = 0f
    private var touchTracking = false

    private val accentColor: Int = TypedValue().let { value ->
        context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, value, true)
        value.data
    }

    var positionLabel: String = "0:00"
        set(value) {
            field = value
            updateContentDescription()
            invalidate()
        }

    var remainingLabel: String = "-0:00"
        set(value) {
            field = value
            updateContentDescription()
            invalidate()
        }

    var onSeekProgressChanged: ((Float) -> Unit)? = null
    var onSeekStarted: (() -> Unit)? = null
    var onSeekFinished: ((Float) -> Unit)? = null
    var onSeekCancelled: (() -> Unit)? = null

    var progress: Float
        get() = progressValue
        set(value) {
            val newProgress = value.coerceIn(0f, 1f)
            if (progressValue == newProgress) return
            progressValue = newProgress
            invalidate()
        }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Track playback progress"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        val size = min(width, height).toFloat()
        if (size <= 0f) return

        val discRadius = size * 0.41f
        paint.style = Paint.Style.FILL
        paint.color = context.getColor(R.color.black)
        paint.alpha = 230
        canvas.drawCircle(centerX, centerY, discRadius, paint)

        val orbitRadius = size * 0.46f
        arcBounds.set(
            centerX - orbitRadius,
            centerY - orbitRadius,
            centerX + orbitRadius,
            centerY + orbitRadius
        )
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = context.getColor(R.color.text_tertiary)
        paint.alpha = 110
        canvas.drawArc(arcBounds, 180f, -180f, false, paint)

        paint.color = accentColor
        paint.alpha = 255
        canvas.drawArc(arcBounds, 180f, -180f * progressValue, false, paint)

        val knobAngle = Math.toRadians((180f - 180f * progressValue).toDouble())
        val knobX = centerX + cos(knobAngle).toFloat() * orbitRadius
        val knobY = centerY + sin(knobAngle).toFloat() * orbitRadius
        paint.style = Paint.Style.FILL
        canvas.drawCircle(knobX, knobY, 5f * density, paint)
        paint.color = context.getColor(R.color.text_primary)
        canvas.drawCircle(knobX, knobY, 2f * density, paint)

        paint.color = context.getColor(R.color.text_secondary)
        paint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            11f,
            resources.displayMetrics
        )
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(positionLabel, centerX - orbitRadius - 10f * density, centerY - 18f * density, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(remainingLabel, centerX + orbitRadius + 10f * density, centerY - 18f * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val centerX = width / 2f
        val centerY = height / 2f
        val orbitRadius = min(width, height) * 0.46f
        val dx = event.x - centerX
        val dy = event.y - centerY
        val distance = kotlin.math.hypot(dx, dy)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (dy < 0f || kotlin.math.abs(distance - orbitRadius) > 30f * density) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                touchTracking = true
                onSeekStarted?.invoke()
                updateProgressFromTouch(dx, dy)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchTracking) return false
                updateProgressFromTouch(dx, dy)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!touchTracking) return false
                updateProgressFromTouch(dx, dy)
                touchTracking = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                onSeekFinished?.invoke(progressValue)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!touchTracking) return false
                touchTracking = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onSeekCancelled?.invoke()
                return true
            }
        }
        return touchTracking
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateProgressFromTouch(dx: Float, dy: Float) {
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            .coerceIn(0f, 180f)
        progress = (180f - angle) / 180f
        onSeekProgressChanged?.invoke(progressValue)
    }

    private fun updateContentDescription() {
        contentDescription = "Playback position $positionLabel, $remainingLabel remaining"
    }
}
