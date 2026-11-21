package com.nilsreichhard.carbonion.widget

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * 1.1.7 (Nils 14:51): crops of the label column at several now-line positions (the now line sits at
 * past / total of the chart, so it moves with the widget's width), light and dark, Modern and Classic.
 * Writes build/outputs/preview/shift_<tag>_*.png; tag = -Pcarbon.previewName (default "after").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LabelShiftPreviewTest {
    @Test
    fun crops() {
        val tag = System.getProperty("carbon.previewName").takeUnless { it.isNullOrEmpty() } ?: "after"
        val tz = TimeZone.getTimeZone("Europe/Zurich")
        for ((hi, cur, lo) in listOf(Triple(6, 5, 1), Triple(-3, -12, -15))) {
            val o = JSONObject(Previews.payloadJson())
            val t = JSONArray().apply { for (i in 0 until WeatherPayload.HOURS) put(lo + (hi - lo) * ((i * 7) % 10) / 9.0) }
            o.put("temp_hourly", t).put("apparent_temp_hourly", t).put("current_temp", cur.toDouble())
            val p = WeatherPayload.fromJson(o)
            val now = p.fetchTime * 1000 + 5 * 60_000
            for (light in listOf(true, false)) {
                val th = if (light) "light" else "dark"
                // Modern: narrow 2-cell, default 4x3, wide.
                for (w in listOf(540, 1080, 1600)) {
                    val b = ModernRenderer.render(p, WidgetSettings(lightTheme = light), ModernRenderer.Spec(w, 1032, 3f), now, tz)
                    Previews.save(Bitmap.createBitmap(b, 0, b.height * 70 / 100, minOf(b.width, 480), b.height * 30 / 100),
                        "shift_${tag}_modern_${th}_w${w}_${hi}_${cur}_$lo")
                }
                for (rows in listOf(160, 320, 450)) {
                    val c = WidgetRenderer.render(p, WidgetSettings(lightTheme = light), WidgetRenderer.Spec(widthRows = rows), now, tz)
                    Previews.save(Bitmap.createBitmap(c, 0, c.height * 70 / 100, minOf(c.width, 480), c.height * 30 / 100),
                        "shift_${tag}_classic_${th}_r${rows}_${hi}_${cur}_$lo")
                }
            }
        }
    }
}
