package com.nilsreichhard.carbonion.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Switch
import android.widget.TextClock
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * 4.0.0 (Nils, "hide the feels like line"): SETTING_FEELS_LIKE, the same key and default (on) as the watch.
 * Off: Modern and Classic draw no dashed feels-like line and scale the chart to the real temperature only,
 * so the chart no longer depends on the apparent-temperature data at all. Previews (line on / off, Modern +
 * Classic, the whole widget and the settings row) go to build/outputs/preview/feels_*.png.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeelsLikeToggleTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val tz = TimeZone.getTimeZone("Europe/Zurich")

    @After fun tearDown() = JvmQuickJs.reset()

    /** Sample payload with the feels-like curve shifted by [shift] degrees from the real temperature. */
    private fun payload(shift: Double): WeatherPayload {
        val o = JSONObject(Previews.payloadJson())
        val t = o.getJSONArray("temp_hourly")
        val a = JSONArray().apply { for (i in 0 until t.length()) put(t.getDouble(i) + shift) }
        return WeatherPayload.fromJson(o.put("apparent_temp_hourly", a))
    }

    private fun modern(p: WeatherPayload, s: WidgetSettings) =
        ModernRenderer.render(p, s, ModernRenderer.Spec(1080, 1032, 3f), p.fetchTime * 1000 + 5 * 60_000, tz)
    private fun classic(p: WeatherPayload, s: WidgetSettings) =
        WidgetRenderer.render(p, s, WidgetRenderer.Spec(widthRows = 360), p.fetchTime * 1000 + 5 * 60_000, tz)

    @Test
    fun settingsDefaultAndParsing() {
        assertTrue(WidgetSettings().showFeelsLike)
        assertTrue(WidgetSettings.fromDict("{}").showFeelsLike)                       // older saved settings keep the line
        assertTrue(WidgetSettings.fromDict("{\"SETTING_FEELS_LIKE\":1}").showFeelsLike)
        assertFalse(WidgetSettings.fromDict("{\"SETTING_FEELS_LIKE\":0}").showFeelsLike)
        assertTrue(WidgetSettings.fromDict("{\"SETTING_FEELS_LIKE\":0}").showAlarm)   // nothing else changes
    }

    @Test
    fun offRemovesTheLineInBothStyles() {
        val near = payload(-3.0); val far = payload(-9.0)
        val on = WidgetSettings(lightTheme = true); val off = on.copy(showFeelsLike = false)
        // On: the chart follows the feels-like data. Off: identical whatever the feels-like data is.
        assertFalse(modern(near, on).sameAs(modern(far, on)))
        assertTrue(modern(near, off).sameAs(modern(far, off)))
        assertFalse(classic(near, on).sameAs(classic(far, on)))
        assertTrue(classic(near, off).sameAs(classic(far, off)))
        // And off differs from on (the line and the wider scale are gone).
        assertFalse(modern(far, on).sameAs(modern(far, off)))
        assertFalse(classic(far, on).sameAs(classic(far, off)))
        val p = WeatherPayload.fromJson(Previews.payloadJson())
        for ((name, s) in listOf("on" to on, "off" to off)) {
            Previews.save(modern(p, s), "feels_modern_light_$name")
            Previews.save(classic(p, s), "feels_classic_light_$name")
        }
    }

    @Test
    fun settingsPageToggleAfterTemperatureFill() {
        JvmQuickJs.require()
        JsRuntime.store(ctx).edit().clear().commit()
        JsRuntime(ctx).use { js ->
            val items = JSONArray(js.call("carbonSettingsInit", "{}")).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            val keys = items.map { it.optString("key") }
            val at = keys.indexOf("SETTING_FEELS_LIKE")
            assertEquals("SETTING_INFILL_MODE", keys[at - 1])
            assertEquals("SETTING_NEEDLE_MODE", keys[at + 1])
            val it = items[at]
            assertEquals("toggle", it.getString("type")); assertEquals("Show feels-like line", it.getString("label"))
            assertTrue(it.getBoolean("value")); assertFalse(it.getBoolean("hidden"))
            assertEquals(1, JSONObject(js.call("carbonSettingsDict")).getInt("SETTING_FEELS_LIKE"))
            js.call("carbonSettingsChange", "SETTING_FEELS_LIKE", "false")
            val saved = JSONObject(js.call("carbonSettingsSave", "{}"))
            assertEquals(0, saved.getInt("SETTING_FEELS_LIKE"))
            assertFalse(WidgetSettings.fromDict(saved.toString()).showFeelsLike)
            assertEquals(0, JSONObject(js.call("carbonSettingsDict")).getInt("SETTING_FEELS_LIKE"))
        }
    }

    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    @Test
    fun settingsScreenRowPreview() {
        JvmQuickJs.require()
        JsRuntime.store(ctx).edit().clear().commit()
        val ctl = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val act = ctl.get()
        val content = ((act.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)) as ViewGroup).getChildAt(0)
        val w = (411 * act.resources.displayMetrics.density).toInt()
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        content.layout(0, 0, w, content.measuredHeight)
        val sw = all(content).filterIsInstance<Switch>().single { it.text.toString() == "Show feels-like line" }
        assertTrue(sw.isChecked)
        val loc = IntArray(2); var y = 0; var v: View = sw
        while (v !== content) { y += v.top; v = v.parent as View }
        loc[1] = y
        val b = Bitmap.createBitmap(w, content.measuredHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Color.WHITE); content.draw(c)
        val d = act.resources.displayMetrics.density
        val top = (loc[1] - 260 * d).roundToInt().coerceAtLeast(0)
        val h = ((420 * d).roundToInt()).coerceAtMost(b.height - top)
        Previews.save(Bitmap.createBitmap(b, 0, top, w, h), "feels_settings_row")
        ctl.pause().stop().destroy()
    }

    @Test
    fun wholeWidgetPreviews() {
        android.provider.Settings.System.putString(ctx.contentResolver, android.provider.Settings.System.TIME_12_24, "24")
        val size = WidgetUpdater.Size(360, 344)
        for (style in UiOptions.Style.values()) for (on in listOf(true, false)) {
            WidgetStore.saveUi(ctx, UiOptions(style = style))
            WidgetStore.saveBackground(ctx, WidgetStore.Background())
            WidgetStore.saveWeather(ctx, Previews.payloadJson(), "Ollon",
                JSONObject().put("SETTING_LIGHT_THEME", 1).put("SETTING_FEELS_LIKE", if (on) 1 else 0).toString())
            assertEquals(on, WidgetStore.settings(ctx).showFeelsLike)
            val now = WidgetStore.payload(ctx, true)!!.fetchTime * 1000 + 8 * 60_000L
            val view = WidgetUpdater.build(ctx, size, 1, null, useAlarmOverride = true, nowMillis = now).apply(ctx, FrameLayout(ctx))
            view.findViewById<TextClock>(R.id.clock).apply { format24Hour = "'14:09'"; format12Hour = "'2:09'" }
            view.findViewById<TextClock>(R.id.date).apply { format24Hour = "'Wednesday, Nov 18'"; format12Hour = format24Hour }
            val d = ctx.resources.displayMetrics.density
            val w = (size.wDp * d).roundToInt(); val h = (size.hDp * d).roundToInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            val pad = (16 * d).roundToInt()
            val out = Previews.wallpaper(w + 2 * pad, h + 2 * pad, false)
            Canvas(out).apply { translate(pad.toFloat(), pad.toFloat()); view.draw(this) }
            Previews.save(out, "feels_widget_${style.key}_light_${if (on) "on" else "off"}")
        }
    }
}
