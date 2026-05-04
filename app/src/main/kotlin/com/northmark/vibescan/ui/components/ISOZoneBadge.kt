

// app/src/main/java/com/northmark/vibescan/ui/components/ISOZoneBadge.kt
package com.northmark.vibescan.ui.components

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * ISOZoneBadge — displays ISO 10816 zone with colored indicator.
 */
class ISOZoneBadge @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var zone = "A"
    private var zoneColor = Color.parseColor("#1D9E75")
    private var subtitle = "Good"

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 56f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#888780")
    }

    private val rect = RectF()

    fun setZone(zone: String, color: Int, subtitle: String) {
        this.zone = zone
        this.zoneColor = color
        this.subtitle = subtitle
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // Draw background with zone color alpha
        bgPaint.color = Color.argb(
            30,
            Color.red(zoneColor),
            Color.green(zoneColor),
            Color.blue(zoneColor)
        )
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, 16f, 16f, bgPaint)

        // Draw border
        val borderPaint = Paint(bgPaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = zoneColor
        }
        canvas.drawRoundRect(rect, 16f, 16f, borderPaint)

        // Draw zone letter
        zonePaint.color = zoneColor
        canvas.drawText("ZONE $zone", w / 2, h / 2 + 10f, zonePaint)

        // Draw subtitle
        canvas.drawText(subtitle, w / 2, h / 2 + 50f, subtitlePaint)
    }
}