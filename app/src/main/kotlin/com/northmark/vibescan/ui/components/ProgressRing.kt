

// app/src/main/java/com/northmark/vibescan/ui/components/ProgressRing.kt
package com.northmark.vibescan.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * ProgressRing — circular progress indicator for baseline learning.
 */
class ProgressRing @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var progress = 0
    private var displayProgress = 0f
    private var isIndeterminate = false

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        color = Color.parseColor("#E1E0DA")
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        color = Color.parseColor("#534AB7")
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 32f
        color = Color.parseColor("#1A1A1A")
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val oval = RectF()
    private var rotationAngle = 0f
    private var animator: ValueAnimator? = null
    private var rotationAnimator: ValueAnimator? = null

    fun setProgress(percent: Int) {
        if (percent == progress) return
        progress = percent.coerceIn(0, 100)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(displayProgress, progress.toFloat()).apply {
            duration = 400L
            addUpdateListener {
                displayProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setIndeterminate(indeterminate: Boolean) {
        isIndeterminate = indeterminate
        if (indeterminate) {
            startRotationAnimation()
        } else {
            rotationAnimator?.cancel()
            rotationAngle = 0f
        }
        invalidate()
    }

    private fun startRotationAnimation() {
        rotationAnimator?.cancel()
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1500L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotationAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val pad = 20f
        oval.set(pad, pad, width - pad, height - pad)

        // Draw track
        canvas.drawArc(oval, 0f, 360f, false, trackPaint)

        if (isIndeterminate) {
            // Draw indeterminate arc
            canvas.save()
            canvas.rotate(rotationAngle, width / 2f, height / 2f)
            canvas.drawArc(oval, 0f, 120f, false, progressPaint)
            canvas.restore()
        } else {
            // Draw determinate progress
            val sweep = (displayProgress / 100f) * 360f
            canvas.drawArc(oval, -90f, sweep, false, progressPaint)

            // Draw percentage text
            canvas.drawText(
                "${displayProgress.toInt()}%",
                width / 2f,
                height / 2f + (textPaint.textSize / 3),
                textPaint
            )
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        rotationAnimator?.cancel()
    }
}