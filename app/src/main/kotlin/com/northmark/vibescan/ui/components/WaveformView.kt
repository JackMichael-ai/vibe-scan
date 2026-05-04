

// app/src/main/java/com/northmark/vibescan/ui/components/WaveformView.kt
package com.northmark.vibescan.ui.components

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * WaveformView — displays time-domain acceleration waveform.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var waveform = FloatArray(512) { 0f }
    private var maxAmplitude = 1.0f

    private val waveformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#534AB7")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E1E0DA")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888780")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888780")
        textSize = 20f
    }

    private val path = Path()
    private val marginLeft = 60f
    private val marginRight = 20f
    private val marginTop = 20f
    private val marginBottom = 40f

    fun setWaveform(data: FloatArray) {
        waveform = data
        maxAmplitude = (data.maxOrNull()?.coerceAtLeast(data.minOrNull()?.let { kotlin.math.abs(it) } ?: 1f) ?: 1f) * 1.2f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val chartW = w - marginLeft - marginRight
        val chartH = h - marginTop - marginBottom
        val centerY = marginTop + chartH / 2

        // Draw grid lines
        for (i in 0..4) {
            val y = marginTop + (chartH / 4) * i
            canvas.drawLine(marginLeft, y, w - marginRight, y, gridPaint)
        }

        // Draw zero line
        canvas.drawLine(marginLeft, centerY, w - marginRight, centerY, zeroPaint)

        // Draw Y-axis labels
        canvas.drawText("${maxAmplitude.format(2)}", 5f, marginTop + 20f, labelPaint)
        canvas.drawText("0", 5f, centerY + 7f, labelPaint)
        canvas.drawText("-${maxAmplitude.format(2)}", 5f, h - marginBottom + 5f, labelPaint)

        // Draw waveform
        path.reset()
        var started = false

        for (i in waveform.indices) {
            val x = marginLeft + (i.toFloat() / waveform.size) * chartW
            val normalized = waveform[i] / maxAmplitude
            val y = centerY - (normalized * (chartH / 2))

            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }

        canvas.drawPath(path, waveformPaint)

        // Draw time axis
        canvas.drawText("Time (ms)", w / 2 - 40f, h - 8f, labelPaint)
    }

    private fun Float.format(decimals: Int): String = "%.${decimals}f".format(this)
}