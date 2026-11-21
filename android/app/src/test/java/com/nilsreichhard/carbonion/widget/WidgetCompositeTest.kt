package com.nilsreichhard.carbonion.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.TextClock
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * The whole widget as the launcher would show it: the real RemoteViews from
 * WidgetUpdater.build applied and drawn over a sample wallpaper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetCompositeTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val size = WidgetUpdater.Size(380, 210)

    private fun dict(light: Boolean, extra: Map<String, Int> = emptyMap()) = JSONObject().apply {
        put("SETTING_LIGHT_THEME", if (light) 1 else 0)
        extra.forEach { (k, v) -> put(k, v) }
    }.toString()

    private fun composite(name: String, light: Boolean, darkWall: Boolean, bg: WidgetStore.Background,
                          alarm: String?, extra: Map<String, Int> = emptyMap()): Bitmap {
        // 24-hour phone, like the reference lock screen (14:09).
        android.provider.Settings.System.putString(ctx.contentResolver, android.provider.Settings.System.TIME_12_24, "24")
        WidgetStore.saveWeather(ctx, Previews.payloadJson(), "Ollon", dict(light, extra))
        WidgetStore.saveBackground(ctx, bg)
        // Shortly after the sample's fetch, so the data is current (1.1.6 shows "--" once it expires).
        val now = WidgetStore.payload(ctx, true)!!.fetchTime * 1000 + 8 * 60_000L
        val rv = WidgetUpdater.build(ctx, size, 1, alarm, useAlarmOverride = true, nowMillis = now)
        val parent = FrameLayout(ctx)
        val view = rv.apply(ctx, parent)
        // Fixed time for comparable previews (the reference shows 14:09).
        (view.findViewById<TextClock>(R.id.clock)).apply { format24Hour = "'14:09'"; format12Hour = "'2:09'" }
        (view.findViewById<TextClock>(R.id.date)).apply { format24Hour = "'Wednesday, Nov 18'"; format12Hour = format24Hour }
        val d = ctx.resources.displayMetrics.density
        val w = (size.wDp * d).roundToInt(); val h = (size.hDp * d).roundToInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        val pad = (24 * d).roundToInt()
        val out = Previews.wallpaper(w + 2 * pad, h + 2 * pad, darkWall)
        val c = Canvas(out)
        c.translate(pad.toFloat(), pad.toFloat())
        view.draw(c)
        Previews.save(out, name)
        return out
    }

    @Test
    fun lightTransparentWithAlarm() {
        val b = composite("widget-light-transparent-alarm", true, false, WidgetStore.Background(), "Thu 05:55")
        assertTrue(b.width > 0)
    }

    @Test
    fun darkTransparentOverDarkWallpaper() {
        composite("widget-dark-transparent-alarm", false, true, WidgetStore.Background(), "Thu 05:55")
        composite("widget-dark-transparent-noalarm", false, true, WidgetStore.Background(), null)
    }

    @Test
    fun splitAndAmountLine() {
        composite("widget-dark-split-amountline", false, true, WidgetStore.Background(), "Thu 05:55",
            mapOf("SETTING_CLOUD_DISPLAY_MODE" to 1, "SETTING_RAIN_HEIGHT" to 2))
    }

    @Test
    fun colouredBackground() {
        val bg = WidgetStore.Background(WidgetStore.BG_COLOR, 0xFFFFFF, 85)
        val b = composite("widget-light-white85", true, true, bg, "Thu 05:55")
        assertEquals(0xD8, bg.argb ushr 24)
        // The white panel covers the dark wallpaper at the widget centre-left.
        val px = b.getPixel(b.width / 10, b.height / 2)
        assertTrue((px shr 16 and 255) > 180)
    }
}
