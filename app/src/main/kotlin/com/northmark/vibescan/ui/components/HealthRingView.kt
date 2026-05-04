// app/src/main/java/com/northmark/vibescan/ui/components/HealthRingView.kt
package com.northmark.vibescan.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.northmark.vibescan.R

/**
 * HealthRingView — animated arc showing 0–100 health score with gradient and pulse effect.
 */
class HealthRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var targetHealth = 100
    private var displayHealth = 100f
    private var ringColor = Color.parseColor("#1D9E75")
    private var pulsePhase = 0f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 20f
        color = Color.parseColor("#E1F5EE")
        strokeCap = Paint.Cap.ROUND
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 20f
        strokeCap = Paint.Cap.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 24f
        strokeCap = Paint.Cap.ROUND
    }

    private val centerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 72f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 24f
        color = Color.parseColor("#888780")
    }

    private val oval = RectF()
    private val glowOval = RectF()
    private var healthAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    init {
        startPulseAnimation()
    }

    fun setHealth(health: Int) {
        if (health == targetHealth) return
        targetHealth = health
        healthAnimator?.cancel()
        healthAnimator = ValueAnimator.ofFloat(displayHealth, health.toFloat()).apply {
            duration = 800L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                displayHealth = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setRingColor(color: Int) {
        ringColor = color
        invalidate()
    }

    private fun startPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener {
                pulsePhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (width.coerceAtMost(height) / 2f) - 40f

        // Set oval bounds
        oval.set(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius
        )

        // Glow effect (pulsing)
        val glowRadius = radius + (pulsePhase * 4f)
        glowOval.set(
            centerX - glowRadius,
            centerY - glowRadius,
            centerX + glowRadius,
            centerY + glowRadius
        )

        val glowAlpha = (1f - pulsePhase) * 0.3f
        glowPaint.color = Color.argb(
            (glowAlpha * 255).toInt(),
            Color.red(ringColor),
            Color.green(ringColor),
            Color.blue(ringColor)
        )

        // Draw glow
        canvas.drawArc(glowOval, 270f, 360f, false, glowPaint)

        // Draw track
        trackPaint.color = Color.argb(
            40,
            Color.red(ringColor),
            Color.green(ringColor),
            Color.blue(ringColor)
        )
        canvas.drawArc(oval, 270f, 360f, false, trackPaint)

        // Draw health arc with gradient
        arcPaint.color = ringColor
        val sweep = (displayHealth / 100f) * 360f
        canvas.drawArc(oval, 270f, sweep, false, arcPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        healthAnimator?.cancel()
        pulseAnimator?.cancel()
    }
}