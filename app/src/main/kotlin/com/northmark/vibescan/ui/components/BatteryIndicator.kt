

// app/src/main/java/com/northmark/vibescan/ui/components/BatteryIndicator.kt
package com.northmark.vibescan.ui.components

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * BatteryIndicator — battery level with charging status.
 */
class BatteryIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var level = 100
    private var isCharging = false
    private var temperature = 25f

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#888780")
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 24f
        color = Color.parseColor("#1A1A1A")
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    private val bodyRect = RectF()
    private val fillRect = RectF()
    private val tipRect = RectF()

    fun setBattery(percent: Int, charging: Boolean, tempC: Float) {
        level = percent.coerceIn(0, 100)
        isCharging = charging
        temperature = tempC
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // Battery body
        bodyRect.set(10f, h / 4, w - 20f, h * 3 / 4)
        canvas.drawRoundRect(bodyRect, 4f, 4f, bodyPaint)

        // Battery tip
        tipRect.set(w - 20f, h / 3, w - 10f, h * 2 / 3)
        canvas.drawRect(tipRect, bodyPaint)

        // Battery fill level
        val fillWidth = ((w - 30f - 10f) * (level / 100f))
        fillRect.set(13f, h / 4 + 3f, 13f + fillWidth, h * 3 / 4 - 3f)

        fillPaint.color = when {
            level > 60 -> Color.parseColor("#1D9E75")
            level > 20 -> Color.parseColor("#BA7517")
            else -> Color.parseColor("#E24B4A")
        }
        canvas.drawRoundRect(fillRect, 2f, 2f, fillPaint)

        // Draw charging indicator
        if (isCharging) {
            val boltPath = Path().apply {
                moveTo(w / 2 - 8f, h / 2 - 12f)
                lineTo(w / 2 + 4f, h / 2)
                lineTo(w / 2 - 2f, h / 2)
                lineTo(w / 2 + 8f, h / 2 + 12f)
                lineTo(w / 2 - 4f, h / 2)
                lineTo(w / 2 + 2f, h / 2)
                close()
            }
            val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.WHITE
            }
            canvas.drawPath(boltPath, boltPaint)
        }

        // Draw level text
        canvas.drawText("$level%", w / 2, h + 30f, textPaint)
    }
}