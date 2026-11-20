package com.nilsreichhard.carbonion.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the widget bitmap on the watch's row grid (emery rows), pixel for
 * pixel like the C layers: daylight track with noon / moon / battery
 * markers and the now needle (daylight_layer.c), clouds Total or Split with
 * thickness and sun rays (cloud_layer.c), rain (precip_layer.c), weather
 * events (event_layer.c), the meteogram (temp_layer.c), and the next alarm
 * in the clock's right flank (the watch's step-count slot). City, clock and
 * date are RemoteViews text on top (see WidgetUpdater).
 *
 * Height comes from the [WidgetLayout] (228 rows by default), width from the
 * widget aspect. The bitmap is transparent; pixels the watch paints in its
 * background colour are punched out so the widget background shows through.
 */
object WidgetRenderer {

    /** Phone battery mapped onto the watch's battery markers. */
    data class Battery(val percent: Int, val charging: Boolean, val anchorSec: Long, val anchorPercent: Int, val secsPerPct: Long)

    class Spec(
        /** Bitmap width in watch rows. */
        val widthRows: Int = 320,
        /** Integer pixels per watch row. */
        val scale: Int = CarbonStyle.RENDER_SCALE.toInt(),
        val layout: WidgetLayout = WidgetLayout.DEFAULT,
        /** Clock text: sample (widest digits for the pattern) and size in rows. */
        val clockSample: String = "00:00",
        val clockRows: Float = CarbonStyle.CLOCK_TEXT_ROWS,
        val battery: Battery? = null,
        /** Clock font of the live clock above the bitmap. */
        val clockTypeface: Typeface = CLOCK_TYPEFACE,
    ) {
        companion object {
            /** Spec for a widget of w×h dp at [density] (aspect kept). */
            fun forWidget(wDp: Int, hDp: Int, density: Float, layout: WidgetLayout,
                          clockSample: String, clockRows: Float, battery: Battery?,
                          clockTypeface: Typeface = CLOCK_TYPEFACE): Spec {
                val rows = layout.totalRows
                val widthRows = max(100, (rows * wDp.toFloat() / max(1, hDp)).roundToInt())
                val scale = (hDp * density / rows).roundToInt().coerceIn(1, 4)
                return Spec(widthRows, scale, layout, clockSample, clockRows, battery, clockTypeface)
            }
        }
    }

    /** IcoMoon (resources/fonts, the watch's icon font); set by the app. */
    @Volatile var iconTypeface: Typeface? = null

