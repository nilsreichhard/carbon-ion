package com.nilsreichhard.carbonion.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.TimeZone

/** 1.1.1: phone battery markers 20 / 10 / 5 / 0 % (Android only; the watch keeps 20 / 10 / 4 / 0 %). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BatteryOptionsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val tz = TimeZone.getTimeZone("Europe/Zurich")
    private val p = WeatherPayload.fromJson(Previews.payloadJson())
    private val now = p.fetchTime * 1000 + 5 * 60_000
    /** 30 %, 1 % per 10 min: 20 % in 1h40, 10 % in 3h20, 5 % in 4h10, 0 % in 5h. */
    private val batt = WidgetRenderer.Battery(30, false, now / 1000, 30, 600)

    @After fun tearDown() = JvmQuickJs.reset()

    @Test
    fun levelsAndOldValues() {
        assertEquals(listOf(20, 10, 5, 0), WidgetSettings.batteryLevels(0))
        assertEquals(listOf(10, 5, 0), WidgetSettings.batteryLevels(1))
        assertEquals(listOf(5, 0), WidgetSettings.batteryLevels(2))
        assertEquals(listOf(20, 0), WidgetSettings.batteryLevels(6))
        assertEquals(listOf(10, 0), WidgetSettings.batteryLevels(7))
        assertEquals(listOf(0), WidgetSettings.batteryLevels(5))
        assertEquals("watch None", listOf(0), WidgetSettings.batteryLevels(3))
        assertEquals("watch 10 / 4 %", listOf(10, 5, 0), WidgetSettings.batteryLevels(4))
        assertEquals(listOf(20, 10, 5, 0), WidgetSettings().batteryLevels)
        assertEquals(1, WidgetSettings.fromDict("{\"SETTING_TIMELINE_BATTERY\":4}").timelineBattery)
        assertEquals(5, WidgetSettings.fromDict("{\"SETTING_TIMELINE_BATTERY\":5}").timelineBattery)
        assertEquals(5, WidgetSettings.fromDict("{\"SETTING_TIMELINE_BATTERY\":3}").timelineBattery)
        assertEquals(7, WidgetSettings.fromDict("{\"SETTING_TIMELINE_BATTERY\":7}").timelineBattery)
        assertEquals(0, WidgetSettings.fromDict("{\"SETTING_TIMELINE_BATTERY\":9}").timelineBattery)
        assertEquals(0, WidgetSettings.fromDict("{}").timelineBattery)
    }

    private fun differ(a: Bitmap, b: Bitmap): Int {
        var n = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getPixel(x, y) != b.getPixel(x, y)) n++
        return n
    }

    @Test
    fun fivePercentMarkerInBothStyles() {
        for (light in listOf(false, true)) {
            fun classic(tb: Int) = WidgetRenderer.render(p, WidgetSettings(lightTheme = light, timelineBattery = tb), WidgetRenderer.Spec(battery = batt), now, tz)
            fun modern(tb: Int) = ModernRenderer.render(p, WidgetSettings(lightTheme = light, timelineBattery = tb), ModernRenderer.Spec(1080, 1032, 3f, battery = batt), now, tz)
            for ((name, r) in listOf("classic" to ::classic, "modern" to ::modern)) {
                val bm = (0..7).associateWith { r(it) }
                assertTrue("$name 5 % marker", differ(bm[2]!!, bm[5]!!) > 20)          // 5/0 vs 0 only
                assertTrue("$name 20 % marker", differ(bm[6]!!, bm[5]!!) > 20)         // 20/0 vs 0 only
                assertTrue("$name 10 % marker", differ(bm[7]!!, bm[5]!!) > 20)         // 10/0 vs 0 only
                assertTrue("$name 20 vs 10", differ(bm[6]!!, bm[7]!!) > 20)
                assertTrue("$name 20 % marker", differ(bm[0]!!, bm[1]!!) > 20)         // 20/10/5/0 vs 10/5/0
                assertEquals("$name old 10 / 4 = 10 / 5 / 0", 0, differ(bm[4]!!, bm[1]!!))
                assertEquals("$name old None = 0 only", 0, differ(bm[3]!!, bm[5]!!))
                Previews.save(bm[0]!!, "battery_${name}_${if (light) "light" else "dark"}_all")
            }
        }
    }

    @Test
    fun androidPageHasPhoneOptionsAndWatchConfigIsUnchanged() {
        assumeTrue("desktop QuickJS library missing", JvmQuickJs.install())
        JsRuntime.store(ctx).edit().clear().putString("clay-settings", "{\"SETTING_TIMELINE_BATTERY\":\"4\"}").commit()
        JsRuntime(ctx).use { js ->
            val items = JSONArray(js.call("carbonSettingsInit", "{}"))
            val b = (0 until items.length()).map { items.getJSONObject(it) }.first { it.optString("key") == "SETTING_TIMELINE_BATTERY" }
            val opts = b.getJSONArray("options").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("value") to o.getString("label") } } }
            assertEquals(listOf("0" to "20 / 10 / 5 / 0 %", "1" to "10 / 5 / 0 %", "2" to "5 / 0 %", "6" to "20 / 0 %", "7" to "10 / 0 %", "5" to "0 % only"), opts)
            assertEquals("old 10 / 4 % → 10 / 5 / 0 %", "1", b.getString("value"))
            assertFalse(b.getString("description").contains("4 %"))
            assertEquals(1, JSONObject(js.call("carbonSettingsDict")).getInt("SETTING_TIMELINE_BATTERY"))
            js.call("carbonSettingsChange", "SETTING_TIMELINE_BATTERY", "\"7\"")
            assertEquals(7, JSONObject(js.call("carbonSettingsSave", "{}")).getInt("SETTING_TIMELINE_BATTERY"))
        }
        JsRuntime.store(ctx).edit().clear().putString("clay-settings", "{\"SETTING_TIMELINE_BATTERY\":\"3\"}").commit()
        JsRuntime(ctx).use { js -> assertEquals("old None → 0 % only", 5, JSONObject(js.call("carbonSettingsDict")).getInt("SETTING_TIMELINE_BATTERY")) }
        // The watch's own Clay list keeps 20 / 10 / 4 / 0 %.
        val cfg = File("../../src/pkjs/config.js").readText()
        for (l in listOf("20 / 10 / 4 / 0 %", "10 / 4 / 0 %", "4 / 0 %", "10 / 4 %")) assertTrue(l, cfg.contains("\"$l\""))
        assertFalse(cfg.contains("5 / 0 %"))
    }
}
