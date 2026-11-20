package com.nilsreichhard.carbonion.widget

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone
import com.nilsreichhard.carbonion.widget.WidgetRenderer.EventKind as K

/** event_layer.c: priorities, spans and the drawn icons. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EventIconsTest {
    private val tz = TimeZone.getTimeZone("Europe/Zurich")

    @Test fun priorities() {
        fun ev(code: Int, strong: Boolean = false, wind: Boolean = false, fog: Boolean = false, flags: Int = 0, fogOn: Boolean = true) =
            WidgetRenderer.hourEvent(code, strong, wind, fog, flags, fogOn)
        assertEquals(K.TORNADO, ev(99, wind = true, fog = true))
        assertEquals(K.STORM, ev(99, flags = CarbonStyle.EVENT_HIDE_TORNADO))
        assertEquals(K.STORM, ev(95, wind = true))
        assertEquals(K.NONE, ev(95, flags = CarbonStyle.EVENT_HIDE_THUNDER))
        assertEquals(K.NONE, ev(95, flags = CarbonStyle.EVENT_THUNDER_STRONG))
        assertEquals(K.STORM, ev(95, strong = true, flags = CarbonStyle.EVENT_THUNDER_STRONG))
        assertEquals(K.STORM, ev(96, flags = CarbonStyle.EVENT_THUNDER_STRONG))
        assertEquals(K.SNOW, ev(71, wind = true))
        assertEquals(K.NONE, ev(71, flags = 0x20))              // snow level "heavy" only
        assertEquals(K.SNOW, ev(75, flags = 0x20))
        assertEquals(K.WIND, ev(61, wind = true, fog = true))
        assertEquals(K.FOG, ev(45, fog = true))
        assertEquals(K.NONE, ev(45, fog = true, fogOn = false))
        assertEquals(K.NONE, ev(3))
    }

    private fun payload(edit: (JSONObject) -> Unit): WeatherPayload {
        val o = JSONObject(Previews.payloadJson())
        val p = if (o.has("payload")) o.getJSONObject("payload") else o
        edit(p)
        return WeatherPayload.fromJson(p.toString())
    }

    private fun setCodes(p: JSONObject, slots: IntRange, code: Int) {
        val a = p.getJSONArray("hourly_weather_code"); for (i in slots) a.put(i, code)
    }

    private fun bits(slots: IntRange) = JSONArray().apply {
        val b = IntArray(9); for (i in slots) b[i shr 3] = b[i shr 3] or (1 shl (i and 7)); b.forEach { put(it) }
    }

    @Test fun spansMergeRunsAndMarkMissingData() {
        val p = payload { setCodes(it, 14..16, 95); it.put("wind_bits", bits(17..19)) }
        val sp = WidgetRenderer.eventSpans(p, WidgetSettings(), 12 - 6, 20)
        assertTrue(sp.toString(), sp.contains(WidgetRenderer.EventSpan(8, 10, K.STORM)))
        assertTrue(sp.toString(), sp.contains(WidgetRenderer.EventSpan(11, 13, K.WIND)))
        assertEquals(K.MISSING, sp.last().kind)
        assertEquals(20, sp.last().start)
    }

    private fun count(b: Bitmap, color: Int, y1: Int): Int {
        var n = 0
        for (y in 0 until y1) for (x in 0 until b.width) if (b.getPixel(x, y) == color) n++
        return n
    }

    @Test fun iconsAreDrawnAndCanBeTurnedOff() {
        val p = payload { setCodes(it, 14..16, 95); it.put("wind_bits", bits(20..23)); it.put("fog_bits", bits(26..28)); setCodes(it, 26..28, 45) }
        val now = p.fetchTime * 1000 + 5 * 60_000
        val spec = WidgetRenderer.Spec(widthRows = 320, scale = 1)
        val pal = CarbonStyle.Palette(true)
        val top = WidgetLayout.DEFAULT.topH
        val on = WidgetRenderer.render(p, WidgetSettings(), spec, now, tz)
        val bolt = count(on, pal.evStorm, top); val wind = count(on, pal.evWind, top)
        assertTrue("bolt pixels $bolt", bolt >= 20)
        assertTrue("wind pixels $wind", wind >= 20)
        assertTrue("fog pixels", count(on, pal.evFog, top) > 10)
        val iconOnly = WidgetRenderer.render(p, WidgetSettings(eventDisplay = 1), spec, now, tz)
        assertTrue("icon only draws fewer storm pixels (no range line)", count(iconOnly, pal.evStorm, top) in 10 until bolt)
        val off = WidgetRenderer.render(p, WidgetSettings(eventDisplay = 2), spec, now, tz)
        assertEquals(0, count(off, pal.evStorm, top))
        val hidden = WidgetRenderer.render(p, WidgetSettings(eventFlags = CarbonStyle.EVENT_HIDE_THUNDER), spec, now, tz)
        assertEquals(0, count(hidden, pal.evStorm, top))
    }

    @Test fun moonPhaseCycles() {
        val full = 1795250000L // within the full moon of 24 Nov 2026
        val newMoon = 1796000000L
        val a = WidgetRenderer.moonPhase(full, tz); val b = WidgetRenderer.moonPhase(newMoon, tz)
        assertTrue("phase in 0..7: $a $b", a in 0..7 && b in 0..7 && a != b)
    }

    /** Split cloud rows (cloud_layer.c prv_draw). */
    @Test fun splitRowsMatchTheWatch() {
        assertEquals(Triple(7, 14, 21), WidgetRenderer.splitRows(28))
        val (a, b, c) = WidgetRenderer.splitRows(14)
        assertTrue(a in 4 until b && b < c && c <= 9)
    }
}
