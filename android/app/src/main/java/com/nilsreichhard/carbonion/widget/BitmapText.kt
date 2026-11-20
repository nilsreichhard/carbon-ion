package com.nilsreichhard.carbonion.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * City row and date drawn into the chart bitmap, for bundled text fonts that
 * the launcher can't apply to RemoteViews text. Positions mirror the
 * time-block layout (text_*.xml): city row top-centred with the condition
 * icon after a 5-row gap, date row (date + next alarm) bottom-centred, no font padding.
 */
object BitmapText {
    class Item(
        val city: String,
        val date: String,
        /** Text size in rows. */
        val textRows: Float,
        val typeface: Typeface,
        val color: Int,
        val icon: Bitmap?,
        /** Icon size in rows. */
        val iconRows: Float,
        val shadow: Boolean,
        /** Next alarm after the date ("· [icon] 07:00"), or null. */
        val alarm: String? = null,
        /** Date row text scale (AlarmBadge.Plan.scale). */
        val dateScale: Float = 1f,
    )

    /** Draws onto [bmp], whose height is the layout's total rows. */
    fun draw(bmp: Bitmap, layout: WidgetLayout, it: Item) {
        val k = bmp.height / layout.totalRows.toFloat()
        val c = Canvas(bmp)
        c.scale(k, k)
        val w = bmp.width / k
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = it.typeface; textSize = it.textRows; color = it.color
            if (it.shadow) setShadowLayer(2f / k * 2.625f, 0f, 1f / k * 2.625f, 0x40000000)
        }
        val fm = p.fontMetrics
        val boxH = fm.descent - fm.ascent
        val top = layout.timeY.toFloat()
        if (it.city.isNotEmpty()) {
            val gap = 5f
            val cityW = AlarmBadge.measureSeparated(it.city, p)
            val iconW = if (it.icon != null) gap + it.iconRows else 0f
            val rowH = maxOf(boxH, if (it.icon != null) it.iconRows else 0f)
            val x0 = (w - cityW - iconW) / 2f
            AlarmBadge.drawSeparated(c, it.city, x0, top + (rowH - boxH) / 2f - fm.ascent, p)
            it.icon?.let { ic ->
                val y = top + (rowH - it.iconRows) / 2f
                c.drawBitmap(ic, null, android.graphics.RectF(x0 + cityW + gap, y, x0 + cityW + gap + it.iconRows, y + it.iconRows),
                    Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            }
        }
        // Date row: date, then the alarm (separator, icon, time) in the same paint, centred as one row.
        p.textSize = it.textRows * it.dateScale
        val baseline = top + layout.timeH - p.fontMetrics.descent
        val alarmW = it.alarm?.let { a -> AlarmBadge.alarmWidth(a, it.typeface, p.textSize) } ?: 0f
        var x = (w - p.measureText(it.date) - alarmW) / 2f
        c.drawText(it.date, x, baseline, p)
        it.alarm?.let { a ->
            x += p.measureText(it.date)
            x += AlarmBadge.drawSeparated(c, AlarmBadge.SEPARATOR, x, baseline, p)
            x += AlarmBadge.drawInline(c, x, baseline, p, it.color, if (it.shadow) 0x40000000 else 0)
            c.drawText(a, x, baseline, p)
        }
    }
}