    fun render(
        p: WeatherPayload?,
        settings: WidgetSettings,
        spec: Spec = Spec(),
        nowMillis: Long = System.currentTimeMillis(),
        tz: TimeZone = TimeZone.getDefault(),
    ): Bitmap {
        val w = spec.widthRows
        val L = spec.layout
        // Narrow widgets show fewer hours instead of squashing the columns.
        val s = settings.copy(forecastHours = WidgetLayout.fitHours(settings.forecastHours, w))
        val bmp = Bitmap.createBitmap(w * spec.scale, L.totalRows * spec.scale, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(spec.scale.toFloat(), spec.scale.toFloat())
        val g = Ctx(c, w, s, CarbonStyle.Palette(s.lightTheme))
        // The watch adds the daylight layer first; clouds overlap its last rows.
        block(c, 0, CarbonStyle.DAYLIGHT_H, w) { drawDaylight(g, p, nowMillis, tz, CarbonStyle.DAYLIGHT_H, spec.battery) }
        if (p != null) {
            val win = Window.of(p, s, nowMillis, tz)
            val h = stripHeights(s)
            val precipH = h.precip + L.precipExtra
            val cloudY = CarbonStyle.DAYLIGHT_H - CarbonStyle.CLOUD_LIFT - CarbonStyle.CLOUD_RAISE
            block(c, cloudY, h.cloud, w) { drawClouds(g, p, win, h.cloud) }
            val precipY = CarbonStyle.DAYLIGHT_H + h.cloud - CarbonStyle.CLOUD_LIFT - CarbonStyle.CLOUD_RAISE
            val precipLayerH = precipH + CarbonStyle.CLOUD_LIFT + CarbonStyle.CLOUD_RAISE
            block(c, precipY, precipLayerH, w) { drawRain(g, p, win, precipLayerH) }
            if (h.event > 0) {
                val evY = CarbonStyle.DAYLIGHT_H + h.cloud + precipH - CarbonStyle.EVENT_ICON_LIFT
                block(c, evY, h.event + CarbonStyle.EVENT_ICON_LIFT, w) { drawEvents(g, p, win, h.event + CarbonStyle.EVENT_ICON_LIFT) }
            }
            if (L.hasTemp) block(c, L.tempY, L.tempH, w) { drawTemps(g, p, win, L.tempH) }
        }
        return bmp
    }

    // ------------------------------------------------------------- plumbing
    private class Ctx(val c: Canvas, val w: Int, val s: WidgetSettings, val pal: CarbonStyle.Palette)

    data class Heights(val cloud: Int, val precip: Int, val event: Int)

    /** main.c prv_top_strip_heights: the stack stays 73 rows. */
    fun stripHeights(s: WidgetSettings): Heights {
        var cloud = if (s.cloudSplit) CarbonStyle.CLOUD_H * 2 else CarbonStyle.CLOUD_H
        val event = if (s.eventDisplay == 2) 0 else CarbonStyle.EVENT_H
        var precip = CarbonStyle.GRAPH_LAYERS_H - CarbonStyle.DAYLIGHT_H - cloud - event
        if (precip < 8) {
            precip = 8
            cloud = max(CarbonStyle.CLOUD_H, CarbonStyle.GRAPH_LAYERS_H - CarbonStyle.DAYLIGHT_H - precip - event)
        }
        return Heights(cloud, precip, event)
    }

    private inline fun block(c: Canvas, y: Int, h: Int, w: Int, draw: () -> Unit) {
        c.save(); c.translate(0f, y.toFloat()); c.clipRect(0, 0, w, h); draw(); c.restore()
    }

    /** Per thread, like ModernRenderer's scratch paints (widget and app render concurrently). */
    private val fillTL = ThreadLocal.withInitial { Paint().apply { isAntiAlias = false; style = Paint.Style.FILL } }
    private val fill: Paint get() = fillTL.get()!!
    private val clear = Paint().apply { isAntiAlias = false; xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    private fun paintFor(color: Int): Paint = if (color == CarbonStyle.BG) clear else fill.also { it.color = color }

    private fun px(c: Canvas, x: Int, y: Int, color: Int) {
        c.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, paintFor(color))
    }

    private fun rect(c: Canvas, x: Int, y: Int, w: Int, h: Int, color: Int) {
        if (w <= 0 || h <= 0) return
        c.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), paintFor(color))
    }

    /** graphics_fill_circle */
    private fun fillCircle(c: Canvas, cx: Int, cy: Int, r: Int, color: Int) {
        if (r <= 0) { px(c, cx, cy, color); return }
        for (dy in -r..r) for (dx in -r..r) if (dx * dx + dy * dy <= r * r + r) px(c, cx + dx, cy + dy, color)
    }

    /** graphics_draw_circle, 1 px (midpoint circle). */
    private fun drawCircle(c: Canvas, cx: Int, cy: Int, r: Int, color: Int) {
        var x = r; var y = 0; var err = 1 - r
        while (x >= y) {
            for ((a, b) in arrayOf(x to y, y to x, -y to x, -x to y, -x to -y, -y to -x, y to -x, x to -y)) px(c, cx + a, cy + b, color)
            y++
            if (err < 0) err += 2 * y + 1 else { x--; err += 2 * (y - x) + 1 }
        }
    }

    /** graphics_draw_line (Bresenham), stroke width 1 or 2. */
    private fun line(c: Canvas, x0: Int, y0: Int, x1: Int, y1: Int, color: Int, width: Int = 1) {
        var x = x0; var y = y0
        val dx = abs(x1 - x0); val dy = -abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1; val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            if (width >= 2) rect(c, x - 1, y, 2, 2, color) else px(c, x, y, color)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    /** gx_mask (identity): bit x of rows[y] → pixel (cx + ox + x, cy + oy + y). */
    private fun mask(c: Canvas, rows: IntArray, w: Int, cx: Int, cy: Int, ox: Int, oy: Int, color: Int) {
        for (y in rows.indices) for (x in 0 until w) if (rows[y] shr x and 1 == 1) px(c, cx + ox + x, cy + oy + y, color)
    }

    private fun colX(g: Ctx, i: Int) = i * g.w / g.s.totalHours
    private fun colCenter(g: Ctx, i: Int) = (i * 2 + 1) * g.w / (g.s.totalHours * 2)
    private fun nowX(g: Ctx) = g.w * g.s.pastHours / g.s.totalHours

    /** Which payload slots are on screen (graph_common.h hour window). */
    class Window(val startSlot: Int, val currentHour: Int, val hoursRemaining: Int) {
        companion object {
            fun of(p: WeatherPayload, s: WidgetSettings, nowMillis: Long, tz: TimeZone): Window {
                // The watch moves its "now" column on as time passes after a fetch.
                val shift = max(0L, floor((nowMillis / 1000 - p.fetchTime) / 3600.0).toLong()).toInt()
                val start = min(CarbonStyle.PAYLOAD_NOW_SLOT - s.pastHours + shift, WeatherPayload.HOURS - s.totalHours - 1).coerceAtLeast(0)
                val cal = Calendar.getInstance(tz).apply { timeInMillis = nowMillis }
                return Window(start, cal.get(Calendar.HOUR_OF_DAY), p.validHours - start)
            }
        }
    }

    // ------------------------------------------------------------- daylight
    /** daylight_layer.c prv_update_proc (rect): track, noon/moon, battery, needle. */
    private fun drawDaylight(g: Ctx, p: WeatherPayload?, nowMillis: Long, tz: TimeZone, h: Int, batt: Battery?) {
        val c = g.c
        val lineY = h / 2
        val th = CarbonStyle.TRACK_HALF
        val nowSec = nowMillis / 1000
        val xNow = nowX(g)
        fun dx(diff: Long): Int {
            val denom = 3600L * g.s.totalHours
            val num = diff * g.w
            return xNow + (if (num >= 0) (num + denom / 2) / denom else (num - denom / 2) / denom).toInt()
        }
        fun visible(x: Int) = x in 0..g.w
        /** Local wall-clock time on day d (DST-aware, like prv_dst_shift). */
        fun at(d: Int, hh: Int, mm: Int): Long = Calendar.getInstance(tz).run {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, d)
            add(Calendar.MINUTE, hh * 60 + mm)
            timeInMillis / 1000
        }
        // prv_draw_track
        rect(c, 0, lineY - th, g.w, 2 * th + 1, g.pal.trackNight)
        if (p != null) {
            val dayLen = ((p.sunsetHour - p.sunriseHour) * 60 + (p.sunsetMinute - p.sunriseMinute)).let { if (it < 0) it + 1440 else it }
            val rises = (-3..3).map { at(it, p.sunriseHour, p.sunriseMinute) - nowSec }
            for (r in rises) {
                val xr = dx(r); val xs = dx(r + dayLen * 60L)
                val x1 = max(0, xr); val x2 = min(g.w, xs)
                if (x2 > x1) rect(c, x1, lineY - th, x2 - x1, 2 * th + 1, g.pal.trackDay)
            }
            for (r in rises) for (x in intArrayOf(dx(r), dx(r + dayLen * 60L))) if (visible(x)) rect(c, x, lineY - 4, 1, 9, g.pal.trackBracket)
        }
        // prv_draw_noon_moon
        for (d in -3..3) {
            val xn = dx(at(d, 12, 0) - nowSec)
            if (visible(xn)) colMarker(g, xn, lineY, 4)
            val midn = at(d + 1, 0, 0)
            val xm = dx(midn - nowSec)
            if (visible(xm)) colMarker(g, xm, lineY, moonPhase(midn, tz))
        }
        // prv_draw_battery (phone battery, see BatteryTracker)
        if (batt != null && !batt.charging && batt.percent < 100 && batt.anchorSec > 0) {
            val past = g.s.pastHours * 3600L; val fut = g.s.forecastHours * 3600L
            val anchorOff = batt.anchorSec - nowSec
            fun toMark(m: Int): Long = when {
                batt.anchorPercent < m -> (batt.anchorPercent - m).toLong() * batt.secsPerPct
                batt.anchorPercent == m -> 0L
                else -> (batt.anchorPercent - m).toLong() * batt.secsPerPct
            }
            val levels = g.s.batteryLevels
            for ((m, col, fw) in listOf(Triple(20, g.pal.batt20, 4), Triple(10, g.pal.batt10, 2), Triple(5, g.pal.batt5, 1), Triple(0, g.pal.batt0, 0))) {
                if (m !in levels) continue
                val eta = anchorOff + toMark(m)
                if (eta < -past || eta > fut) continue
                val bx = dx(eta).coerceIn(0, g.w)
                fillCircle(c, bx, lineY, CarbonStyle.BM_W / 2 + 2, CarbonStyle.BG)
                roundRect1(c, bx - CarbonStyle.BM_W / 2, lineY - CarbonStyle.BM_H / 2, CarbonStyle.BM_W, CarbonStyle.BM_H, col)
                rect(c, bx + CarbonStyle.BM_W / 2, lineY - 1, 1, 3, col)
                if (fw > 0) rect(c, bx - CarbonStyle.BM_W / 2 + 1, lineY - CarbonStyle.BM_H / 2 + 1, fw * CarbonStyle.BM_W / 8, CarbonStyle.BM_H - 2, col)
            }
        }
        // prv_draw_needle
        if (g.s.needle == 0 || g.s.needle == 1) rect(c, xNow - 1, 0, 2, lineY + 7, g.pal.needle)
    }

    /** graphics_draw_round_rect r=1: 1 px outline without the 4 corner pixels. */
    private fun roundRect1(c: Canvas, x: Int, y: Int, w: Int, h: Int, col: Int) {
        rect(c, x + 1, y, w - 2, 1, col); rect(c, x + 1, y + h - 1, w - 2, 1, col)
        rect(c, x, y + 1, 1, h - 2, col); rect(c, x + w - 1, y + 1, 1, h - 2, col)
    }

    /** prv_draw_col_marker: noon (phase 4) or moon phase 0–7, r = 3. */
    private fun colMarker(g: Ctx, cx: Int, cy: Int, phase: Int) {
        val c = g.c
        val r = CarbonStyle.MARKER_R
        val fg = g.pal.markerFg
        fillCircle(c, cx, cy, r, CarbonStyle.BG)
        drawCircle(c, cx, cy, r, fg)
        if (phase == 0) return
        if (phase == 4) { fillCircle(c, cx, cy, r, fg); return }
        val thr = intArrayOf(0, 2, 1, -1, 0, 1, -1, -2)[phase]
        val waxing = phase in 1..3
        for (dy in -(r - 1)..(r - 1)) {
            var dxMax = 0
            while ((dxMax + 1) * (dxMax + 1) + dy * dy < r * r) dxMax++
            val lo = max(if (waxing) thr else -dxMax, -dxMax)
            val hi = min(if (waxing) dxMax else thr, dxMax)
            if (lo <= hi) rect(c, cx + lo, cy + dy, hi - lo + 1, 1, fg)
        }
    }

    /** prv_moon_phase_at (integer algorithm, local date). */
    fun moonPhase(sec: Long, tz: TimeZone): Int {
        val cal = Calendar.getInstance(tz).apply { timeInMillis = sec * 1000 }
        var year = cal.get(Calendar.YEAR); var month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        if (month < 3) { year--; month += 12 }
        month++
        val jd = 3652500L * (year - 2000) + 306000L * month + 10000L * day + 364609100L
        val denom = 295306L
        val rem = Math.floorMod(jd, denom)
        val b = ((rem * 8L + denom / 2L) / denom).toInt()
        return if (b >= 8) 0 else b
    }

    // ------------------------------------------------------------------ clouds
    private fun radiusFor(cover: Int, th: IntArray) = when {
        cover < th[0] -> 0
        cover < th[1] -> 2
        cover < th[2] -> 3
        else -> 4
    }

    private val LOBES = { r: Int -> listOf(Triple(0, 0, r), Triple(-r, r / 2, r * 2 / 3), Triple(r, r / 2, r * 2 / 3)) }

    /** prv_draw_cloud_lobes */
    private fun lobes(c: Canvas, cx: Int, cy: Int, r: Int, color: Int) {
        for ((ox, oy, rr) in LOBES(r)) fillCircle(c, cx + ox, cy + oy, rr, color)
    }

    /** prv_stipple_lobes */
    private fun stipple(c: Canvas, cx: Int, cy: Int, r: Int, color: Int) {
        for ((ox, oy, rr) in LOBES(r)) {
            val x0 = cx + ox; val y0 = cy + oy
            for (dy in -rr..rr) for (dx in -rr..rr)
                if (dx * dx + dy * dy < rr * rr && ((x0 + dx) + (y0 + dy)) and 1 == 0) px(c, x0 + dx, y0 + dy, color)
        }
    }

    private const val LOW = 1
    private const val MID = 2
    private const val HIGH = 4

    /** precip_map.h cloud_split_heavy_mask */
    private fun splitHeavyMask(code: Int, heavy: Boolean, drawn: Int): Int = when {
        !heavy -> 0
        code in 95..99 -> drawn
        drawn and LOW != 0 -> LOW
        drawn and MID != 0 -> MID
        else -> 0
    }

    /** precip_map.h cloud_thick_for_layer (0 none, 1 light, 2 normal, 3 thick) */
    private fun thickFor(level: Int, layer: Int, drawn: Int): Int {
        if (level == 0 || level > 3) return 2
        if (layer == 0 || level != 3) return level
        val lowest = when { drawn and LOW != 0 -> LOW; drawn and MID != 0 -> MID; drawn and HIGH != 0 -> HIGH; else -> 0 }
        return if (layer == lowest) 3 else 2
    }

    /** cloud_layer.c prv_draw_band */
    private fun band(g: Ctx, p: WeatherPayload, win: Window, cover: IntArray, cy: Int, th: IntArray, layer: Int, halo: Boolean) {
        val c = g.c
        val total = g.s.totalHours
        if (halo) for (i in total - 1 downTo 0) {
            val r = radiusFor(cover[win.startSlot + i], th)
            if (r > 0) lobes(c, colCenter(g, i), cy, r + 1, CarbonStyle.BG)
        }
        for (i in total - 1 downTo 0) {
            val sl = win.startSlot + i
            val r = radiusFor(cover[sl], th)
            if (r <= 0) continue
            val code = p.weatherCode[sl]
            var heavy = PrecipMap.cloudHeavy(code, p.haveMm, p.precipMmTenths[sl])
            var drawn = 0
            if (layer != 0) {
                if (radiusFor(p.cloudLow[sl], th) > 0) drawn = drawn or LOW
                if (radiusFor(p.cloudMid[sl], th) > 0) drawn = drawn or MID
                if (radiusFor(p.cloudHigh[sl], th) > 0) drawn = drawn or HIGH
                heavy = splitHeavyMask(code, heavy, drawn) and layer != 0
            }
            val level = if (g.s.cloudThick) thickFor(p.cloudThick[sl], layer, drawn) else 2
            var base = if (heavy) g.pal.cloudHeavy else g.pal.cloudNormal
            var dither: Int? = null
            if (!heavy && level == 1) dither = g.pal.cloudLightDither
            if (!heavy && level == 3) { base = g.pal.cloudThickBase; dither = g.pal.cloudThickDither }
            val cx = colCenter(g, i)
            lobes(c, cx, cy, r, base)
            if (dither != null) stipple(c, cx, cy, r, dither)
        }
    }

    /** cloud_layer.c prv_draw: clouds first, then sun rays on top. */
    private fun drawClouds(g: Ctx, p: WeatherPayload, win: Window, h: Int) {
        val s = g.s
        if (s.cloudSensitivity < 4) {
            val th = CarbonStyle.CLOUD_THRESHOLDS[s.cloudSensitivity]
            if (s.cloudSplit) {
                val (cyHigh, cyMid, cyLow) = splitRows(h)
                band(g, p, win, p.cloudHigh, cyHigh, th, HIGH, false)
                band(g, p, win, p.cloudMid, cyMid, th, MID, true)
                band(g, p, win, p.cloudLow, cyLow, th, LOW, true)
            } else {
                band(g, p, win, p.cloudCover, (h / 2).coerceAtMost(h - 5).coerceAtLeast(4), th, 0, false)
            }
        }
        if (s.sunSensitivity < 4) {
            val (minI, full) = CarbonStyle.SUN_THRESHOLDS[s.sunSensitivity].let { it[0] to it[1] }
            val span = if (h > 2) h - 1 else 4
            for (i in 0 until s.totalHours) {
                val sl = win.startSlot + i
                val wm2 = p.shortwaveWm2[sl]
                val base = if (wm2 >= full) 255 else wm2 * 255 / full
                val intensity = base * max(0, 100 - p.cloudCover[sl]) / 100  // prv_ray_intensity_for_cover
                if (intensity < minI) continue
                val dy = (3 + intensity * (span - 3) / 255).coerceIn(3, span)
                val cx = colCenter(g, i)
                for (r in 0 until 3) rect(g.c, cx - 2 + r * 2, 0, 1, dy + 1, g.pal.sunRay)
            }
        }
    }

    /** Split band centres (prv_draw): high = h/4, pitch 7, clamped to the strip. */
    fun splitRows(h: Int): Triple<Int, Int, Int> {
        var pitch = 7
        if (pitch * 2 + 8 > h) pitch = (h - 8) / 2
        if (pitch < 5) pitch = 5
        val cyHigh = max(4, h / 4)
        var cyMid = cyHigh + pitch
        var cyLow = cyMid + pitch
        if (cyLow > h - 5) { cyLow = h - 5; cyMid = max(cyLow - pitch, cyHigh + 4) }
        return Triple(cyHigh, cyMid, cyLow)
    }

    // -------------------------------------------------------------------- rain
    /** precip_map.h precip_amount_px */
    internal fun amountPx(tenths: Int, h: Int): Int {
        if (tenths == 0 || h <= 0) return 0
        if (tenths >= CarbonStyle.AMOUNT_FULL_TENTHS) return h
        val target = tenths.toLong() * h * h / CarbonStyle.AMOUNT_FULL_TENTHS
        var r = 0L
        while ((r + 1) * (r + 1) <= target) r++
        return max(1, r.toInt())
    }

    /** precip_layer.c prv_update_proc (+ prv_fill_shaded, prv_draw_chance_line) */
    private fun drawRain(g: Ctx, p: WeatherPayload, win: Window, layerH: Int) {
        val c = g.c
        val s = g.s
        val total = s.totalHours
        val minProb = CarbonStyle.PRECIP_MIN_PROB[s.precipSensitivity]
        val amount = p.haveMm && (s.rainHeight == 1 || s.rainHeight == 2)
        val lineMode = amount && s.rainHeight == 2
        for (i in 0 until total) {
            val sl = win.startSlot + i
            val x0 = colX(g, i); val x1 = colX(g, i + 1)
            val barW = max(1, x1 - x0 - 1)
            val prob = p.precipProb[sl]
            if (prob < minProb) continue
            val barH = if (amount) amountPx(p.precipMmTenths[sl], layerH - 2).also { if (it < 1) continue }
            else max(1, (prob - minProb) * 100 / max(1, 100 - minProb)) * (layerH - 2) / 100
            val code = p.weatherCode[sl]
            val kind = PrecipMap.kind(code)
            val heavy = PrecipMap.isHeavy(code, p.haveMm, p.precipMmTenths[sl])
            val col = when (kind) {
                PrecipMap.KIND_SNOW -> if (heavy) g.pal.snowHeavy else g.pal.snowLight
                PrecipMap.KIND_MIX -> g.pal.mix
                PrecipMap.KIND_HAIL -> g.pal.hail
                else -> if (heavy) g.pal.rainHeavy else g.pal.rainLight
            }
            if (amount && !lineMode) {
                val shade = if (prob >= 70) 2 else if (prob >= 40) 1 else 0  // precip_prob_shade
                if (shade >= 2) rect(c, x0, 0, barW, barH, col)
                else {
                    for (y in 0 until barH - 1) for (x in x0 until x0 + barW)
                        if (if (shade == 1) (x + y) and 1 == 0 else (x and 1 == 0 && y and 1 == 0)) px(c, x, y, col)
                    rect(c, x0, barH - 1, barW, 1, col)
                }
            } else rect(c, x0, 0, barW, barH, col)
            if (kind == PrecipMap.KIND_HAIL) g.pal.hailOutline?.let { o ->
                rect(c, x0, 0, barW, 1, o); rect(c, x0, barH - 1, barW, 1, o)
                rect(c, x0, 0, 1, barH, o); rect(c, x0 + barW - 1, 0, 1, barH, o)
            }
        }
        if (lineMode) {
            val span = layerH - 2
            var prevX = 0; var prevY = 0; var prevOn = false
            for (i in 0 until total) {
                val sl = win.startSlot + i
                val x0 = colX(g, i); val x1 = colX(g, i + 1)
                val pr = min(100, p.precipProb[sl])
                val ptX = (x0 + x1 - 1) / 2; val ptY = pr * span / 100
                val on = p.precipProb[sl] >= minProb
                if (i > 0 && (on || prevOn)) line(c, prevX, prevY, ptX, ptY, g.pal.chanceLine)
                else if (on) px(c, ptX, ptY, g.pal.chanceLine)
                prevX = ptX; prevY = ptY; prevOn = on
            }
        }
    }

    // ------------------------------------------------------------------ events
    enum class EventKind { NONE, SNOW, STORM, TORNADO, MISSING, FOG, WIND }
    data class EventSpan(val start: Int, val end: Int, val kind: EventKind)

    /** event_layer.c prv_event_icon + prv_hour_event: tornado > thunder > snow > wind > fog. */
    fun hourEvent(code: Int, strong: Boolean, wind: Boolean, fog: Boolean, flags: Int, fogOn: Boolean): EventKind {
        val tornadoOn = flags and CarbonStyle.EVENT_HIDE_TORNADO == 0
        if ((code == 19 || code == 99) && tornadoOn) return EventKind.TORNADO
        if ((code in 95..98 || (code == 99 && !tornadoOn)) && flags and CarbonStyle.EVENT_HIDE_THUNDER == 0 &&
            (flags and CarbonStyle.EVENT_THUNDER_STRONG == 0 || code >= 96 || strong)) return EventKind.STORM
        if (flags and CarbonStyle.EVENT_HIDE_SNOW == 0) {
            val lvl = (flags and 0x30) shr 4
            val snow = when {
                lvl >= 2 -> code == 75 || code == 86
                lvl == 1 -> code == 73 || code == 75 || code == 86
                else -> code in intArrayOf(71, 73, 75, 77, 85, 86)
            }
            if (snow) return EventKind.SNOW
        }
        if (wind) return EventKind.WIND
        if (fog && fogOn) return EventKind.FOG
        return EventKind.NONE
    }

    /** prv_find_spans: runs of the same kind, plus one MISSING span past the data. */
    fun eventSpans(p: WeatherPayload, s: WidgetSettings, startSlot: Int, hoursRemaining: Int): List<EventSpan> {
        val total = s.totalHours
        val out = ArrayList<EventSpan>()
        fun ev(i: Int): EventKind { val sl = startSlot + i
            return hourEvent(p.weatherCode[sl], p.storm[sl], p.wind[sl], p.fog[sl], s.eventFlags, s.fogIndicator) }
        var i = 0
        val lim = min(hoursRemaining, total)
        while (i < lim) {
            val k = ev(i)
            if (k == EventKind.NONE) { i++; continue }
            val start = i
            while (i < lim && ev(i) == k) i++
            out.add(EventSpan(start, i - 1, k))
        }
        if (hoursRemaining in 1 until total) out.add(EventSpan(hoursRemaining, total - 1, EventKind.MISSING))
        return out
    }

    private fun spanColor(g: Ctx, k: EventKind) = when (k) {
        EventKind.FOG -> g.pal.evFog
        EventKind.WIND -> g.pal.evWind
        EventKind.STORM -> g.pal.evStorm
        EventKind.SNOW -> g.pal.evSnow
        EventKind.TORNADO -> g.pal.evTornado
        else -> g.pal.evMissing
    }

    /** event_layer.c prv_update_proc (rect). */
    private fun drawEvents(g: Ctx, p: WeatherPayload, win: Window, layerH: Int) {
        if (g.s.eventDisplay == 2) return
        val c = g.c
        val total = g.s.totalHours
        val spans = eventSpans(p, g.s, win.startSlot, win.hoursRemaining)
        val cy = (layerH - CarbonStyle.EVENT_ICON_LIFT) / 2 + CarbonStyle.EVENT_ICON_LIFT
        for (sp in spans.asReversed()) {
            val xs = (sp.start * 2 + 1) * g.w / (total * 2)
            val xe = (sp.end * 2 + 1) * g.w / (total * 2)
            val ix = (xs + xe) / 2
            val gap = if (sp.kind == EventKind.WIND) 8 else 7
            val col = spanColor(g, sp.kind)
            val col2 = if (sp.kind == EventKind.FOG) g.pal.evFog2 else col
            fun line2(x0: Int, y0: Int, x1: Int, y1: Int) {
                val sx = Integer.signum(x1 - x0); val sy = Integer.signum(y1 - y0)
                var x = x0; var y = y0
                while (true) { px(c, x, y, if ((x + y) and 1 == 1) col2 else col); if (x == x1 && y == y1) break; x += sx; y += sy }
            }
            if (g.s.eventDisplay == 0) {
                if (xs < ix - gap) { line2(xs, cy, ix - gap, cy); line2(xs, cy - 2, xs, cy + 2) }
                if (xe > ix + gap) { line2(ix + gap, cy, xe, cy); line2(xe, cy - 2, xe, cy + 2) }
            }
            val iy = cy - CarbonStyle.EVENT_ICON_LIFT
            when (sp.kind) {
                EventKind.STORM -> mask(c, CarbonStyle.MASK_BOLT, 9, ix, iy, -4, -6, col)
                EventKind.WIND -> mask(c, CarbonStyle.MASK_WIND, 12, ix, iy, -6, -4, col)
                EventKind.SNOW -> mask(c, CarbonStyle.MASK_SNOW, 9, ix, iy, -4, -4, col)
                EventKind.TORNADO -> mask(c, CarbonStyle.MASK_TORNADO, 7, ix, iy, -3, -4, col)
                EventKind.FOG -> { // prv_draw_fog_icon
                    val fy = iy + 1
                    val wave = CarbonStyle.FOG_WAVE
                    for (dy in intArrayOf(-2, 2)) for (k in wave.indices) {
                        val x = ix - 5 + k
                        var y = fy + dy + wave[k]
                        px(c, x, y, if ((x + y) and 1 == 1) col2 else col)
                        if (k < wave.size - 1 && wave[k + 1] != wave[k]) { y = fy + dy + wave[k + 1]; px(c, x, y, if ((x + y) and 1 == 1) col2 else col) }
                    }
                }
                EventKind.MISSING -> iconGlyph(c, "\uF1DF", ix.toFloat(), iy.toFloat(), 12f, col)
                EventKind.NONE -> {}
            }
        }
    }

    /** Per thread, like ModernRenderer's scratch paints (widget and app render concurrently). */
    private val glyphPaintTL = ThreadLocal.withInitial { Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER } }
    private val glyphPaint: Paint get() = glyphPaintTL.get()!!

    /** An IcoMoon glyph centred on (cx, cy), [size] rows (the watch font's px size). */
    private fun iconGlyph(c: Canvas, glyph: String, cx: Float, cy: Float, size: Float, color: Int) {
        val tf = iconTypeface ?: return
        glyphPaint.typeface = tf; glyphPaint.textSize = size; glyphPaint.color = color
        val fm = glyphPaint.fontMetrics
        c.drawText(glyph, cx, cy - (fm.ascent + fm.descent) / 2f, glyphPaint)
    }

    // ------------------------------------------------------------ temperatures
    /** temp_layer.c prv_update_proc (emery, colour). */
    private fun drawTemps(g: Ctx, p: WeatherPayload, win: Window, lh: Int) {
        val c = g.c
        val s = g.s
        val total = s.totalHours
        val nowCol = s.pastHours
        val rem = win.hoursRemaining
        val pts = IntArray(total + 1); val apt = IntArray(total + 1)
        val cur = p.currentTemp?.roundToInt()
        for (i in 0..total) {
            val sl = win.startSlot + i
            pts[i] = when {
                i == nowCol && cur != null -> cur
                i > 0 && i >= rem -> pts[i - 1]
                else -> p.tempHourly[sl].roundToInt()
            }
            apt[i] = if (i > 0 && i >= rem) apt[i - 1] else p.apparentHourly[sl].roundToInt()
        }
        var hi = pts[0]; var lo = pts[0]
        var tMin = min(pts[0], apt[0]); var tMax = max(pts[0], apt[0])
        for (i in 1..total) {
            if (i >= rem) break
            hi = max(hi, pts[i]); lo = min(lo, pts[i])
            tMin = minOf(tMin, pts[i], apt[i]); tMax = maxOf(tMax, pts[i], apt[i])
        }
        val tRange = max(1, tMax - tMin)
        val pad = CarbonStyle.TEMP_PAD
        val graphH = lh - pad - 3
        val spx = IntArray(total + 1) { colX(g, it) }
        val spy = IntArray(total + 1) { pad + graphH - (pts[it] - tMin) * graphH / tRange }
        val apy = IntArray(total + 1) { pad + graphH - (apt[it] - tMin) * graphH / tRange }
        val xNow = nowX(g)
        fun tf(t: Int) = if (s.celsius) t * 9 / 5 + 32 else t
        fun colour(i: Int) = g.pal.tempColor(tf((pts[i - 1] + pts[i]) / 2))

        // Pass 1: infill
        if (s.infill != 3) {
            val startCol = if (s.infill == 0) nowCol + 1 else 1
            val endCol = if (s.infill == 1) nowCol else total
            for (i in startCol..min(endCol, total)) {
                if (i >= rem) break
                val x0 = spx[i - 1]; val y0 = spy[i - 1]; val x1 = spx[i]; val y1 = spy[i]
                val dx = x1 - x0
                if (dx <= 0) continue
                val xEnd = if (i == endCol || i == total) x1 else x1 - 1
                for (x in x0..xEnd) {
                    if (s.infill == 0 && x < xNow) continue
                    if (s.infill == 1 && x > xNow) continue
                    val y = y0 + (y1 - y0) * (x - x0) / dx + 1
                    rect(c, x, y, 1, lh - y, colour(i))
                }
            }
        }
        // Pass 2: actual-temp line, width 2
        for (i in 1..total) {
            if (i >= rem) break
            line(c, spx[i - 1], spy[i - 1], spx[i], spy[i], colour(i), 2)
        }
        // Pass 3: feels-like, graph_draw_dashed_line(3, 2)
        for (i in 1..total) {
            if (i >= rem) break
            val x0 = spx[i - 1]; val y0 = apy[i - 1]; val dx = spx[i] - x0; val dy = apy[i] - y0
            val steps = max(abs(dx), abs(dy))
            for (st in 0..steps) if (st % 5 < 3)
                px(c, x0 + if (steps == 0) 0 else dx * st / steps, y0 + if (steps == 0) 0 else dy * st / steps, g.pal.ink)
        }
        // prv_draw_ticks
        val baseHour = ((win.currentHour - nowCol) % 24 + 240) % 24
        val step = if (total > 36) 3 else 1
        val tickXs = ArrayList<Float>(); val tickLens = ArrayList<Float>()
        for (i in 0..total step step) {
            val hv = (baseHour + i) % 24
            val major = hv == 0 || hv == 12
            val len = if (major) 5 else 3
            rect(c, spx[i], lh - len, 1, len, if (major) g.pal.tickMajor else g.pal.tick)
            tickXs.add(spx[i].toFloat()); tickLens.add(len.toFloat())
        }
        // prv_draw_needle
        if (s.needle == 0 || s.needle == 2) rect(c, xNow - 1, 0, 2, lh, g.pal.needle)
        // prv_draw_labels, but stacked by glyph ink (TempLabels): equal gaps, current midway, right-aligned digits.
        val right = 2f + CarbonStyle.TEMP_LABEL_W
        val texts = listOf(hi.toString(), (cur ?: pts[nowCol]).toString(), lo.toString())
        val paints = listOf(labelPaint(CarbonStyle.TEMP_FONT_SM, false), labelPaint(CarbonStyle.TEMP_FONT_MD, true), labelPaint(CarbonStyle.TEMP_FONT_SM, false))
        // Full chart height: ink padded 4 % (≥ 1 px) under the top and above the tallest hour tick under the labels.
        val bottom = TempLabels.bandBottom(lh.toFloat(), tickXs, tickLens, right - TempLabels.columnWidth(texts, paints) - 1f, right + 1f)
        val pos = TempLabels.place(texts, paints, right, 0f, bottom,
            pad = TempLabels.pad(lh.toFloat(), 1f).roundToInt().toFloat(), minGap = 1f, snap = true)
        for ((i, pl) in pos.withIndex()) label(g, pl.text, pl.x, pl.baseline, if (i == 1) CarbonStyle.TEMP_FONT_MD else CarbonStyle.TEMP_FONT_SM, i == 1)
    }

    /** Per thread, like ModernRenderer's scratch paints (widget and app render concurrently). */
    private val textTL = ThreadLocal.withInitial { Paint().apply { isAntiAlias = true } }
    private val text: Paint get() = textTL.get()!!

    /** Pebble GOTHIC sizes map to Roboto at 0.82 (cap heights match). */
    private fun labelPaint(size: Float, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; textSize = size * 0.82f
    }

    /** Label at a left origin [x] and [baseline] from [TempLabels]. */
    private fun label(g: Ctx, s: String, x: Float, baseline: Float, size: Float, bold: Boolean) {
        text.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        text.textSize = size * 0.82f
        text.textAlign = Paint.Align.LEFT
        // Halo in the theme's background (white / black) so the label reads over the curve and infill.
        text.style = Paint.Style.STROKE; text.strokeJoin = Paint.Join.ROUND; text.strokeWidth = 1.6f
        text.color = if (g.pal.light) 0xB3FFFFFF.toInt() else 0xB3000000.toInt()
        g.c.drawText(s, x, baseline, text)
        text.style = Paint.Style.FILL
        text.color = g.pal.ink
        g.c.drawText(s, x, baseline, text)
    }

    // ------------------------------------------------------- condition icon
    /** weather.c weather_code_to_condition + weather_condition_to_icon → IcoMoon glyph. */
    fun conditionGlyph(code: Int, isDay: Boolean): String? {
        val sun = "\uF340"; val moon = "\uF0CF"
        return when {
            code < 0 -> null
            code == 0 -> if (isDay) sun else moon
            code == 1 -> if (isDay) "\uF639" else "\uF5F1"
            code == 2 -> if (isDay) "\uF2BA" else "\uF217"
            code <= 5 -> "\uF183"
            code <= 9 -> "\uF8D7"
            code <= 12 -> if (isDay) "\uF1AA" else "\uF2FE"
            code == 13 -> if (isDay) "\uF2F3" else "\uF212"
            code <= 16 -> if (isDay) "\uF4B7" else "\uF4B8"
            code == 17 -> if (isDay) "\uF2F3" else "\uF212"
            code == 18 -> "\uF8D7"
            code == 19 -> "\uF773"
            code == 20 -> "\uF62F"
            code == 21 -> if (isDay) "\uF4B7" else "\uF4B8"
            code == 22 -> if (isDay) "\uF876" else "\uF430"
            code <= 24 -> "\uF507"
            code == 25 -> if (isDay) "\uF4B7" else "\uF4B8"
            code == 26 -> "\uF507"
            code == 27 -> "\uF3B0"
            code == 28 -> if (isDay) "\uF1AA" else "\uF2FE"
            code == 29 -> if (isDay) "\uF2F3" else "\uF212"
            code <= 35 -> "\uF8D7"
            code <= 39 -> if (isDay) "\uF876" else "\uF430"
            code <= 49 -> if (isDay) "\uF1AA" else "\uF2FE"
            code <= 55 -> "\uF62F"
            code <= 57 -> "\uF507"
            code <= 63 -> if (isDay) "\uF4B7" else "\uF4B8"
            code <= 65 -> "\uF20B"
            code <= 69 -> "\uF507"
            code <= 73 -> if (isDay) "\uF876" else "\uF430"
            code <= 75 -> "\uF41A"
            code <= 79 -> "\uF507"
            code <= 81 -> if (isDay) "\uF4B7" else "\uF4B8"
            code == 82 -> "\uF20B"
            code <= 84 -> "\uF507"
            code == 85 -> if (isDay) "\uF876" else "\uF430"
            code == 86 -> "\uF41A"
            code <= 90 -> "\uF3B0"
            code <= 92 -> if (isDay) "\uF4B7" else "\uF4B8"
            code <= 94 -> "\uF507"
            code <= 96 -> if (isDay) "\uF2F3" else "\uF212"
            code == 97 -> "\uF7DB"
            code == 98 -> if (isDay) "\uF2F3" else "\uF212"
            code == 99 -> "\uF7DB"
            else -> "\uF54A"
        }
    }

    /** The condition icon shown right of the city (time_layer.c cond_label, IcoMoon 18). */
    fun conditionIcon(p: WeatherPayload?, nowMillis: Long, tz: TimeZone, color: Int, sizePx: Int): Bitmap? {
        if (p == null || iconTypeface == null) return null
        val cal = Calendar.getInstance(tz).apply { timeInMillis = nowMillis }
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val isDay = m >= p.sunriseHour * 60 + p.sunriseMinute && m < p.sunsetHour * 60 + p.sunsetMinute
        val glyph = conditionGlyph(p.currentCode, isDay) ?: return null
        val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        iconGlyph(Canvas(b), glyph, sizePx / 2f, sizePx / 2f, sizePx.toFloat(), color)
        return b
    }

    /** The watch-like clock face (Condensed bold). */
    val CLOCK_TYPEFACE: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
}
