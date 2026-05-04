package com.northmark.vibescan.ui.components

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.northmark.vibescan.R

/**
 * HealthTrendView — A specialized sparkline for tracking the last 60 health scores.
 */
class HealthTrendView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val maxPoints = 60
    private val history = IntArray(maxPoints) { -1 } // -1 indicates no data
    private var head = 0
    private var count = 0

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
        strokeWidth = 1f
    }

    private val path = Path()
    private val fillPath = Path()
    private var gradient: LinearGradient? = null
    private var lastHeight = 0f
    private var lastColor = 0

    fun addHealthPoint(health: Int) {
        history[head] = health
        head = (head + 1) % maxPoints
        if (count < maxPoints) count++
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (count < 2) return

        val w = width.toFloat()
        val h = height.toFloat()

        if (h != lastHeight) {
            lastHeight = h
            gradient = null // Force recreate
        }

        // Draw horizontal grid lines for 0, 50, 100
        canvas.drawLine(0f, h * 0.5f, w, h * 0.5f, gridPaint)

        path.reset()
        fillPath.reset()

        var started = false
        val xStep = w / (maxPoints - 1)

        for (i in 0 until count) {
            // Get data in chronological order
            val index = if (count < maxPoints) i else (head + i) % maxPoints
            val health = history[index]
            if (health < 0) continue

            val x = i * xStep
            val y = h - (health.toFloat() / 100f * h)

            if (!started) {
                path.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            
            if (i == count - 1) {
                fillPath.lineTo(x, h)
            }
        }

        // Draw fill with gradient
        val latestIndex = if (head == 0) maxPoints - 1 else head - 1
        val currentHealth = history[latestIndex]
        val color = getHealthColor(currentHealth)
        
        if (gradient == null || color != lastColor) {
            lastColor = color
            gradient = LinearGradient(0f, 0f, 0f, h, 
                Color.argb(100, Color.red(color), Color.green(color), Color.blue(color)),
                Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        
        fillPaint.shader = gradient
        canvas.drawPath(fillPath, fillPaint)

        // Draw line
        linePaint.color = color
        canvas.drawPath(path, linePaint)
    }

    private fun getHealthColor(health: Int): Int {
        return when {
            health >= 85 -> ContextCompat.getColor(context, R.color.vibe_green)
            health >= 70 -> ContextCompat.getColor(context, R.color.vibe_amber)
            health >= 40 -> ContextCompat.getColor(context, R.color.vibe_coral)
            health >= 0  -> ContextCompat.getColor(context, R.color.vibe_red)
            else -> ContextCompat.getColor(context, R.color.vibe_muted)
        }
    }
}
