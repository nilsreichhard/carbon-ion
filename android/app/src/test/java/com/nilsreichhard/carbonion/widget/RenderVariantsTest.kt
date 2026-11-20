package com.nilsreichhard.carbonion.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import java.util.TimeZone

/** Chart bitmap variants (WidgetRenderer only), PNGs in build/outputs/preview/. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderVariantsTest {
    private val tz = TimeZone.getTimeZone("Europe/Zurich")
    private val p = WeatherPayload.fromJson(Previews.payloadJson())
    private val now = p.fetchTime * 1000 + 5 * 60_000
    private val spec = WidgetRenderer.Spec(widthRows = 360)

    private fun render(s: WidgetSettings, sp: WidgetRenderer.Spec = spec) = WidgetRenderer.render(p, s, sp, now, tz)

    private fun inked(b: Bitmap, y0: Int, y1: Int): Int {
        var n = 0
        for (y in y0 until y1 step 2) for (x in 0 until b.width step 2) if (b.getPixel(x, y) ushr 24 != 0) n++
        return n
    }

    private fun over(b: Bitmap, dark: Boolean): Bitmap {
        val out = Previews.wallpaper(b.width, b.height, dark)
        Canvas(out).drawBitmap(b, 0f, 0f, null)
        return out
    }

    @Test
    fun parsesPayloadWithCloudLayers() {
        assertEquals(72, p.tempHourly.size)
        assertEquals(72, p.cloudLow.size)
        assertTrue(p.fetchTime > 0)
    }

    @Test
    fun lightAndDarkThemes() {
        val light = render(WidgetSettings(lightTheme = true))
        val dark = render(WidgetSettings(lightTheme = false))
        Previews.save(over(light, false), "chart-light")
        Previews.save(over(dark, true), "chart-dark-transparent")
        // Background stays transparent (wallpaper shows through) in the clock band.
        assertEquals(0, light.getPixel(light.width / 2, (100 * CarbonStyle.RENDER_SCALE).toInt()) ushr 24)
        assertTrue("chart blank", inked(light, 0, light.height) > 2000)
        assertTrue("themes look the same", !light.sameAs(dark))
        val sc = CarbonStyle.RENDER_SCALE.toInt()
        Previews.save(Previews.zoom(over(dark, true), 0, 0, 120 * sc, 75 * sc, 3), "chart-dark-zoom-top")
    }

    @Test
    fun splitClouds() {
        val total = render(WidgetSettings())
        val split = render(WidgetSettings(cloudSplit = true))
        Previews.save(over(split, false), "chart-split")
        assertNotEquals(inked(total, 0, 80), inked(split, 0, 80))
    }

    @Test
    fun rainAmountModes() {
        val dots = render(WidgetSettings(rainHeight = 1))
        val line = render(WidgetSettings(rainHeight = 2))
        Previews.save(over(dots, false), "chart-amount-dots")
        Previews.save(over(line, false), "chart-amount-line")
        // The Amount+line mode draws the orange intensity line.
        var orange = 0
        for (y in 0 until (73 * CarbonStyle.RENDER_SCALE).toInt()) for (x in 0 until line.width) {
            val c = line.getPixel(x, y)
            if ((c ushr 24) == 255 && (c shr 16 and 255) > 200 && (c shr 8 and 255) in 60..190 && (c and 255) < 80) orange++
        }
        assertTrue("no orange line", orange > 20)
    }

    @Test
    fun alarmFormat() {
        val c = java.util.Calendar.getInstance(Locale.US).apply { set(2026, 10, 19, 5, 55, 0) } // Thu
        val twoDaysBefore = c.timeInMillis - 2 * NextAlarm.DAY_MS
        assertEquals("Thu 05:55", NextAlarm.format(c.timeInMillis, twoDaysBefore, true, 0, Locale.US))
        assertEquals("Thu 5:55", NextAlarm.format(c.timeInMillis, twoDaysBefore, true, 2, Locale.US))
        assertEquals("Thu 5:55 AM", NextAlarm.format(c.timeInMillis, twoDaysBefore, false, 0, Locale.US))
        assertEquals("HH:mm", WidgetSettings.clockPattern(true, 0))
        assertEquals("h:mm", WidgetSettings.clockPattern(false, 0))
        assertEquals("EEEE, MMM d", WidgetSettings.datePattern("%A, %b %d"))
    }
}
