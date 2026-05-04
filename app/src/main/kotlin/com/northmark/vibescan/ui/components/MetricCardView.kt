

// app/src/main/java/com/northmark/vibescan/ui/components/MetricCardView.kt
package com.northmark.vibescan.ui.components

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.northmark.vibescan.R

/**
 * MetricCardView — displays a single metric with sparkline history.
 */
class MetricCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var label = "RMS"
    private var value = "0.00"
    private var unit = "mm/s"
    private var history = FloatArray(20) { 0f }
    private var alertColor = Color.parseColor("#1D9E75")

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888780")
        textSize = 28f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 48f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888780")
        textSize = 24f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    private val sparklinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val sparkPath = Path()
    private val fillPath = Path()
    private val rect = RectF()

    fun setMetric(label: String, value: String, unit: String, color: Int) {
        this.label = label
        this.value = value
        this.unit = unit
        this.alertColor = color
        invalidate()
    }

    fun addHistoryPoint(point: Float) {
        // Shift history and add new point
        for (i in 0 until history.size - 1) {
            history[i] = history[i + 1]
        }
        history[history.size - 1] = point
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // Draw background
        rect.set(0f, 0f, w, h)
        bgPaint.color = Color.parseColor("#1A1A1A") // Match vibe_s2
        canvas.drawRoundRect(rect, 12f, 12f, bgPaint)

        // Draw label
        canvas.drawText(label, 24f, 48f, labelPaint)

        // Draw value
        valuePaint.color = alertColor
        canvas.drawText(value, 24f, 110f, valuePaint)

        // Draw unit
        val valueWidth = valuePaint.measureText(value)
        canvas.drawText(unit, 32f + valueWidth, 110f, unitPaint)

        // Draw sparkline
        if (history.any { it > 0f }) {
            drawSparkline(canvas, w, h)
        }
    }

    private fun drawSparkline(canvas: Canvas, w: Float, h: Float) {
        val sparkHeight = 60f
        val sparkBottom = h - 10f
        val sparkTop = sparkBottom - sparkHeight
        val sparkLeft = 0f
        val sparkRight = w
        val sparkWidth = sparkRight - sparkLeft

        val maxValue = history.maxOrNull() ?: 1f
        val minValue = (history.filter { it > 0f }.minOrNull() ?: 0f) * 0.9f
        val range = (maxValue - minValue).coerceAtLeast(0.01f)

        sparkPath.reset()
        fillPath.reset()
        var started = false

        for (i in history.indices) {
            val x = sparkLeft + (i.toFloat() / (history.size - 1)) * sparkWidth
            val normalized = (history[i] - minValue) / range
            val y = sparkBottom - (normalized.coerceIn(0f, 1f) * sparkHeight)

            if (!started) {
                sparkPath.moveTo(x, y)
                fillPath.moveTo(x, sparkBottom)
                fillPath.lineTo(x, y)
                started = true
            } else {
                sparkPath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(sparkRight, sparkBottom)
        fillPath.close()

        // Fill
        fillPaint.shader = LinearGradient(
            0f, sparkTop, 0f, sparkBottom,
            Color.argb(80, Color.red(alertColor), Color.green(alertColor), Color.blue(alertColor)),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)

        // Line
        sparklinePaint.color = alertColor
        sparklinePaint.strokeWidth = 4f
        canvas.drawPath(sparkPath, sparklinePaint)
    }
}