package com.nilsreichhard.carbonion.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The widget picker preview (res/drawable-nodpi/widget_preview.png, light, and
 * res/drawable-night-nodpi/widget_preview.png, dark; layout/widget_preview_layout.xml shows the same on
 * Android 12+) is rendered by the real WidgetUpdater.build + renderer, so it can't drift from the widget:
 * this test fails when the committed PNGs no longer match what the code draws.
 *
 * Regenerate after a visual change:
 *   ./gradlew :app:testDebugUnitTest --tests '*WidgetPreviewTest*' -Pcarbon.updatePreview=true
 * (fresh renders are always written to build/outputs/samples/widget_preview[_night].png).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetPreviewTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** Pixel Launcher 4x3 (targetCellWidth/Height in carbon_widget_info.xml) at xxhdpi. */
    private val size = WidgetUpdater.Size(360, 344)

    private fun render(light: Boolean): Bitmap {
        android.provider.Settings.System.putString(ctx.contentResolver, android.provider.Settings.System.TIME_12_24, "24")
        val dict = JSONObject().apply { put("SETTING_LIGHT_THEME", if (light) 1 else 0); put("SETTING_TEMP_UNIT", 0) }.toString()
        WidgetStore.saveWeather(ctx, Previews.payloadJson(), "Villars-sur-Ollon", dict)
        WidgetStore.saveBackground(ctx, WidgetStore.Background())
        WidgetStore.saveUi(ctx, UiOptions())   // the default look: Modern
        val p = WidgetStore.payload(ctx, true)!!
        // Shortly after the sample's fetch, so the data is current, not stale / expired.
        val now = p.fetchTime * 1000 + 8 * 60_000L
        val battery = WidgetRenderer.Battery(64, false, now / 1000, 64, 900)
        val view = WidgetUpdater.build(ctx, size, 1, "Thu 06:30", useAlarmOverride = true, nowMillis = now,
            batteryOverride = battery, useBatteryOverride = true).apply(ctx, FrameLayout(ctx))
        // The live clock / date are TextClocks: pin them to the sample's own time (Robolectric's clock is 1970).
        val zurich = java.util.TimeZone.getTimeZone("Europe/Zurich")
        val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).apply { timeZone = zurich }.format(java.util.Date(now))
        val date = java.text.SimpleDateFormat("EEEE, MMM d", java.util.Locale.US).apply { timeZone = zurich }.format(java.util.Date(now))
        view.findViewById<TextClock>(R.id.clock).apply { format24Hour = "'$clock'"; format12Hour = format24Hour }
        view.findViewById<TextClock>(R.id.date).apply { format24Hour = "'$date'"; format12Hour = format24Hour }
        val d = ctx.resources.displayMetrics.density
        val w = (size.wDp * d).roundToInt(); val h = (size.hDp * d).roundToInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(out))
        return out
    }

    private fun check(light: Boolean) {
        val name = if (light) "widget_preview" else "widget_preview_night"
        val fresh = render(light)
        File("build/outputs/samples").mkdirs()
        File("build/outputs/samples/$name.png").outputStream().use { fresh.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val committed = File(if (light) "src/main/res/drawable-nodpi/widget_preview.png" else "src/main/res/drawable-night-nodpi/widget_preview.png")
        if (System.getProperty("carbon.updatePreview") == "true") {
            committed.parentFile!!.mkdirs()
            committed.outputStream().use { fresh.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return
        }
        val regen = "./gradlew :app:testDebugUnitTest --tests '*WidgetPreviewTest*' -Pcarbon.updatePreview=true"
        assertTrue("missing ${committed.path}; run $regen", committed.isFile)
        val old = BitmapFactory.decodeFile(committed.path)
        assertEquals("${committed.path} size differs from the renderer; run $regen", fresh.width to fresh.height, old.width to old.height)
        // Small tolerance for font rasterisation differences between machines.
        var diff = 0
        for (y in 0 until fresh.height step 2) for (x in 0 until fresh.width step 2) {
            val a = fresh.getPixel(x, y); val b = old.getPixel(x, y)
            if ((0..24 step 8).any { abs((a ushr it and 255) - (b ushr it and 255)) > 48 }) diff++
        }
        val frac = diff.toDouble() / ((fresh.width / 2) * (fresh.height / 2))
        assertTrue("${committed.path} is out of date (%.2f %% of pixels differ from the current renderer); run $regen".format(frac * 100), frac < 0.005)
    }

    @Test fun lightPreviewMatchesRenderer() = check(light = true)

    @Test fun darkPreviewMatchesRenderer() = check(light = false)

    @Test
    fun previewIsCurrentModernLook() {
        val b = render(light = false)
        // Dark theme on transparent: light ink somewhere in the clock band, nothing opaque behind it.
        val h = b.height; val w = b.width
        assertTrue((w / 4 until w * 3 / 4).any { val px = b.getPixel(it, h / 2); px ushr 24 > 200 && (px shr 16 and 255) > 180 })
        assertEquals(0, b.getPixel(2, h / 2) ushr 24)
    }
}
