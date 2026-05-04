package com.northmark.vibescan.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * SpectrumView — custom Canvas-based FFT spectrum visualiser.
 */
class SpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var spectrum    = FloatArray(256)
    private var shaftHz     = 25f
    private var maxAmp      = 1.0f
    private var actualSampleRate = 1000f

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1D9E75")
        style = Paint.Style.FILL
    }
    private val shaftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#534AB7")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val bpfoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D85A30")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }
    private val bpfiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BA7517")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 4f), 0f)
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B4B2A9")
        strokeWidth = 0.5f
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888780")
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }
    private val markerLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#534AB7")
        textSize = 22f
        textAlign = Paint.Align.LEFT
    }

    private val barRect = RectF()
    private val marginLeft   = 48f
    private val marginBottom = 36f
    private val marginTop    = 16f
    private val marginRight  = 16f

    fun setSpectrum(spec: FloatArray, shaftRpm: Float, sampleRate: Float = 1000f) {
        spectrum = spec
        shaftHz  = shaftRpm / 60f
        actualSampleRate = sampleRate
        maxAmp = (spec.maxOrNull() ?: 1.0f).coerceAtLeast(0.01f) * 1.2f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val chartW = w - marginLeft - marginRight
        val chartH = h - marginTop - marginBottom

        val nyquist    = actualSampleRate / 2f
        val nBins      = spectrum.size

        canvas.drawLine(marginLeft, marginTop, marginLeft, h - marginBottom, axisPaint)
        canvas.drawLine(marginLeft, h - marginBottom, w - marginRight, h - marginBottom, axisPaint)

        // Dynamic Frequency Labels
        val maxLabelHz = if (nyquist > 1000) 1000f else nyquist
        val step = maxLabelHz / 5f
        for (i in 0..5) {
            val hz = i * step
            val x = marginLeft + (hz / nyquist) * chartW
            canvas.drawLine(x, h - marginBottom, x, h - marginBottom + 6f, axisPaint)
            canvas.drawText("${hz.toInt()}", x, h - 8f, labelPaint)
        }

        val displayBins = 64
        val groupSize   = nBins / displayBins
        val barWidth    = chartW / displayBins - 1f

        // VibeScan Unified: Hide bins < 2Hz to remove "ski-slope" noise from view
        val minHzToDisplay = 2.0f
        val firstBinToDisplay = ((minHzToDisplay / nyquist) * nBins).toInt().coerceIn(0, nBins - 1)
        val maxBinToDisplay = ((maxLabelHz / nyquist) * nBins).toInt().coerceIn(0, nBins)

        for (i in 0 until displayBins) {
            val startIdx = (i * (maxBinToDisplay.toFloat() / displayBins)).toInt()
            val endIdx = ((i + 1) * (maxBinToDisplay.toFloat() / displayBins)).toInt()
            
            if (endIdx <= firstBinToDisplay) continue

            var groupMax = 0f
            for (idx in startIdx until endIdx) {
                if (idx < nBins && idx >= firstBinToDisplay && spectrum[idx] > groupMax) {
                    groupMax = spectrum[idx]
                }
            }

            if (groupMax <= 0) continue

            val barH = (groupMax / maxAmp) * chartH
            val x    = marginLeft + i * (barWidth + 1f)
            val top  = h - marginBottom - barH

            barPaint.color = amplitudeColor(groupMax / maxAmp)
            barRect.set(x, top, x + barWidth, h - marginBottom)
            canvas.drawRect(barRect, barPaint)
        }

        drawFrequencyMarker(canvas, shaftHz, nyquist, chartW, chartH, shaftPaint, "1×", marginLeft, h)
        drawFrequencyMarker(canvas, shaftHz * 3.585f, nyquist, chartW, chartH, bpfoPaint, "BPFO", marginLeft, h)
        drawFrequencyMarker(canvas, shaftHz * 5.415f, nyquist, chartW, chartH, bpfiPaint, "BPFI", marginLeft, h)
    }

    private fun drawFrequencyMarker(
        canvas: Canvas, hz: Float, nyquist: Float,
        chartW: Float, chartH: Float,
        paint: Paint, label: String,
        leftMargin: Float, h: Float
    ) {
        if (hz <= 0f || hz > nyquist) return
        val x = leftMargin + (hz / nyquist) * chartW
        canvas.drawLine(x, marginTop, x, h - marginBottom, paint)
        markerLabelPaint.color = paint.color
        canvas.drawText(label, x + 4f, marginTop + 22f, markerLabelPaint)
    }

    private fun amplitudeColor(ratio: Float): Int {
        return when {
            ratio < 0.5f -> Color.parseColor("#1D9E75")
            ratio < 0.75f -> Color.parseColor("#BA7517")
            else          -> Color.parseColor("#E24B4A")
        }
    }
}
