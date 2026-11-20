package com.nilsreichhard.carbonion.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.ceil

/**
 * The next alarm on the date row, right after the date:
 * "Thursday, Nov 19 · [alarm-clock icon] 07:00" (text_*.xml R.id.date_row).
 * The separator and time are TextViews in the date's font, size and colour;
 * the icon is a bitmap as tall as the date's text line with its ink the
 * date's cap height, centred on the caps, so all three line up when the row
 * aligns their tops.
 */
object AlarmBadge {
    const val SEPARATOR = " \u00B7 "

    /**
     * Minimum visible gap (space + the dot's side bearing) either side of the "·", in em: Roboto's is
     * 0.32 em; Outfit's space is 0.21 em and its dot has no side bearing, so it looked cramped.
     */
    const val SEP_MIN_GAP = 0.32f

    /** Space either side of the "·" when drawn into a bitmap. */
    fun sepSpace(p: Paint): Float {
        val dot = Rect().also { p.getTextBounds("\u00B7", 0, 1, it) }
        return maxOf(p.measureText(" "), p.textSize * SEP_MIN_GAP - dot.left)
    }

    /** Width of [SEPARATOR] as drawn by [drawSeparated] (never narrower than the TextView's). */
    fun separatorWidth(p: Paint): Float = 2 * sepSpace(p) + p.measureText("\u00B7")

    /** Width of [text] with each [SEPARATOR] widened as in [separatorWidth]. */
    fun measureSeparated(text: String, p: Paint): Float {
        val parts = text.split(SEPARATOR)
        return parts.sumOf { p.measureText(it).toDouble() }.toFloat() + (parts.size - 1) * separatorWidth(p)
    }

    /** Draws [text] at [x], widening each [SEPARATOR]; returns the width drawn. */
    fun drawSeparated(c: Canvas, text: String, x: Float, baseline: Float, p: Paint): Float {
        var cx = x
        text.split(SEPARATOR).forEachIndexed { i, part ->
            if (i > 0) { cx += sepSpace(p); c.drawText("\u00B7", cx, baseline, p); cx += p.measureText("\u00B7") + sepSpace(p) }
            c.drawText(part, cx, baseline, p); cx += p.measureText(part)
        }
        return cx - x
    }

    /** Text size steps (with the short date) before the alarm is hidden; the alarm time is never truncated. */
    val SHRINK = floatArrayOf(1f, 0.93f, 0.86f)

    /** How the date row is laid out: date format, text scale, alarm shown. */
    data class Plan(val shortDate: Boolean, val scale: Float, val showAlarm: Boolean)

    /** "EEEE, MMM d" → "EEE, MMM d" (weekday and month names abbreviated). */
    fun shortPattern(pattern: String): String = pattern.replace("EEEE", "EEE").replace("MMMM", "MMM")

    fun paint(tf: Typeface, px: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = px }

    fun capHeight(p: Paint): Float = Rect().also { p.getTextBounds("H", 0, 1, it) }.height().toFloat()

    /** Icon box side whose ink (bells to feet, see [drawIcon]) is the cap height. */
    fun iconSize(p: Paint): Float = capHeight(p) / ICON_INK

    /** Gap between the icon and the time. */
    fun iconGap(px: Float): Float = px * 0.22f

    /** Separator + icon + gap + time, at [px]. */
    fun alarmWidth(alarm: String, tf: Typeface, px: Float): Float {
        val p = paint(tf, px)
        return separatorWidth(p) + iconSize(p) + iconGap(px) + p.measureText(alarm)
    }

    private fun iconBitmapWidth(p: Paint): Int = ceil(iconSize(p) + iconGap(p.textSize)).toInt()

    /**
     * Fits "date · [icon] alarm" into [availPx]: the long date first, then the short
     * date, then the short date a little smaller; the alarm is hidden only when none fit.
     * [longDate]/[shortDate] are the widest dates the live date can show before the next update.
     */
    fun plan(longDate: String, shortDate: String, alarm: String?, tf: Typeface, px: Float, availPx: Float): Plan {
        if (alarm == null) return Plan(false, 1f, false)
        fun w(date: String, f: Float) = paint(tf, px * f).measureText(date) + alarmWidth(alarm, tf, px * f)
        if (w(longDate, 1f) <= availPx) return Plan(false, 1f, true)
        for (f in SHRINK) if (w(shortDate, f) <= availPx) return Plan(true, f, true)
        return Plan(false, 1f, false)
    }

    /**
     * The icon for R.id.alarm_icon: as tall as a TextView line at [px] with
     * includeFontPadding=false (descent − ascent), ink centred on the caps
     * (baseline − cap/2), followed by the gap before the time.
     */
    fun iconBitmap(tf: Typeface, px: Float, color: Int, shadow: Int): Bitmap {
        val p = paint(tf, px)
        val fm = p.fontMetricsInt
        val h = (fm.descent - fm.ascent).coerceAtLeast(1)
        val size = iconSize(p)
        val bmp = Bitmap.createBitmap(iconBitmapWidth(p).coerceAtLeast(1), h, Bitmap.Config.ARGB_8888)
        val capCenter = -fm.ascent - capHeight(p) / 2f
        drawIcon(Canvas(bmp), size / 2f + 0.5f, capCenter - size * ICON_INK_OFFSET, size, color, shadow)
        return bmp
    }

    /** Draws the icon into a bitmap-drawn date row (bundled fonts): left edge [x], text baseline [baseline]. */
    fun drawInline(c: Canvas, x: Float, baseline: Float, p: Paint, color: Int, shadow: Int): Float {
        val size = iconSize(p)
        drawIcon(c, x + size / 2f, baseline - capHeight(p) / 2f - size * ICON_INK_OFFSET, size, color, shadow)
        return size + iconGap(p.textSize)
    }

    /** Ink height of [drawIcon] as a fraction of its size, and the ink centre's offset below cy. */
    private const val ICON_INK = 0.975f
    private const val ICON_INK_OFFSET = 0.023f

    /**
     * Alarm clock in [size] (Material "alarm" shape): round face, hands at
     * twelve and four, two bells above, two feet below.
     */
    fun drawIcon(c: Canvas, cx: Float, cy: Float, size: Float, color: Int, shadow: Int = 0) {
        val sw = size * 0.115f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = sw
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            if (shadow != 0) setShadowLayer(2f, 0f, 1f, shadow)
        }
        val r = size * 0.36f
        val fy = cy + size * 0.05f
        val path = Path().apply {
            addCircle(cx, fy, r, Path.Direction.CW)
            moveTo(cx, fy - r * 0.58f); lineTo(cx, fy); lineTo(cx + r * 0.42f, fy + r * 0.3f)
            // Bells: short arcs hugging the face at ten and two o'clock.
            val br = r * 1.38f
            addArc(RectF(cx - br, fy - br, cx + br, fy + br), 213f, 34f)
            addArc(RectF(cx - br, fy - br, cx + br, fy + br), 293f, 34f)
            // Feet.
            moveTo(cx - r * 0.66f, fy + r * 0.78f); lineTo(cx - r * 0.95f, fy + r * 1.12f)
            moveTo(cx + r * 0.66f, fy + r * 0.78f); lineTo(cx + r * 0.95f, fy + r * 1.12f)
        }
        c.drawPath(path, p)
    }
}
