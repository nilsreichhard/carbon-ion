package com.nilsreichhard.carbonion.widget

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The high-resolution "Modern" style: the watch's layout, rows, data and
 * event logic (shared with [WidgetRenderer]), drawn anti-aliased at the
 * device's real pixel density with full colour instead of the e-ink
 * palette and dithering. Coordinates stay in watch rows (the canvas is
 * scaled to device pixels), so every breakpoint and size behaves exactly
 * like the Classic renderer.
 */
object ModernRenderer {

    class Spec(
        val widthPx: Int,
        val heightPx: Int,
        /** Device density (px per dp), for hairline widths. */
        val density: Float,
        val layout: WidgetLayout = WidgetLayout.DEFAULT,
        val clockSample: String = "00:00",
        val clockRows: Float = CarbonStyle.CLOCK_TEXT_ROWS,
        val battery: WidgetRenderer.Battery? = null,
        val clockTypeface: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL),
        val textTypeface: Typeface = Typeface.DEFAULT,
        /** Soft scrim behind the time block (transparent backgrounds). */
        val scrim: Boolean = false,
        /** Wallpaper accent (Material You) for the now line and the night track, or null for the watch colours. */
        val accent: Int? = null,
        /** Corner radius in px (the launcher's widget radius). */
        val cornerPx: Float = 0f,
        /** Small text shadow for legibility over wallpapers. */
        val textShadow: Boolean = true,
        /** Clip to the rounded corners (tests turn it off to prove nothing would be cut). */
        val clipCorners: Boolean = true,
    ) {
        companion object {
            fun forWidget(wDp: Int, hDp: Int, density: Float, layout: WidgetLayout, clockSample: String,
                          clockRows: Float, battery: WidgetRenderer.Battery?, clockTypeface: Typeface, textTypeface: Typeface,
                          scrim: Boolean, accent: Int?, cornerPx: Float, textShadow: Boolean): Spec {
                // Cap the bitmap at ~1.2 Mpx so two size-mapped bitmaps stay well within the binder limit.
                var d = density
                while (wDp * d * hDp * d > 1_300_000f && d > 1f) d -= 0.25f
                return Spec((wDp * d).roundToInt().coerceAtLeast(50), (hDp * d).roundToInt().coerceAtLeast(50), d, layout,
                    clockSample, clockRows, battery, clockTypeface, textTypeface, scrim, accent, cornerPx * d / density, textShadow)
            }
        }
    }

    /** Colours designed for transparent backgrounds over wallpapers and for solid ones. */
    class Palette(val light: Boolean, accent: Int?) {
        val ink = if (light) 0xFF1F2124.toInt() else 0xFFF4F6F8.toInt()
        val ink2 = if (light) 0xFF5F6368.toInt() else 0xFFC4C9CF.toInt()
        val shadow = if (light) 0x55FFFFFF else 0x66000000
        /** Halo behind the temperature labels: the background tone of the theme. */
        val halo = if (light) 0xFFFFFFFF.toInt() else 0xFF181C24.toInt()
        /** Night track; with a wallpaper accent it takes the accent's hue (the sun stays warm). */
        val night = (if (light) 0xFF8792A6.toInt() else 0xFF3A4458.toInt()).let { if (accent != null) mix(it, accent, 0.55f) else it }
        val day = if (light) 0xFFFFB627.toInt() else 0xFFFFCA5C.toInt()
        val needle = accent ?: if (light) 0xFFE53935.toInt() else 0xFFFF6B6B.toInt()
        val sun = 0xFFFFC21A.toInt()
        val sunRing = 0xFFF08C00.toInt()
        val moonDark = if (light) 0xFF56637A.toInt() else 0xFF2B3344.toInt()
        val moonLit = if (light) 0xFFFFFFFF.toInt() else 0xFFF2F2EA.toInt()
        val moonRim = if (light) 0xFF56637A.toInt() else 0xFFB8C0CC.toInt()
        /** Split layer tones: high, mid, low (lighter to darker); Total uses one. */
        val cloudHigh = if (light) 0xFFAEB9CA.toInt() else 0xFFE6EBF1.toInt()
        val cloudMid = if (light) 0xFF8593A9.toInt() else 0xFFB9C3D0.toInt()
        val cloudLow = if (light) 0xFF5D6B82.toInt() else 0xFF8D9AAC.toInt()
        val cloudTotal = if (light) 0xFF7D8BA2.toInt() else 0xFFC9D1DB.toInt()
        val cloudHeavy = if (light) 0xFF465266.toInt() else 0xFF667389.toInt()
        val faint = if (light) 0xFFFFFFFF.toInt() else 0xFF2A2F38.toInt()
        val chance = 0xFFFF8F1F.toInt()
        val hailRing = 0xFF8E24AA.toInt()
        val evStorm = if (light) 0xFFE0A100.toInt() else 0xFFFFD84D.toInt()
        val evWind = if (light) 0xFF23A047.toInt() else 0xFF5CE68A.toInt()
        val evSnow = if (light) 0xFF1E88E5.toInt() else 0xFF7FD3FF.toInt()
        val evFog = if (light) 0xFF78869A.toInt() else 0xFFB4BFCC.toInt()
        val evTornado = 0xFFFF8F00.toInt()
        val evMissing = 0xFFE53935.toInt()
        val battery = intArrayOf(0xFFF5B800.toInt(), 0xFFFF8A00.toInt(), 0xFFE53935.toInt(), 0xFFB71C1C.toInt())
        val rainRamp = intArrayOf(0xFF9CD4FF.toInt(), 0xFF52AAF2.toInt(), 0xFF1F80DA.toInt(), 0xFF1451B4.toInt(), 0xFF6B2DB3.toInt())
        val snowRamp = if (light) intArrayOf(0xFFB9C8DA.toInt(), 0xFF8EA3BD.toInt(), 0xFF5E7899.toInt())
                       else intArrayOf(0xFFFFFFFF.toInt(), 0xFFDCE6F2.toInt(), 0xFFAFC3DB.toInt())
        val mix = intArrayOf(0xFF8ED8CE.toInt(), 0xFF26A69A.toInt())
        val hail = 0xFFD9A8E8.toInt()
    }

    /** Temperature colour ramp (°C): violet → blue → teal → green → yellow → orange → red. */
    private val TEMP_STOPS = floatArrayOf(-20f, -10f, -3f, 3f, 9f, 15f, 21f, 27f, 33f)
    private val TEMP_COLORS = intArrayOf(0xFF6A3FC8.toInt(), 0xFF3F6FE0.toInt(), 0xFF2E9CF0.toInt(), 0xFF2CC3D2.toInt(),
        0xFF4FC27A.toInt(), 0xFFB9CC33.toInt(), 0xFFFFC233.toInt(), 0xFFFF8A2A.toInt(), 0xFFE5392F.toInt())

    fun tempColor(celsius: Float): Int = ramp(TEMP_STOPS, TEMP_COLORS, celsius)

    private fun ramp(stops: FloatArray, colors: IntArray, v: Float): Int {
        if (v <= stops[0]) return colors[0]
        for (i in 1 until stops.size) if (v <= stops[i]) return mix(colors[i - 1], colors[i], (v - stops[i - 1]) / (stops[i] - stops[i - 1]))
        return colors.last()
    }

    fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s) and 255) * (1 - u) + ((b shr s) and 255) * u).roundToInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    private fun alpha(c: Int, a: Float) = (c and 0xFFFFFF) or ((((c ushr 24) * a.coerceIn(0f, 1f)).roundToInt()) shl 24)

    private class Ctx(val c: Canvas, val w: Float, val s: WidgetSettings, val pal: Palette, val dp: Float, val spec: Spec,
                      /** Hour columns span [left, right] so the timeline ends clear the rounded corners. */
                      val left: Float, val right: Float) {
        val total get() = s.totalHours
        val cw get() = right - left
        fun colX(i: Int) = left + i * cw / total
        fun colCenter(i: Int) = left + (i + 0.5f) * cw / total
        val colW get() = cw / total
        val nowX get() = left + cw * s.pastHours / total
        /** Launcher corner radius in rows. */
        val cornerRows get() = spec.cornerPx * h0
        var h0 = 0f
    }

    /*
     * Scratch paints are per thread: the widget (WeatherWorker / RedrawWorker / provider threads) and the
     * app's chart (main thread) render at the same time after a refresh, and one shared Paint let a
     * worker's reset() / style / shader land in the middle of the other's draw (open curves filled
     * with a straight closing edge, fills in another shader's colours).
     */
    private val paintTL = ThreadLocal.withInitial { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val paint: Paint get() = paintTL.get()!!
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    private fun fillP(color: Int) = paint.apply { reset(); isAntiAlias = true; style = Paint.Style.FILL; this.color = color }
    private fun strokeP(color: Int, width: Float) = paint.apply {
        reset(); isAntiAlias = true; style = Paint.Style.STROKE; this.color = color; strokeWidth = width
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    fun render(p: WeatherPayload?, settings: WidgetSettings, spec: Spec, nowMillis: Long = System.currentTimeMillis(),
               tz: TimeZone = TimeZone.getDefault()): Bitmap {
        val L = spec.layout
        val k = spec.heightPx / L.totalRows.toFloat()
        val w = spec.widthPx / k
        val s = settings.copy(forecastHours = WidgetLayout.fitHours(settings.forecastHours, w.toInt()))
        val bmp = Bitmap.createBitmap(spec.widthPx, spec.heightPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (spec.cornerPx > 0f && spec.clipCorners) c.clipPath(Path().apply {
            addRoundRect(RectF(0f, 0f, spec.widthPx.toFloat(), spec.heightPx.toFloat()), spec.cornerPx, spec.cornerPx, Path.Direction.CW) })
        c.scale(k, k)
        val inset = sideInset(spec.cornerPx / k)
        val g = Ctx(c, w, s, Palette(s.lightTheme, spec.accent), spec.density / k, spec, inset, w - inset).also { it.h0 = 1f / k }
        if (spec.scrim) drawScrim(g)
        block(c, 0f, CarbonStyle.DAYLIGHT_H.toFloat(), w) { drawDaylight(g, p, nowMillis, tz, CarbonStyle.DAYLIGHT_H.toFloat()) }
        if (p != null) {
            val win = WidgetRenderer.Window.of(p, s, nowMillis, tz)
            val h = WidgetRenderer.stripHeights(s)
            val precipH = h.precip + L.precipExtra
            val cloudY = (CarbonStyle.DAYLIGHT_H - CarbonStyle.CLOUD_LIFT - CarbonStyle.CLOUD_RAISE).toFloat()
            block(c, cloudY, h.cloud.toFloat(), w) { drawSun(g, p, win, h.cloud.toFloat()); drawClouds(g, p, win, h.cloud) }
            val precipY = CarbonStyle.DAYLIGHT_H + h.cloud - CarbonStyle.CLOUD_LIFT - CarbonStyle.CLOUD_RAISE
            val precipLayerH = precipH + CarbonStyle.CLOUD_LIFT + CarbonStyle.CLOUD_RAISE
            block(c, precipY.toFloat(), precipLayerH.toFloat(), w) { drawRain(g, p, win, precipLayerH.toFloat()) }
            if (h.event > 0) {
                val evY = CarbonStyle.DAYLIGHT_H + h.cloud + precipH - CarbonStyle.EVENT_ICON_LIFT
                val evH = h.event + CarbonStyle.EVENT_ICON_LIFT
                block(c, evY.toFloat() - 2f, evH + 3f, w) { c.translate(0f, 2f); drawEvents(g, p, win, evH.toFloat()) }
            }
            if (L.hasTemp) block(c, L.tempY.toFloat(), L.tempH.toFloat(), w) { drawTemps(g, p, win, L.tempH.toFloat()) }
        }
        return bmp
    }

    // Daylight strip geometry (rows): the bar is centred in DAYLIGHT_H; the
    // widest things on it are the battery (half width 5.7 incl. its knock-out)
    // and the moon/sun with their clear halo (4.2).
    private const val BAR_HALF = 2.1f
    /** Major (midnight / noon) hour tick length at the bottom of the temperature chart, rows. */
    private const val TICK_MAJOR = 4.5f
    /** Temperature-label halo: stroke width (dp) and opacity. */
    const val LABEL_HALO = 2.4f
    const val LABEL_HALO_ALPHA = 0.6f
    private const val MARKER_R = 4.4f
    private const val BATTERY_HALF = 5.8f
    /** Topmost row the timeline inks (marker halo / tick top). */
    private val TIMELINE_TOP get() = CarbonStyle.DAYLIGHT_H / 2f - MARKER_R

    /**
     * Horizontal inset (rows) of the hour columns, so the timeline's round caps,
     * ticks and markers stay inside a launcher corner of radius [cornerRows]:
     * the corner arc cuts `R - sqrt(R² - (R - y)²)` at height y, measured at
     * the timeline's top row; plus the marker radius and half a row of air.
     */
    fun sideInset(cornerRows: Float): Float {
        val r = cornerRows; val y = TIMELINE_TOP
        val cut = if (r > y) r - sqrt(r * r - (r - y) * (r - y)) else 0f
        return cut + MARKER_R + 0.5f
    }

    /** Hour column geometry of a render (px), for touch-to-inspect. */
    class Columns(val leftPx: Float, val colPx: Float, val total: Int, val pastHours: Int, val forecastHours: Int)

    fun columns(spec: Spec, settings: WidgetSettings): Columns {
        val k = spec.heightPx / spec.layout.totalRows.toFloat()
        val w = spec.widthPx / k
        val s = settings.copy(forecastHours = WidgetLayout.fitHours(settings.forecastHours, w.toInt()))
        val inset = sideInset(spec.cornerPx / k)
        return Columns(inset * k, (w - 2 * inset) / s.totalHours * k, s.totalHours, s.pastHours, s.forecastHours)
    }

    /** Ink bounds of the daylight timeline in px (bar, caps, ticks, markers; the now line is inside), for tests. */
    fun timelineBounds(spec: Spec): RectF {
        val k = spec.heightPx / spec.layout.totalRows.toFloat()
        val w = spec.widthPx / k
        val inset = sideInset(spec.cornerPx / k)
        val mid = CarbonStyle.DAYLIGHT_H / 2f
        return RectF((inset - MARKER_R) * k, TIMELINE_TOP * k, (w - inset + MARKER_R) * k, (mid + MARKER_R) * k)
    }

    private inline fun block(c: Canvas, y: Float, h: Float, w: Float, draw: () -> Unit) {
        c.save(); c.translate(0f, y); c.clipRect(0f, 0f, w, h); draw(); c.restore()
    }

    // -------------------------------------------------------------- scrim
    private fun drawScrim(g: Ctx) {
        val L = g.spec.layout
        val col = if (g.pal.light) 0xB3FFFFFF.toInt() else 0x8C000000.toInt()
        val cx = g.w / 2f; val cy = L.timeY + L.timeH / 2f
        val rx = g.w * 0.46f; val ry = L.timeH * 0.62f
        g.c.save()
        g.c.translate(cx, cy); g.c.scale(1f, ry / rx)
        val p = fillP(col)
        p.shader = android.graphics.RadialGradient(0f, 0f, rx, intArrayOf(col, alpha(col, 0.75f), alpha(col, 0.3f), alpha(col, 0f)),
            floatArrayOf(0f, 0.5f, 0.78f, 1f), Shader.TileMode.CLAMP)
        g.c.drawCircle(0f, 0f, rx, p)
        p.shader = null
        g.c.restore()
    }

    // ----------------------------------------------------------- daylight
    private fun drawDaylight(g: Ctx, p: WeatherPayload?, nowMillis: Long, tz: TimeZone, h: Float) {
        val c = g.c
        val lineY = h / 2f
        val half = BAR_HALF
        val nowSec = nowMillis / 1000
        fun dx(diff: Long): Float = g.nowX + diff * g.cw / (3600f * g.total)
        fun visible(x: Float) = x >= g.left - 0.5f && x <= g.right + 0.5f
        fun at(d: Int, hh: Int, mm: Int): Long = Calendar.getInstance(tz).run {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, d); add(Calendar.MINUTE, hh * 60 + mm)
            timeInMillis / 1000
        }
        val layer = c.saveLayer(-4f, -4f, g.w + 4f, h + 4f, null)
        // Bar spans the hour columns exactly; Ctx.left/right already clear the corners.
        val bar = RectF(g.left, lineY - half, g.right, lineY + half)
        c.drawRoundRect(bar, half, half, fillP(alpha(g.pal.night, 0.85f)))
        val rises = ArrayList<Pair<Float, Float>>()
        if (p != null) {
            val dayLen = ((p.sunsetHour - p.sunriseHour) * 60 + (p.sunsetMinute - p.sunriseMinute)).let { if (it < 0) it + 1440 else it }
            for (d in -3..3) {
                val r = at(d, p.sunriseHour, p.sunriseMinute) - nowSec
                rises.add(dx(r) to dx(r + dayLen * 60L))
            }
            val tw = g.colW * 0.55f
            c.save(); c.clipPath(Path().apply { addRoundRect(bar, half, half, Path.Direction.CW) })
            for ((xr, xs) in rises) {
                if (xs < g.left || xr > g.right) continue
                val x0 = xr - tw; val x1 = xs + tw
                val span = x1 - x0
                val t0 = (2 * tw / span).coerceIn(0f, 0.5f)
                val dayC = g.pal.day
                val pp = fillP(dayC)
                pp.shader = LinearGradient(x0, 0f, x1, 0f, intArrayOf(alpha(dayC, 0f), dayC, dayC, alpha(dayC, 0f)),
                    floatArrayOf(0f, t0, 1f - t0, 1f), Shader.TileMode.CLAMP)
                c.drawRect(x0, bar.top, x1, bar.bottom, pp)
                pp.shader = null
            }
            c.restore()
            // Sunrise / sunset ticks.
            for ((xr, xs) in rises) for (x in floatArrayOf(xr, xs)) if (visible(x))
                c.drawLine(x, lineY - 4.2f, x, lineY + 4.2f, strokeP(alpha(g.pal.ink2, 0.9f), 1.3f * g.dp))
        }
        // Noon sun and midnight moon.
        for (d in -3..3) {
            val xn = dx(at(d, 12, 0) - nowSec)
            if (visible(xn)) sunDot(g, xn, lineY)
            val midn = at(d + 1, 0, 0)
            val xm = dx(midn - nowSec)
            if (visible(xm)) moon(g, xm, lineY, WidgetRenderer.moonPhase(midn, tz))
        }
        drawBattery(g, nowSec, lineY, ::dx)
        c.restoreToCount(layer)
        if (g.s.needle == 0 || g.s.needle == 1) {
            c.drawLine(g.nowX, 0.8f, g.nowX, lineY + 6.5f, strokeP(g.pal.needle, 2.2f * g.dp))
        }
    }

    private fun sunDot(g: Ctx, x: Float, y: Float) {
        val r = 2.9f
        g.c.drawCircle(x, y, r + 1.1f, clear)
        g.c.drawCircle(x, y, r, fillP(g.pal.sun))
        g.c.drawCircle(x, y, r, strokeP(g.pal.sunRing, 1f * g.dp))
    }

    /** Moon phase 0–7 (0 new, 4 full; 1–3 waxing lit on the right). */
    private fun moon(g: Ctx, x: Float, y: Float, phase: Int) {
        val c = g.c
        val r = 3.1f
        c.drawCircle(x, y, r + 1.1f, clear)
        c.drawCircle(x, y, r, fillP(g.pal.moonDark))
        if (phase != 0) {
            val lit = Path()
            if (phase == 4) lit.addCircle(x, y, r, Path.Direction.CW)
            else {
                val theta = phase / 8.0 * 2 * PI
                val rx = (r * abs(cos(theta))).toFloat()
                val waxing = phase in 1..3
                val half = Path().apply {
                    addArc(RectF(x - r, y - r, x + r, y + r), if (waxing) -90f else 90f, 180f); close()
                }
                val ell = Path().apply { addOval(RectF(x - rx, y - r, x + rx, y + r), Path.Direction.CW) }
                val crescent = phase == 1 || phase == 7
                lit.op(half, ell, if (crescent) Path.Op.DIFFERENCE else Path.Op.UNION)
                if (!crescent) lit.op(Path().apply { addCircle(x, y, r, Path.Direction.CW) }, Path.Op.INTERSECT)
            }
            c.drawPath(lit, fillP(g.pal.moonLit))
        }
        c.drawCircle(x, y, r, strokeP(g.pal.moonRim, 0.9f * g.dp))
    }

    private fun drawBattery(g: Ctx, nowSec: Long, lineY: Float, dx: (Long) -> Float) {
        val b = g.spec.battery ?: return
        if (b.charging || b.percent >= 100 || b.anchorSec <= 0) return
        val past = g.s.pastHours * 3600L; val fut = g.s.forecastHours * 3600L
        val anchorOff = b.anchorSec - nowSec
        val levels = g.s.batteryLevels
        val marks = listOf(Triple(20, 0, 0.5f), Triple(10, 1, 0.25f), Triple(5, 2, 0.12f), Triple(0, 3, 0f))
        for ((m, ci, frac) in marks) {
            if (m !in levels) continue
            val eta = anchorOff + (b.anchorPercent - m).toLong() * b.secsPerPct
            if (eta < -past || eta > fut) continue
            val x = dx(eta).coerceIn(g.left - MARKER_R + BATTERY_HALF, g.right + MARKER_R - BATTERY_HALF)
            val col = g.pal.battery[ci]
            val bw = 8.6f; val bh = 5.4f
            val body = RectF(x - bw / 2, lineY - bh / 2, x + bw / 2 - 1f, lineY + bh / 2)
            g.c.drawRoundRect(RectF(body.left - 1.4f, body.top - 1.4f, body.right + 2.4f, body.bottom + 1.4f), 2.4f, 2.4f, clear)
            g.c.drawRoundRect(body, 1.4f, 1.4f, fillP(alpha(g.pal.faint, 0.9f)))
            g.c.drawRoundRect(body, 1.4f, 1.4f, strokeP(col, 1.1f * g.dp))
            g.c.drawRoundRect(RectF(body.right + 0.2f, lineY - 1.3f, body.right + 1.4f, lineY + 1.3f), 0.5f, 0.5f, fillP(col))
            if (frac > 0f) g.c.drawRoundRect(RectF(body.left + 1.2f, body.top + 1.2f, body.left + 1.2f + (body.width() - 2.4f) * frac,
                body.bottom - 1.2f), 0.6f, 0.6f, fillP(col))
        }
    }

    // ----------------------------------------------------------------- sun
    /**
     * Sun rays hanging from the top of the cloud strip (cloud_layer.c rays):
     * one soft ray per sunny hour, length and strength by sunlight
     * (prv_ray_intensity_for_cover), fading out along its length, over a
     * faint warm halo.
     */
    private fun drawSun(g: Ctx, p: WeatherPayload, win: WidgetRenderer.Window, h: Float) {
        val s = g.s
        if (s.sunSensitivity >= 4) return
        val (minI, full) = CarbonStyle.SUN_THRESHOLDS[s.sunSensitivity].let { it[0] to it[1] }
        val span = if (h > 2) h - 1 else 4f
        val sun = g.pal.sun
        for (i in 0 until g.total) {
            val sl = win.startSlot + i
            val wm2 = p.shortwaveWm2[sl]
            val base = if (wm2 >= full) 255 else wm2 * 255 / full
            val intensity = base * max(0, 100 - p.cloudCover[sl]) / 100
            if (intensity < minI) continue
            val len = (3f + intensity * (span - 3f) / 255f).coerceIn(3f, span) + 1.5f
            val a = 0.45f + 0.5f * intensity / 255f
            val cx = g.colCenter(i)
            val halo = fillP(sun)
            halo.shader = LinearGradient(0f, -1f, 0f, len, alpha(sun, a * 0.14f), alpha(sun, 0f), Shader.TileMode.CLAMP)
            g.c.drawRect(cx - g.colW * 0.5f, -1f, cx + g.colW * 0.5f, len, halo)
            val rw = max(g.colW * 0.24f, 1.6f * g.dp)
            val ray = fillP(sun)
            ray.shader = LinearGradient(0f, -1f, 0f, len, intArrayOf(alpha(sun, a), alpha(sun, a * 0.6f), alpha(sun, 0f)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            g.c.drawRoundRect(RectF(cx - rw / 2, -rw, cx + rw / 2, len), rw / 2, rw / 2, ray)
            halo.shader = null; ray.shader = null
        }
    }

    // -------------------------------------------------------------- clouds
    private const val LOW = 1
    private const val MID = 2
    private const val HIGH = 4

    private fun level(cover: Int, th: IntArray) = when {
        cover < th[0] -> 0
        cover < th[1] -> 1
        cover < th[2] -> 2
        else -> 3
    }

    private fun drawClouds(g: Ctx, p: WeatherPayload, win: WidgetRenderer.Window, h: Int) {
        val s = g.s
        if (s.cloudSensitivity >= 4) return
        val th = CarbonStyle.CLOUD_THRESHOLDS[s.cloudSensitivity]
        val layer = g.c.saveLayer(-2f, -6f, g.w + 2f, h + 6f, null)
        if (s.cloudSplit) {
            val (cyHigh, cyMid, cyLow) = WidgetRenderer.splitRows(h)
            val rMax = min(4.2f, (cyMid - cyHigh) * 0.62f)
            band(g, p, win, p.cloudHigh, cyHigh.toFloat(), rMax * 0.92f, th, HIGH, g.pal.cloudHigh)
            band(g, p, win, p.cloudMid, cyMid.toFloat(), rMax, th, MID, g.pal.cloudMid)
            band(g, p, win, p.cloudLow, cyLow.toFloat(), rMax * 1.05f, th, LOW, g.pal.cloudLow)
        } else {
            val cy = (h / 2).coerceAtMost(h - 5).coerceAtLeast(4).toFloat()
            band(g, p, win, p.cloudCover, cy, min(5.8f, h * 0.4f), th, 0, g.pal.cloudTotal)
        }
        g.c.restoreToCount(layer)
    }

    /**
     * One smooth band per layer: for each run of cloudy hours a soft rounded
     * shape whose height follows the cover (monotone cubic edges), coloured
     * per hour (lighter when thin, darker when thick, slate when heavy) with
     * opacity by cover, and a soft top highlight.
     */
    private fun band(g: Ctx, p: WeatherPayload, win: WidgetRenderer.Window, cover: IntArray, cy: Float, rMax: Float,
                     th: IntArray, layer: Int, tone: Int) {
        val c = g.c
        val n = g.total
        val lv = IntArray(n) { level(cover[win.startSlot + it], th) }
        val runs = ArrayList<IntRange>()
        var i = 0
        while (i < n) {
            if (lv[i] == 0) { i++; continue }
            val st = i
            while (i < n && lv[i] > 0) i++
            runs.add(st until i)
        }
        if (runs.isEmpty()) return
        fun r(i: Int) = rMax * (0.5f + 0.5f * cover[win.startSlot + i].coerceIn(0, 100) / 100f)
        fun colorAt(i: Int): Int {
            val sl = win.startSlot + i
            val code = p.weatherCode[sl]
            var heavy = PrecipMap.cloudHeavy(code, p.haveMm, p.precipMmTenths[sl])
            if (layer != 0) {
                var drawn = 0
                if (level(p.cloudLow[sl], th) > 0) drawn = drawn or LOW
                if (level(p.cloudMid[sl], th) > 0) drawn = drawn or MID
                if (level(p.cloudHigh[sl], th) > 0) drawn = drawn or HIGH
                val mask = when { !heavy -> 0; code in 95..99 -> drawn; drawn and LOW != 0 -> LOW; drawn and MID != 0 -> MID; else -> 0 }
                heavy = mask and layer != 0
            }
            val thick = if (g.s.cloudThick) p.cloudThick[sl] else 2
            var col = if (heavy) g.pal.cloudHeavy else tone
            if (!heavy) {
                if (lv[i] == 1 || thick == 1) col = mix(col, g.pal.faint, 0.3f)
                if (thick == 3) col = mix(col, g.pal.cloudHeavy, 0.4f)
            }
            return alpha(col, 0.62f + 0.38f * cover[sl].coerceIn(0, 100) / 100f)
        }
        for (run in runs) {
            val shape = runShape(g, run, cy, ::r)
            // A thin gap above the band below so the Split layers read as three.
            if (layer == MID || layer == LOW) c.drawPath(shape, strokeP(Color.BLACK, 1.6f).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
            val xs0 = g.colCenter(run.first) - g.colW; val xs1 = g.colCenter(run.last) + g.colW
            val pos = ArrayList<Float>(); val cols = ArrayList<Int>()
            // Per-hour colour with short soft transitions (crisp hours, no smear).
            for (j in run) {
                val cj = colorAt(j)
                for (dxf in floatArrayOf(-0.22f, 0.22f)) { pos.add((g.colCenter(j) + dxf * g.colW - xs0) / (xs1 - xs0)); cols.add(cj) }
            }
            val fp = fillP(Color.WHITE)
            fp.shader = if (cols.size == 1) null else LinearGradient(xs0, 0f, xs1, 0f, cols.toIntArray(), pos.toFloatArray(), Shader.TileMode.CLAMP)
            if (cols.size == 1) fp.color = cols[0]
            c.drawPath(shape, fp)
            fp.shader = null
            // Soft highlight on top, shade underneath.
            val hp = fillP(Color.WHITE)
            val hi = if (g.pal.light) 0x66FFFFFF else 0x40FFFFFF
            val lo = if (g.pal.light) 0x1A000000 else 0x33000000
            hp.shader = LinearGradient(0f, cy - rMax, 0f, cy + rMax * 0.62f, intArrayOf(hi, 0x00FFFFFF, lo), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(shape, hp)
            hp.shader = null
        }
    }

    /**
     * Closed soft shape for a run of hours: a billowy cubic top (a crest per
     * hour, gentle dips between), a flatter bottom, and round end caps.
     */
    private fun runShape(g: Ctx, run: IntRange, cy: Float, r: (Int) -> Float): Path {
        val xs = ArrayList<Float>(); val top = ArrayList<Float>(); val bot = ArrayList<Float>()
        val r0 = r(run.first); val r1 = r(run.last)
        val xL = g.colCenter(run.first) - g.colW * 0.42f
        val xR = g.colCenter(run.last) + g.colW * 0.42f
        xs.add(xL); top.add(cy - r0 * 0.3f); bot.add(cy + r0 * 0.4f)
        for (j in run) {
            xs.add(g.colCenter(j)); top.add(cy - r(j)); bot.add(cy + r(j) * 0.58f)
            if (j < run.last) {
                xs.add(g.colCenter(j) + g.colW / 2f); top.add(cy - min(r(j), r(j + 1)) * 0.74f); bot.add(cy + (r(j) + r(j + 1)) * 0.29f)
            }
        }
        xs.add(xR); top.add(cy - r1 * 0.3f); bot.add(cy + r1 * 0.4f)
        val xa = xs.toFloatArray()
        val band = Path()
        appendSmooth(band, xa, top.toFloatArray(), moveFirst = true)
        appendSmooth(band, xa.reversedArray(), bot.toFloatArray().reversedArray(), moveFirst = false)
        band.close()
        val caps = Path().apply {
            addCircle(xL, cy + r0 * 0.05f, r0 * 0.36f + 0.15f * r0, Path.Direction.CW)
            addCircle(xR, cy + r1 * 0.05f, r1 * 0.36f + 0.15f * r1, Path.Direction.CW)
        }
        return Path().apply { op(band, caps, Path.Op.UNION) }
    }

    // ---------------------------------------------------------------- rain
    private fun rainColor(g: Ctx, kind: Int, tenths: Int): Int = when (kind) {
        PrecipMap.KIND_SNOW -> ramp(floatArrayOf(2f, 15f, 40f), g.pal.snowRamp, tenths.toFloat())
        PrecipMap.KIND_MIX -> ramp(floatArrayOf(2f, 30f), g.pal.mix, tenths.toFloat())
        PrecipMap.KIND_HAIL -> g.pal.hail
        else -> ramp(floatArrayOf(1f, 10f, 40f, 100f, 250f), g.pal.rainRamp, tenths.toFloat())
    }

    private fun drawRain(g: Ctx, p: WeatherPayload, win: WidgetRenderer.Window, layerH: Float) {
        val c = g.c
        val s = g.s
        val minProb = CarbonStyle.PRECIP_MIN_PROB[s.precipSensitivity]
        val amount = p.haveMm && (s.rainHeight == 1 || s.rainHeight == 2)
        val lineMode = amount && s.rainHeight == 2
        val span = layerH - 2f
        val gap = max(g.colW * 0.13f, 0.8f * g.dp)
        for (i in 0 until g.total) {
            val sl = win.startSlot + i
            val prob = p.precipProb[sl]
            if (prob < minProb) continue
            val tenths = p.precipMmTenths[sl]
            val barH = if (amount) {
                if (tenths <= 0) continue
                if (tenths >= CarbonStyle.AMOUNT_FULL_TENTHS) span else max(1.4f, span * sqrt(tenths / CarbonStyle.AMOUNT_FULL_TENTHS.toFloat()))
            } else max(1.4f, (prob - minProb).toFloat() / max(1, 100 - minProb) * span)
            val code = p.weatherCode[sl]
            val kind = PrecipMap.kind(code)
            val t = if (p.haveMm) tenths else if (PrecipMap.isHeavy(code, false, 0)) 60 else 12
            var col = rainColor(g, kind, t)
            if (amount && !lineMode) col = alpha(col, 0.3f + 0.7f * prob / 100f)
            val x0 = g.colX(i) + gap / 2; val x1 = g.colX(i + 1) - gap / 2
            val r = min((x1 - x0) * 0.42f, min(barH, 3.2f))
            val rect = RectF(x0, 0f, x1, barH)
            val path = Path().apply { addRoundRect(rect, floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r), Path.Direction.CW) }
            c.drawPath(path, fillP(col))
            if (kind == PrecipMap.KIND_HAIL) c.drawPath(path, strokeP(g.pal.hailRing, 1f * g.dp))
        }
        if (lineMode) {
            val pts = (0 until g.total).map { i ->
                val sl = win.startSlot + i
                Triple(g.colCenter(i), 0.8f + min(100, p.precipProb[sl]) * (span - 1f) / 100f, p.precipProb[sl] >= minProb)
            }
            val sp = strokeP(g.pal.chance, 1.5f * g.dp)
            var i = 0
            while (i < pts.size) {
                if (!pts[i].third && !(i + 1 < pts.size && pts[i + 1].third)) { i++; continue }
                val start = i
                while (i + 1 < pts.size && (pts[i].third || pts[i + 1].third)) i++
                val seg = pts.subList(start, i + 1)
                if (seg.size > 1) c.drawPath(smooth(seg.map { it.first }.toFloatArray(), seg.map { it.second }.toFloatArray()), sp)
                i++
            }
            val dot = 1.25f * g.dp + 0.4f
            for ((x, y, on) in pts) if (on) {
                c.drawCircle(x, y, dot + 0.6f * g.dp, fillP(alpha(g.pal.faint, 0.85f)))
                c.drawCircle(x, y, dot, fillP(g.pal.chance))
            }
        }
    }

    /** Monotone cubic (Fritsch–Carlson) path through the points: smooth, no overshoot. */
    fun smooth(xs: FloatArray, ys: FloatArray): Path = Path().also { appendSmooth(it, xs, ys, moveFirst = true) }

    /** Appends the monotone cubic through the points (x strictly monotone, either direction). */
    fun appendSmooth(path: Path, xs: FloatArray, ys: FloatArray, moveFirst: Boolean) {
        val n = xs.size
        if (n == 0) return
        if (moveFirst) path.moveTo(xs[0], ys[0]) else path.lineTo(xs[0], ys[0])
        if (n == 1) return
        val d = FloatArray(n - 1) { val dx = xs[it + 1] - xs[it]; (ys[it + 1] - ys[it]) / (if (abs(dx) < 1e-4f) 1e-4f else dx) }
        val m = FloatArray(n)
        m[0] = d[0]; m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        for (i in 0 until n - 1) {
            if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
            val a = m[i] / d[i]; val b = m[i + 1] / d[i]
            val h = a * a + b * b
            if (h > 9f) { val t = 3f / sqrt(h); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i] }
        }
        for (i in 0 until n - 1) {
            val dx = (xs[i + 1] - xs[i]) / 3f
            path.cubicTo(xs[i] + dx, ys[i] + m[i] * dx, xs[i + 1] - dx, ys[i + 1] - m[i + 1] * dx, xs[i + 1], ys[i + 1])
        }
    }

    // -------------------------------------------------------------- events
    private fun evColor(g: Ctx, k: WidgetRenderer.EventKind) = when (k) {
        WidgetRenderer.EventKind.FOG -> g.pal.evFog
        WidgetRenderer.EventKind.WIND -> g.pal.evWind
        WidgetRenderer.EventKind.STORM -> g.pal.evStorm
        WidgetRenderer.EventKind.SNOW -> g.pal.evSnow
        WidgetRenderer.EventKind.TORNADO -> g.pal.evTornado
        else -> g.pal.evMissing
    }

    private fun drawEvents(g: Ctx, p: WeatherPayload, win: WidgetRenderer.Window, layerH: Float) {
        if (g.s.eventDisplay == 2) return
        val c = g.c
        val spans = WidgetRenderer.eventSpans(p, g.s, win.startSlot, win.hoursRemaining)
        val cy = (layerH - CarbonStyle.EVENT_ICON_LIFT) / 2f + CarbonStyle.EVENT_ICON_LIFT
        val size = EVENT_ICON_ROWS
        for (sp in spans.asReversed()) {
            val xs = g.colCenter(sp.start); val xe = g.colCenter(sp.end)
            val ix = (xs + xe) / 2f
            val col = evColor(g, sp.kind)
            val gapX = size * 0.75f
            if (g.s.eventDisplay == 0) {
                val lp = strokeP(alpha(col, 0.85f), 1.2f * g.dp)
                if (sp.kind == WidgetRenderer.EventKind.FOG || sp.kind == WidgetRenderer.EventKind.MISSING)
                    lp.pathEffect = DashPathEffect(floatArrayOf(2.2f, 2f), 0f)
                if (xs < ix - gapX) { c.drawLine(xs, cy, ix - gapX, cy, lp); c.drawLine(xs, cy - 2.2f, xs, cy + 2.2f, lp) }
                if (xe > ix + gapX) { c.drawLine(ix + gapX, cy, xe, cy, lp); c.drawLine(xe, cy - 2.2f, xe, cy + 2.2f, lp) }
                lp.pathEffect = null
            }
            val iy = cy - CarbonStyle.EVENT_ICON_LIFT
            icon(g, sp.kind, ix, iy, size, col)
        }
    }

    /** Event icon height in rows (was 10.5; ~16 % smaller so it doesn't crowd the rain bars). */
    const val EVENT_ICON_ROWS = 8.8f

    /** Vector event icons, [size] rows tall, centred on (cx, cy). */
    fun icon(c: Canvas, kind: WidgetRenderer.EventKind, cx: Float, cy: Float, size: Float, col: Int, dpRows: Float) {
        val sw = max(size * 0.13f, 0.9f * dpRows)
        when (kind) {
            WidgetRenderer.EventKind.STORM -> {
                val h = size * 1.1f; val w = h * 0.62f
                val x0 = cx - w / 2; val y0 = cy - h / 2
                val pts = floatArrayOf(0.62f, 0f, 0.08f, 0.56f, 0.45f, 0.56f, 0.3f, 1f, 0.92f, 0.4f, 0.55f, 0.4f, 0.78f, 0f)
                val path = Path()
                for (i in pts.indices step 2) { val x = x0 + pts[i] * w; val y = y0 + pts[i + 1] * h; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                path.close()
                c.drawPath(path, strokeP(alpha(Color.BLACK, 0.18f), sw * 0.9f))
                c.drawPath(path, fillP(col))
            }
            WidgetRenderer.EventKind.WIND -> {
                // event_layer.c s_mask_wind (12x10, origin -6,-4): Lucide "wind",
                // two gusts. Upper: row 3 from x0 to x10, curling up the right
                // (x11, rows 1-2) and back left along row 0 to x7. Lower: row 6
                // to x8, curling down (x9, rows 7-8) and back along row 9 to x6.
                // Traced through the pixel centres, scaled to the icon size.
                val sc = size / 10f
                fun X(mx: Float) = cx + (mx - 6f) * sc
                fun Y(my: Float) = cy + (my - 4f) * sc
                val path = Path().apply {
                    moveTo(X(0.5f), Y(3.5f)); lineTo(X(9.6f), Y(3.5f))
                    cubicTo(X(11.9f), Y(3.5f), X(11.9f), Y(0.5f), X(9.6f), Y(0.5f)); lineTo(X(7.5f), Y(0.5f))
                    moveTo(X(0.5f), Y(6.5f)); lineTo(X(7.6f), Y(6.5f))
                    cubicTo(X(9.9f), Y(6.5f), X(9.9f), Y(9.5f), X(7.6f), Y(9.5f)); lineTo(X(6.5f), Y(9.5f))
                }
                c.drawPath(path, strokeP(col, max(sc * 1.15f, 0.9f * dpRows)))
            }
            WidgetRenderer.EventKind.SNOW -> {
                val p = strokeP(col, sw * 0.9f)
                val r = size * 0.5f
                for (a in 0 until 3) {
                    val ang = a * PI / 3 + PI / 2
                    val dx = (cos(ang) * r).toFloat(); val dy = (sin(ang) * r).toFloat()
                    c.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, p)
                    for (sgn in intArrayOf(-1, 1)) {
                        val bx = cx + sgn * dx * 0.62f; val by = cy + sgn * dy * 0.62f
                        for (off in doubleArrayOf(-0.6, 0.6)) {
                            val a2 = ang + (if (sgn > 0) 0.0 else PI) + off
                            c.drawLine(bx, by, bx + (cos(a2) * r * 0.32f).toFloat(), by + (sin(a2) * r * 0.32f).toFloat(), p)
                        }
                    }
                }
            }
            WidgetRenderer.EventKind.FOG -> {
                val p = strokeP(col, sw)
                val w = size * 1.2f; val x0 = cx - w / 2
                for ((j, y) in floatArrayOf(cy - size * 0.28f, cy + size * 0.06f, cy + size * 0.4f).withIndex()) {
                    val path = Path()
                    val n = 16
                    val shift = if (j == 1) 0.8f else 0f
                    val ww = if (j == 1) w * 0.8f else w
                    for (s in 0..n) {
                        val x = x0 + shift + ww * s / n
                        val yy = y + (sin(s / n.toDouble() * 2.0 * PI) * size * 0.08f).toFloat()
                        if (s == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
                    }
                    c.drawPath(path, p)
                }
            }
            WidgetRenderer.EventKind.TORNADO -> {
                val p = strokeP(col, sw)
                val widths = floatArrayOf(1.1f, 0.85f, 0.6f, 0.38f, 0.2f)
                for ((j, f) in widths.withIndex()) {
                    val y = cy - size * 0.48f + j * size * 0.24f
                    val off = (j - 2) * size * 0.05f
                    c.drawLine(cx + off - size * f / 2, y, cx + off + size * f / 2, y, p)
                }
            }
            WidgetRenderer.EventKind.MISSING -> {
                val r = size * 0.5f
                c.drawCircle(cx, cy, r, strokeP(col, sw))
                c.drawLine(cx, cy - r * 0.5f, cx, cy + r * 0.12f, strokeP(col, sw))
                c.drawCircle(cx, cy + r * 0.48f, sw * 0.65f, fillP(col))
            }
            WidgetRenderer.EventKind.NONE -> {}
        }
    }

    private fun icon(g: Ctx, kind: WidgetRenderer.EventKind, cx: Float, cy: Float, size: Float, col: Int) =
        icon(g.c, kind, cx, cy, size, col, g.dp)

    // -------------------------------------------------------- temperatures
    private fun drawTemps(g: Ctx, p: WeatherPayload, win: WidgetRenderer.Window, lh: Float) {
        val c = g.c
        val s = g.s
        val total = g.total
        val nowCol = s.pastHours
        val rem = win.hoursRemaining
        val pts = FloatArray(total + 1); val apt = FloatArray(total + 1)
        val cur = p.currentTemp
        for (i in 0..total) {
            val sl = win.startSlot + i
            pts[i] = when {
                i == nowCol && cur != null -> cur.toFloat()
                i > 0 && i >= rem -> pts[i - 1]
                else -> p.tempHourly[sl].toFloat()
            }
            apt[i] = if (i > 0 && i >= rem) apt[i - 1] else p.apparentHourly[sl].toFloat()
        }
        val last = if (rem in 1..total) rem - 1 else total
        var hi = pts[0]; var lo = pts[0]
        var tMin = min(pts[0], apt[0]); var tMax = max(pts[0], apt[0])
        for (i in 1..last) {
            hi = max(hi, pts[i]); lo = min(lo, pts[i])
            tMin = minOf(tMin, pts[i], apt[i]); tMax = maxOf(tMax, pts[i], apt[i])
        }
        val tRange = max(2f, tMax - tMin)
        val mid = (tMax + tMin) / 2f
        tMin = mid - tRange / 2f; tMax = mid + tRange / 2f
        val pad = CarbonStyle.TEMP_PAD + 1.5f
        val graphH = lh - pad - 4f
        fun y(t: Float) = pad + graphH - (t - tMin) * graphH / tRange
        // Celsius for colour (pts are in the user's unit).
        fun cel(t: Float) = if (s.celsius) t else (t - 32f) * 5f / 9f
        val xs = FloatArray(last + 1) { g.colX(it) }
        val ys = FloatArray(last + 1) { y(pts[it]) }
        val curve = smooth(xs, ys)
        // Vertical colour gradient by temperature.
        val stops = 6
        val gc = IntArray(stops) { tempColor(cel(tMax - (tMax - tMin) * it / (stops - 1f))) }
        val gp = FloatArray(stops) { it / (stops - 1f) }
        val y0 = y(tMax); val y1 = y(tMin)
        val xNow = g.nowX
        if (s.infill != 3) {
            val fill = Path(curve).apply { lineTo(xs[last], lh); lineTo(xs[0], lh); close() }
            val fp = fillP(Color.WHITE)
            fp.shader = LinearGradient(0f, y0, 0f, lh, gc.map { alpha(it, 0.55f) }.toIntArray().let { a ->
                intArrayOf(*a, alpha(gc.last(), 0.12f)) }, FloatArray(stops + 1) { if (it < stops) gp[it] * (y1 - y0) / (lh - y0) else 1f },
                Shader.TileMode.CLAMP)
            // Past hours dimmer (infill 0 future, 1 past, 2 both).
            c.save(); c.clipRect(xNow, 0f, g.w, lh)
            if (s.infill == 0 || s.infill == 2) c.drawPath(fill, fp)
            c.restore()
            c.save(); c.clipRect(0f, 0f, xNow, lh)
            fp.alpha = if (s.infill == 1 || s.infill == 2) 255 else 90
            c.drawPath(fill, fp)
            c.restore()
            fp.shader = null
        }
        // Feels-like: thin dashed line.
        val ap = strokeP(alpha(g.pal.ink2, 0.75f), 1f * g.dp)
        ap.pathEffect = DashPathEffect(floatArrayOf(2.4f, 2f), 0f)
        c.drawPath(smooth(xs, FloatArray(last + 1) { y(apt[it]) }), ap)
        ap.pathEffect = null
        // Actual temperature: crisp gradient stroke.
        val lp = strokeP(Color.WHITE, 2.1f * g.dp + 0.3f)
        lp.shader = LinearGradient(0f, y0, 0f, y1, gc, gp, Shader.TileMode.CLAMP)
        c.drawPath(curve, lp)
        lp.shader = null
        // Hour ticks (midnight / noon longer).
        val baseHour = ((win.currentHour - nowCol) % 24 + 240) % 24
        val step = if (total > 36) 3 else 1
        val tickXs = ArrayList<Float>(); val tickLens = ArrayList<Float>()
        for (i in 0..total step step) {
            val hv = (baseHour + i) % 24
            val major = hv == 0 || hv == 12
            val len = if (major) TICK_MAJOR else 2.4f
            val x = g.colX(i)
            c.drawLine(x, lh - len, x, lh, strokeP(alpha(if (major) g.pal.ink else g.pal.ink2, if (major) 0.8f else 0.5f), 1f * g.dp))
            tickXs.add(x); tickLens.add(len)
        }
        // Now line (no dot on the curve).
        val nowT = cur?.toFloat() ?: pts[nowCol]
        if (s.needle == 0 || s.needle == 2) c.drawLine(xNow, 0f, xNow, lh, strokeP(g.pal.needle, 1.6f * g.dp))
        // Labels: high (top), current (middle, bold), low (bottom), in the chosen text font,
        // stacked by their ink bounds with equal gaps and right-aligned on the digits.
        val sm = if (lh > 70f) 15f else 13.5f
        val md = if (lh > 70f) 23f else 20f
        val tf = g.spec.textTypeface
        // Watch label column, but always clear of the now line (the ° hangs right of the digits).
        val degW = labelPaint(tf, md, true).measureText("°")
        val right = min(g.left + 3f + CarbonStyle.TEMP_LABEL_W, xNow - 2.6f - 1f * g.dp - 2f - degW)
        val texts = listOf("${hi.roundToInt()}", "${nowT.roundToInt()}", "${lo.roundToInt()}")
        val paints = listOf(labelPaint(tf, sm, false), labelPaint(tf, md, true), labelPaint(tf, sm, false))
        // Full chart height: the same small ink padding (4 %, ≥ 1.5 dp) under the top edge and above
        // the tallest hour tick under the label column (the ticks elsewhere don't constrain it).
        val bottom = TempLabels.bandBottom(lh, tickXs, tickLens, right - TempLabels.columnWidth(texts, paints) - 1f, right + degW + 1f)
        val pos = TempLabels.place(texts, paints, right, 0f, bottom, pad = TempLabels.pad(lh, 1.5f * g.dp), minGap = 1.6f)
        for ((i, pl) in pos.withIndex()) label(g, pl.text + "°", pl.x, pl.baseline, if (i == 1) md else sm, i == 1, if (i == 1) g.pal.ink else g.pal.ink2)
    }

    private val textTL = ThreadLocal.withInitial { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val text: Paint get() = textTL.get()!!

    private fun labelPaint(tf: Typeface, size: Float, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = if (bold) Typeface.create(tf, Typeface.BOLD) else tf; textSize = size
    }

    /** Label drawn from [x] (left-aligned origin from [TempLabels]) at [baseline]; the ° hangs right of the digits. */
    private fun label(g: Ctx, s: String, x: Float, baseline: Float, size: Float, bold: Boolean, color: Int) {
        val tf = g.spec.textTypeface
        text.reset(); text.isAntiAlias = true
        text.typeface = if (bold) Typeface.create(tf, Typeface.BOLD) else tf
        text.textSize = size
        text.color = color
        text.textAlign = Paint.Align.LEFT
        // Soft halo in the background tone first, so the label stays legible where the curve runs under it.
        text.style = Paint.Style.STROKE; text.strokeJoin = Paint.Join.ROUND
        text.strokeWidth = LABEL_HALO * g.dp
        text.color = alpha(g.pal.halo, LABEL_HALO_ALPHA)
        g.c.drawText(s, x, baseline, text)
        text.style = Paint.Style.FILL; text.color = color
        if (g.spec.textShadow) text.setShadowLayer(1.6f * g.dp, 0f, 0.5f * g.dp, g.pal.shadow)
        g.c.drawText(s, x, baseline, text)
        text.clearShadowLayer()
    }
}
