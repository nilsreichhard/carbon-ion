package com.nilsreichhard.carbonion.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.floor

/**
 * The Modern chart, large (WidgetLayout.DETAIL: timeline, three cloud
 * layers, rain, events, temperature), with hour labels underneath and a
 * touch-to-inspect scrub line. The selection stays after the finger lifts;
 * [onHour] reports the payload slot under it.
 */
class ChartView(context: Context) : View(context) {
    var payload: WeatherPayload? = null; set(v) { field = v; rerender() }
    var settings: WidgetSettings = WidgetSettings(); set(v) { field = v; rerender() }
    var light = true; set(v) { field = v; rerender() }
    var accent: Int? = null
    var textTypeface: Typeface = Typeface.DEFAULT
    var nowMillis: Long = System.currentTimeMillis()
    var onHour: ((Int?) -> Unit)? = null

    /** Selected column (0-based in the visible window), or null. */
    var selected: Int? = null
        private set

    private val dp = resources.displayMetrics.density
    private val axisH = (22 * dp).toInt()
    private var bmp: Bitmap? = null
    private var cols: ModernRenderer.Columns? = null
    private var win: WidgetRenderer.Window? = null
    private val tz: TimeZone get() = TimeZone.getDefault()

    private val axisP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11.5f * dp; textAlign = Paint.Align.CENTER }
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * dp; strokeCap = Paint.Cap.ROUND }
    private val tagP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tagText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12.5f * dp; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

    fun select(col: Int?) {
        val c = cols ?: return
        selected = col?.coerceIn(0, c.total - 1)
        invalidate()
        onHour?.invoke(selected?.let { slotOf(it) })
    }

    fun slotOf(col: Int): Int = (win?.startSlot ?: 0) + col

    /** Column under x (px), or null outside the hour columns. */
    fun columnAt(x: Float): Int? {
        val c = cols ?: return null
        val i = floor((x - c.leftPx) / c.colPx).toInt()
        return if (i in 0 until c.total) i else null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rerender()

    private fun rerender() {
        val w = width; val h = height - axisH
        if (w <= 0 || h <= 0) return
        val spec = ModernRenderer.Spec(w, h, dp, WidgetLayout.DETAIL, textTypeface = textTypeface, accent = accent,
            textShadow = false)
        val s = settings.copy(lightTheme = light)
        bmp = ModernRenderer.render(payload, s, spec, nowMillis, tz)
        cols = ModernRenderer.columns(spec, s)
        win = payload?.let { WidgetRenderer.Window.of(it, s.copy(forecastHours = cols!!.forecastHours), nowMillis, tz) }
        selected = selected?.coerceAtMost((cols?.total ?: 1) - 1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val b = bmp ?: return
        canvas.drawBitmap(b, 0f, 0f, null)
        val c = cols ?: return
        val p = payload
        val ink = if (light) 0xFF44474E.toInt() else 0xFFC4C6D0.toInt()
        axisP.color = ink
        // Hour labels every 3 h (6 h when columns are narrow), at the column boundaries.
        if (p != null) {
            val step = if (c.colPx < 14 * dp) 6 else 3
            val fmt = SimpleDateFormat(if (android.text.format.DateFormat.is24HourFormat(context)) "HH" else "ha", Locale.getDefault())
            for (i in 0..c.total) {
                val t = DetailData.slotMillis(p, slotOf(i))
                val hr = java.util.Calendar.getInstance(tz).apply { timeInMillis = t }.get(java.util.Calendar.HOUR_OF_DAY)
                if (hr % step != 0) continue
                val x = c.leftPx + i * c.colPx
                if (x < 12 * dp || x > width - 12 * dp) continue
                canvas.drawText(fmt.format(Date(t)), x, b.height + axisH * 0.72f, axisP)
            }
        }
        val sel = selected ?: return
        val x = c.leftPx + (sel + 0.5f) * c.colPx
        val acc = accent ?: if (light) 0xFF3F5F90.toInt() else 0xFFA8C8FF.toInt()
        lineP.color = acc
        canvas.drawLine(x, 0f, x, b.height.toFloat(), lineP)
        if (p != null) {
            val t = DetailData.slotMillis(p, slotOf(sel))
            val label = SimpleDateFormat(if (android.text.format.DateFormat.is24HourFormat(context)) "EEE HH:mm" else "EEE h a", Locale.getDefault()).format(Date(t))
            val tw = tagText.measureText(label) + 16 * dp
            val l = (x - tw / 2).coerceIn(2 * dp, width - tw - 2 * dp)
            val r = RectF(l, b.height + 1f, l + tw, b.height + axisH - 1f)
            tagP.color = acc
            canvas.drawRoundRect(r, axisH / 2f, axisH / 2f, tagP)
            tagText.color = if (light) 0xFFFFFFFF.toInt() else 0xFF0A1F3A.toInt()
            canvas.drawText(label, r.centerX(), r.centerY() + tagText.textSize * 0.36f, tagText)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                columnAt(e.x)?.let { if (it != selected) select(it) }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { parent?.requestDisallowInterceptTouchEvent(false); return true }
        }
        return super.onTouchEvent(e)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // Chart rows ≈ 1.45 dp each (a little taller than a 4x4 widget), plus the hour axis.
        val h = (WidgetLayout.DETAIL.totalRows * 1.45f * dp).toInt() + axisH
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }
}
