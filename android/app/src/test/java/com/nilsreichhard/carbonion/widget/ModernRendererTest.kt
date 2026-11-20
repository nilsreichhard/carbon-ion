package com.nilsreichhard.carbonion.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextClock
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/** The Modern (high-resolution) style, and the style / font wiring in WidgetUpdater. PNGs in build/outputs/preview/. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModernRendererTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val tz = TimeZone.getTimeZone("Europe/Zurich")
    private val p = WeatherPayload.fromJson(Previews.payloadJson())
    private val now = p.fetchTime * 1000 + 5 * 60_000
    private val spec = ModernRenderer.Spec(1080, 1032, 3f)

    private fun render(s: WidgetSettings, sp: ModernRenderer.Spec = spec) = ModernRenderer.render(p, s, sp, now, tz)

    private fun rowsPx(b: Bitmap, r0: Int, r1: Int, rows: Int = WidgetLayout.DEFAULT.totalRows) =
        (r0 * b.height / rows) until (r1 * b.height / rows)

    private fun same(a: Bitmap, b: Bitmap, ys: IntRange): Boolean {
        for (y in ys step 2) for (x in 0 until a.width step 2) if (a.getPixel(x, y) != b.getPixel(x, y)) return false
        return true
    }

    @Test
    fun rendersAtDevicePixelsAntiAliased() {
        val b = render(WidgetSettings())
        assertEquals(1080, b.width); assertEquals(1032, b.height)
        var partial = 0; var opaque = 0
        for (y in 0 until b.height step 2) for (x in 0 until b.width step 2) {
            val a = b.getPixel(x, y) ushr 24
            if (a in 1..254) partial++ else if (a == 255) opaque++
        }
        assertTrue("anti-aliased edges", partial > 2000)
        assertTrue(opaque > 10_000)
        Previews.save(b, "modern_default")
    }

    @Test
    fun splitTotalAndRainModesDiffer() {
        val total = render(WidgetSettings())
        val split = render(WidgetSettings(cloudSplit = true))
        assertTrue(!same(total, split, rowsPx(total, 14, 50)))
        val r0 = render(WidgetSettings(rainHeight = 0))
        val r1 = render(WidgetSettings(rainHeight = 1))
        val r2 = render(WidgetSettings(rainHeight = 2))
        val rain = rowsPx(r0, 30, 70)
        assertTrue(!same(r0, r1, rain)); assertTrue(!same(r1, r2, rain))
        // Mode 2: the orange probability line.
        var orange = 0
        for (y in rain step 1) for (x in 0 until r2.width step 2) {
            val c = r2.getPixel(x, y)
            if (c ushr 24 > 200 && abs((c shr 16 and 255) - 0xFF) < 20 && abs((c shr 8 and 255) - 0x8F) < 30 && (c and 255) < 80) orange++
        }
        assertTrue("probability line", orange > 50)
        Previews.save(split, "modern_split"); Previews.save(r2, "modern_rain_line")
    }

    @Test
    fun eventsUseColouredVectorIcons() {
        // Thunder for 3 hours, then wind for 3, inside the visible window.
        val ev = WeatherPayload.fromJson(Previews.payloadJson())
        val s = WidgetSettings()
        val start = WidgetRenderer.Window.of(ev, s, now, tz).startSlot
        for (i in 2..4) ev.weatherCode[start + s.pastHours + i] = 95
        for (i in 8..10) { ev.wind[start + s.pastHours + i] = true; ev.weatherCode[start + s.pastHours + i] = 3 }
        val b = ModernRenderer.render(ev, s, spec, now, tz)
        var green = 0; var yellow = 0
        for (y in rowsPx(b, 55, 75)) for (x in 0 until b.width) {
            val c = b.getPixel(x, y)
            val r = c shr 16 and 255; val gg = c shr 8 and 255; val bl = c and 255
            if (c ushr 24 > 200 && gg > 140 && r < 90 && bl < 110) green++
            if (c ushr 24 > 200 && r > 200 && gg in 140..200 && bl < 60) yellow++
        }
        assertTrue("wind", green > 40)
        assertTrue("thunder", yellow > 40)
        Previews.save(b, "modern_events")
    }

    @Test
    fun darkThemeAndAccentChangeTheLook() {
        val light = render(WidgetSettings(lightTheme = true))
        val dark = render(WidgetSettings(lightTheme = false))
        val accent = render(WidgetSettings(), ModernRenderer.Spec(1080, 1032, 3f, accent = 0xFF6750A4.toInt()))
        assertTrue(!same(light, dark, rowsPx(light, 0, 228)))
        assertTrue(!same(light, accent, rowsPx(light, 0, 18)))
        Previews.save(dark, "modern_dark")
    }

    @Test
    fun monotoneCurveStaysWithinItsPoints() {
        val path = ModernRenderer.smooth(floatArrayOf(0f, 10f, 20f, 30f), floatArrayOf(5f, 5f, 20f, 20f))
        val r = android.graphics.RectF()
        @Suppress("DEPRECATION") path.computeBounds(r, true)
        assertTrue(r.top >= 4.99f && r.bottom <= 20.01f)
    }

    private fun build(ui: UiOptions, size: WidgetUpdater.Size = WidgetUpdater.Size(360, 344)): View {
        android.provider.Settings.System.putString(ctx.contentResolver, android.provider.Settings.System.TIME_12_24, "24")
        WidgetStore.saveWeather(ctx, Previews.payloadJson(), "Villars-sur-Ollon", JSONObject().put("SETTING_TEMP_UNIT", 0).toString())
        WidgetStore.saveUi(ctx, ui)
        val view = WidgetUpdater.build(ctx, size, 1, null, useAlarmOverride = true, nowMillis = now).apply(ctx, FrameLayout(ctx))
        val d = ctx.resources.displayMetrics.density
        val w = (size.wDp * d).roundToInt(); val h = (size.hDp * d).roundToInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        return view
    }

    private fun chart(v: View) = (v.findViewById<ImageView>(R.id.chart).drawable as android.graphics.drawable.BitmapDrawable).bitmap

    @Test
    fun styleSwitchesRenderer() {
        val modern = chart(build(UiOptions()))
        val classic = chart(build(UiOptions(style = UiOptions.Style.CLASSIC)))
        val d = ctx.resources.displayMetrics.density
        assertEquals((344 * d).roundToInt(), modern.height)       // device pixels
        assertEquals(0, classic.height % WidgetLayout.DEFAULT.totalRows) // whole watch rows
        assertNotEquals(modern.height, classic.height)
    }

    @Test
    fun clockFontLayoutIsLiveTextClock() {
        for (f in listOf(FontOption.THIN, FontOption.SERIF, FontOption.MONO, FontOption.CONDENSED)) {
            val v = build(UiOptions(clockFont = f))
            val clock = v.findViewById<TextClock>(R.id.clock)
            assertEquals(View.VISIBLE, clock.visibility)
            assertTrue(clock.textSize > 50f)
        }
    }

    @Test
    fun bundledTextFontIsDrawnIntoTheBitmap() {
        val sys = build(UiOptions())
        val outfit = build(UiOptions(textFont = FontOption.OUTFIT))
        assertEquals(View.VISIBLE, sys.findViewById<View>(R.id.city).visibility)
        assertEquals(View.INVISIBLE, outfit.findViewById<View>(R.id.city).visibility)
        assertEquals(View.INVISIBLE, outfit.findViewById<View>(R.id.date).visibility)
        val a = chart(sys); val b = chart(outfit)
        val L = WidgetLayout.DEFAULT
        assertTrue("city/date in the bitmap", !same(a, b, rowsPx(a, L.timeY, L.timeY + 20)))
        val out = Previews.wallpaper(outfit.width, outfit.height, false)
        outfit.draw(Canvas(out))
        Previews.save(out, "modern_outfit_text")
    }

    /** 2x2, 4x2, 4x3, 4x4 on a Pixel launcher (dp), as measured on the emulator. */
    private val sizes = listOf("2x2" to (172 to 224), "4x2" to (360 to 224), "4x3" to (360 to 344), "4x4" to (360 to 464))

    @Test
    fun timelineStaysInsideTheRoundedCorners() {
        val d = 3f; val corner = 28f * d
        for ((name, wh) in sizes) for (hours in listOf(12, 24, 48)) for (step in 0 until 24 step 3) {
            val (wDp, hDp) = wh
            val spec = ModernRenderer.Spec((wDp * d).toInt(), (hDp * d).toInt(), d, WidgetLayout.forHeight(hDp),
                cornerPx = corner, clipCorners = false)
            val bmp = ModernRenderer.render(p, WidgetSettings(forecastHours = hours), spec, now + step * 3_600_000L, tz)
            val tb = ModernRenderer.timelineBounds(spec)
            val inside = { x: Float, y: Float ->
                val cx = x.coerceIn(corner, spec.widthPx - corner); val cy = y.coerceIn(corner, spec.heightPx - corner)
                (x - cx) * (x - cx) + (y - cy) * (y - cy) <= corner * corner
            }
            // The declared bounds are inside the drawable (rounded) area…
            for ((x, y) in listOf(tb.left to tb.top, tb.right to tb.top, tb.left to tb.bottom, tb.right to tb.bottom))
                assertTrue("$name ${hours}h bounds corner ($x,$y) outside", x >= 0 && y >= 0 && x <= spec.widthPx && inside(x, y))
            // …and, rendered unclipped, nothing in the timeline strip inks where the corner would cut it.
            val stripBottom = (CarbonStyle.DAYLIGHT_H * spec.heightPx / spec.layout.totalRows.toFloat()).toInt()
            for (y in 0 until stripBottom) for (x in 0 until spec.widthPx) {
                if (inside(x + 0.5f, y + 0.5f)) continue
                assertTrue("$name ${hours}h +${step}h: timeline ink at ($x,$y) would be clipped", (bmp.getPixel(x, y) ushr 24) < 12)
            }
        }
    }

    @Test
    fun windIconMirrorsTheWatchMask() {
        // Scaled to one row per watch pixel, the vector must cover the watch's lit pixels and stay out of its gaps.
        val sc = 20; val bmp = Bitmap.createBitmap(14 * sc, 12 * sc, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.scale(sc.toFloat(), sc.toFloat())
        ModernRenderer.icon(c, WidgetRenderer.EventKind.WIND, 7f, 5f, 10f, 0xFF23A047.toInt(), 0.5f)
        var lit = 0; var stray = 0
        for (my in 0 until 10) for (mx in 0 until 12) {
            val on = CarbonStyle.MASK_WIND[my] and (1 shl mx) != 0
            val a = bmp.getPixel(((1 + mx + 0.5f) * sc).toInt(), ((1 + my + 0.5f) * sc).toInt()) ushr 24
            if (on && a > 100) lit++
            if (!on && a > 200 && (my == 4 || my == 5)) stray++
        }
        val total = CarbonStyle.MASK_WIND.sumOf { Integer.bitCount(it) }
        assertTrue("wind vector covers $lit of $total watch pixels", lit >= total - 2)
        assertEquals("nothing between the two gusts", 0, stray)
    }

    @Test
    fun temperatureLabelsHaveEqualInkGapsAndShareARightEdge() {
        val faces = listOf(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.create("sans-serif-condensed", 0),
            android.graphics.Typeface.SERIF, android.graphics.Typeface.MONOSPACE)
        for (tf in faces) for (lh in listOf(56f, 72f, 96f, 140f)) for (t in listOf(listOf("26", "23", "13"), listOf("4", "-1", "-12"), listOf("31", "7", "6"))) {
            val paints = listOf(15f to false, 23f to true, 15f to false).map { (sz, b) ->
                android.graphics.Paint().apply { typeface = if (b) android.graphics.Typeface.create(tf, 1) else tf; textSize = sz } }
            for (snap in listOf(false, true)) {
                val pad = TempLabels.pad(lh, 1.5f)
                val pl = TempLabels.place(t, paints, 35f, 0f, lh - 4.5f, pad, 1f, snap)
                // Spread over the whole band: high's ink pad below the top, low's ink pad above the ticks.
                assertEquals("top padding $t lh=$lh snap=$snap", pad, pl[0].inkTop, if (snap) 1.5f else 0.001f)
                assertEquals("bottom padding $t lh=$lh snap=$snap", lh - 4.5f - pad, pl[2].inkBottom, if (snap) 1.5f else 0.001f)
                val g1 = pl[1].inkTop - pl[0].inkBottom; val g2 = pl[2].inkTop - pl[1].inkBottom
                assertEquals("gaps $t lh=$lh snap=$snap", g1, g2, 0.001f)
                assertTrue(g1 >= 1f)
                val mid = (pl[0].inkBottom + pl[2].inkTop) / 2f; val curMid = (pl[1].inkTop + pl[1].inkBottom) / 2f
                assertEquals("current centred", mid, curMid, 0.001f)
                for (q in pl) assertEquals("right edge", 35f, q.inkRight, if (snap) 0.5f else 0.001f)
                // Verify against the rendered ink, not just the metrics.
                val bmp = Bitmap.createBitmap(60, lh.toInt() + 20, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
                for ((i, q) in pl.withIndex()) c.drawText(q.text, q.x, q.baseline + 10, paints[i].apply { color = android.graphics.Color.BLACK })
                val rows = (0 until bmp.height).map { y -> (0 until bmp.width).any { x -> (bmp.getPixel(x, y) ushr 24) >= TempLabels.INK_ALPHA } }
                val runs = ArrayList<IntRange>(); var s0 = -1
                for (y in rows.indices) { if (rows[y] && s0 < 0) s0 = y; if (!rows[y] && s0 >= 0) { runs.add(s0 until y); s0 = -1 } }
                if (runs.size == 3) assertTrue("rendered gaps $runs $t lh=$lh tf=$tf ${pl.map { "${it.inkTop}-${it.inkBottom}" }}", abs((runs[1].first - runs[0].last) - (runs[2].first - runs[1].last)) <= 1)
            }
        }
    }

    @Test
    fun temperatureLabelsUseNearlyTheFullChartHeightWithoutClipping() {
        // Chart heights in rows: 4x2 (DEFAULT), 4x3/4x4 (TALL), the expanded screen (DETAIL), and Classic watch pixels.
        val heights = listOf(WidgetLayout.DEFAULT.tempH, WidgetLayout.TALL.tempH, WidgetLayout.DETAIL.tempH, 52, 70).map { it.toFloat() }
        val faces = listOf(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.SERIF, android.graphics.Typeface.MONOSPACE,
            android.graphics.Typeface.create("sans-serif-condensed", 0))
        for (tf in faces) for (lh in heights) for (t in listOf(listOf("6", "6", "4"), listOf("26", "23", "13"), listOf("-3", "-8", "-12"))) for (snap in listOf(false, true)) {
            val sm = if (lh > 70f) 15f else 13.5f; val md = if (lh > 70f) 23f else 20f
            val paints = listOf(sm to false, md to true, sm to false).map { (sz, b) ->
                android.graphics.Paint().apply { typeface = if (b) android.graphics.Typeface.create(tf, 1) else tf; textSize = sz } }
            val bottom = lh - 2.4f
            val pad = TempLabels.pad(lh, 1.5f)
            // Small, even padding: 4 % of the chart (at least 1.5), never the old 7 %.
            assertTrue("pad $pad lh=$lh", pad >= 1.5f && pad <= maxOf(lh * 0.04f, 1.5f) + 0.001f)
            val pl = TempLabels.place(t, paints, 35f, 0f, bottom, pad, 1.6f, snap)
            val tol = if (snap) 1.5f else 0.01f
            assertEquals("top pad $t lh=$lh", pad, pl[0].inkTop, tol)
            assertEquals("bottom pad $t lh=$lh", bottom - pad, pl[2].inkBottom, tol)
            // No ink touching or crossing the edges.
            assertTrue("ink clear of the top $t lh=$lh", pl[0].inkTop >= 1f)
            assertTrue("ink clear of the ticks $t lh=$lh", pl[2].inkBottom <= bottom - 1f)
            val g1 = pl[1].inkTop - pl[0].inkBottom; val g2 = pl[2].inkTop - pl[1].inkBottom
            assertEquals("equal gaps $t lh=$lh", g1, g2, 0.001f)
            // The stack spans the band minus the padding (at least 88 % of the chart height).
            assertTrue("span ${pl[2].inkBottom - pl[0].inkTop} of $lh", pl[2].inkBottom - pl[0].inkTop >= 0.88f * lh - 2.4f - 1.5f)
        }
    }

    @Test
    fun labelBandStopsAboveTheTallestTickUnderTheLabelsOnly() {
        val xs = listOf(10f, 20f, 30f, 40f, 50f); val lens = listOf(2.4f, 2.4f, 2.4f, 4.5f, 2.4f)
        assertEquals(97.6f, TempLabels.bandBottom(100f, xs, lens, 5f, 35f), 0.001f)
        assertEquals(95.5f, TempLabels.bandBottom(100f, xs, lens, 5f, 45f), 0.001f)
        assertEquals(100f, TempLabels.bandBottom(100f, xs, lens, 55f, 60f), 0.001f)
    }

    @Test
    fun noDotOnTheTemperatureCurveAtTheNowLine() {
        // Now line on the chart (needle 2) vs off (needle 1): in the temperature block they may differ
        // only along the thin line, not in a disc around the current temperature.
        val on = render(WidgetSettings(needle = 2)); val off = render(WidgetSettings(needle = 1))
        val ys = rowsPx(on, WidgetLayout.DEFAULT.tempY, WidgetLayout.DEFAULT.totalRows)
        var x0 = Int.MAX_VALUE; var x1 = -1
        for (y in ys) for (x in 0 until on.width) if (on.getPixel(x, y) != off.getPixel(x, y)) { x0 = minOf(x0, x); x1 = maxOf(x1, x) }
        assertTrue("now line drawn", x1 >= 0)
        val rowPx = on.height.toFloat() / WidgetLayout.DEFAULT.totalRows
        assertTrue("only the line differs: ${x1 - x0 + 1} px wide (row $rowPx px)", x1 - x0 + 1 <= (3f * rowPx).roundToInt() + 2)
    }
}
