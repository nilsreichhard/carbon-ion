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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.TimeZone

/** Phone battery markers. 4.0.0: the watch's list and values, 20/10/5/0 (0), 10/5/0 (1), 5/0 (2), 10/0 (7), 0 (5), None (3). */
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
        assertEquals(listOf(0, 1, 2, 7, 5, 3), WidgetSettings.BATTERY_OPTIONS)
        assertEquals(listOf(20, 10, 5, 0), WidgetSettings.batteryLevels(0))
        assertEquals(listOf(10, 5, 0), WidgetSettings.batteryLevels(1))
        assertEquals(listOf(5, 0), WidgetSettings.batteryLevels(2))
        assertEquals(listOf(10, 0), WidgetSettings.batteryLevels(7))
        assertEquals(listOf(0), WidgetSettings.batteryLevels(5))
        assertEquals("None", emptyList<Int>(), WidgetSettings.batteryLevels(3))
        assertEquals("old phone 20 / 0 %", listOf(20, 10, 5, 0), WidgetSettings.batteryLevels(6))
        assertEquals("old phone Off", emptyList<Int>(), WidgetSettings.batteryLevels(8))
        assertEquals("watch 3.3.0 10 / 4 %", listOf(10, 5, 0), WidgetSettings.batteryLevels(4))
        assertEquals(listOf(20, 10, 5, 0), WidgetSettings().batteryLevels)
        for ((v, want) in listOf(0 to 0, 1 to 1, 2 to 2, 3 to 3, 4 to 1, 5 to 5, 6 to 0, 7 to 7, 8 to 3, 9 to 0))
            assertEquals("value $v", want, WidgetSettings.fromDict("{\"SETTING_TIMELINE_BATTERY\":$v}").timelineBattery)
        assertEquals(3, WidgetSettings.fromDict("{\"SETTING_TIMELINE_BATTERY\":\"3\"}").timelineBattery)
        assertEquals(0, WidgetSettings.fromDict("{}").timelineBattery)
    }

    /** "None" (4.0.0: value 3, was the phone-only Off 8) draws no battery marker in either style, exactly like having no battery data. */
    @Test
    fun offHidesAllMarkers() {
        val off = WidgetSettings.BATTERY_NONE
        for (light in listOf(false, true)) {
            val c0 = WidgetRenderer.render(p, WidgetSettings(lightTheme = light, timelineBattery = 0), WidgetRenderer.Spec(battery = batt), now, tz)
            val cOff = WidgetRenderer.render(p, WidgetSettings(lightTheme = light, timelineBattery = off), WidgetRenderer.Spec(battery = batt), now, tz)
            val cNone = WidgetRenderer.render(p, WidgetSettings(lightTheme = light, timelineBattery = 0), WidgetRenderer.Spec(battery = null), now, tz)
            assertTrue(differ(c0, cOff) > 20)
            assertEquals("classic off = no battery", 0, differ(cOff, cNone))
            val m0 = ModernRenderer.render(p, WidgetSettings(lightTheme = light, timelineBattery = 0), ModernRenderer.Spec(1080, 1032, 3f, battery = batt), now, tz)
            val mOff = ModernRenderer.render(p, WidgetSettings(lightTheme = light, timelineBattery = off), ModernRenderer.Spec(1080, 1032, 3f, battery = batt), now, tz)
            val mNone = ModernRenderer.render(p, WidgetSettings(lightTheme = light, timelineBattery = 0), ModernRenderer.Spec(1080, 1032, 3f, battery = null), now, tz)
            assertTrue(differ(m0, mOff) > 20)
            assertEquals("modern off = no battery", 0, differ(mOff, mNone))
        }
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
                val bm = (0..8).associateWith { r(it) }
                assertTrue("$name 5 % marker", differ(bm[2]!!, bm[5]!!) > 20)          // 5/0 vs 0
                assertTrue("$name 10 % marker", differ(bm[7]!!, bm[5]!!) > 20)         // 10/0 vs 0
                assertTrue("$name 0 % marker", differ(bm[5]!!, bm[3]!!) > 20)          // 0 vs None
                assertTrue("$name 20 % marker", differ(bm[0]!!, bm[1]!!) > 20)         // 20/10/5/0 vs 10/5/0
                assertTrue("$name 5 % in 10/5/0", differ(bm[1]!!, bm[7]!!) > 20)       // 10/5/0 vs 10/0
                assertEquals("$name old 10 / 4 = 10 / 5 / 0", 0, differ(bm[4]!!, bm[1]!!))
                assertEquals("$name old 20 / 0 = 20 / 10 / 5 / 0", 0, differ(bm[6]!!, bm[0]!!))
                assertEquals("$name old Off = None", 0, differ(bm[8]!!, bm[3]!!))
                Previews.save(bm[0]!!, "battery_${name}_${if (light) "light" else "dark"}_all")
            }
        }
    }

    @Test
    fun androidPageUsesTheWatchListAndMigratesOldValues() {
        JvmQuickJs.require()
        val expected = listOf("0" to "20 / 10 / 5 / 0 %", "1" to "10 / 5 / 0 %", "2" to "5 / 0 %", "7" to "10 / 0 %", "5" to "0 %", "3" to "None")
        JsRuntime.store(ctx).edit().clear().putString("clay-settings", "{\"SETTING_TIMELINE_BATTERY\":\"4\"}").commit()
        JsRuntime(ctx).use { js ->
            val items = JSONArray(js.call("carbonSettingsInit", "{}"))
            val b = (0 until items.length()).map { items.getJSONObject(it) }.first { it.optString("key") == "SETTING_TIMELINE_BATTERY" }
            val opts = b.getJSONArray("options").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("value") to o.getString("label") } } }
            assertEquals(expected, opts)
            assertEquals("old 10 / 4 % → 10 / 5 / 0 %", "1", b.getString("value"))
            assertTrue(b.getString("description").contains("phone battery"))
            assertFalse(b.getString("description").contains("saver"))
            assertEquals(1, JSONObject(js.call("carbonSettingsDict")).getInt("SETTING_TIMELINE_BATTERY"))
            js.call("carbonSettingsChange", "SETTING_TIMELINE_BATTERY", "\"7\"")
            assertEquals(7, JSONObject(js.call("carbonSettingsSave", "{}")).getInt("SETTING_TIMELINE_BATTERY"))
            js.call("carbonSettingsChange", "SETTING_TIMELINE_BATTERY", "\"3\"")
            val saved = JSONObject(js.call("carbonSettingsSave", "{}"))
            assertEquals("None survives the shared settings.js dict", 3, saved.getInt("SETTING_TIMELINE_BATTERY"))
            assertEquals(emptyList<Int>(), WidgetSettings.fromDict(saved.toString()).batteryLevels)
        }
        // Old phone-only values in storage: 20 / 0 % (6) → 20 / 10 / 5 / 0 %, Off (8) → None; 0 % only (5) stays "0 %".
        for ((old, want) in listOf("6" to 0, "8" to 3, "5" to 5, "2" to 2)) {
            JsRuntime.store(ctx).edit().clear().putString("clay-settings", "{\"SETTING_TIMELINE_BATTERY\":\"$old\"}").commit()
            JsRuntime(ctx).use { js -> assertEquals("stored $old", want, JSONObject(js.call("carbonSettingsDict")).getInt("SETTING_TIMELINE_BATTERY")) }
        }
        // The page shows exactly the watch's own Clay list.
        val cfg = File("../../src/pkjs/config.js").readText()
        for ((_, l) in expected) assertTrue(l, cfg.contains("\"label\": \"$l\""))
        assertFalse(cfg.contains("battery saver", ignoreCase = true))
    }
}
