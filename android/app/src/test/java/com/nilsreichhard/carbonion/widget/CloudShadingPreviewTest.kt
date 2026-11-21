package com.nilsreichhard.carbonion.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * 1.1.4: Nils "in dark mode clearer clouds show darker, heavy clouds lighter". Synthetic 24 h strip:
 * thin (20 %), partly (50 %), overcast (90 %), heavy rain clouds (95 %, rain), then clear, in both
 * themes, Total and Split. Writes build/outputs/preview/v13_cloud_shading.png.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CloudShadingPreviewTest {
    private val tz = TimeZone.getTimeZone("Europe/Zurich")

    private fun synthetic(): WeatherPayload {
        val o = JSONObject(Previews.payloadJson())
        fun arr(f: (Int) -> Number) = JSONArray().apply { for (i in 0 until WeatherPayload.HOURS) put(f(i)) }
        fun block(i: Int) = when (i) { in 6..11 -> 0; in 12..17 -> 1; in 18..23 -> 2; in 24..29 -> 3; else -> 4 }
        val cover = intArrayOf(20, 50, 90, 95, 0)
        val code = intArrayOf(1, 2, 3, 63, 0)
        o.put("cloud_cover", arr { cover[block(it)] })
        o.put("cloud_cover_low", arr { if (block(it) >= 2 && block(it) < 4) cover[block(it)] else 0 })
        o.put("cloud_cover_mid", arr { if (block(it) == 1 || block(it) == 3) cover[block(it)] else 0 })
        o.put("cloud_cover_high", arr { if (block(it) <= 1) cover[block(it)] else 0 })
        o.put("hourly_weather_code", arr { code[block(it)] })
        o.put("precip_prob", arr { if (block(it) == 3) 90 else 0 })
        o.put("precip_mm", arr { if (block(it) == 3) 30 else 0 })
        o.put("shortwave_radiation", arr { 0 })
        o.put("wind_bits", arr { 0 }); o.put("storm_bits", arr { 0 }); o.put("fog_bits", arr { 0 })
        return WeatherPayload.fromJson(o)
    }

    @Test
    fun cloudShadingLightVsHeavyInBothThemes() {
        val p = synthetic()
        val now = p.fetchTime * 1000 + 5 * 60_000
        val W = 1080; val H = 600
        val spec = ModernRenderer.Spec(W, H, 3f)
        val cells = ArrayList<Pair<String, Bitmap>>()
        for (light in listOf(true, false)) for (split in listOf(false, true)) {
            val s = WidgetSettings(lightTheme = light, cloudSplit = split, cloudThick = false)
            val b = ModernRenderer.render(p, s, spec, now, tz)
            cells.add("${if (light) "Light" else "Dark"} · ${if (split) "Split" else "Total"}   thin | partly | overcast | heavy rain | clear" to b)
        }
        val pad = 60
        val out = Bitmap.createBitmap(W * 2 + 30, (H + pad) * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(128, 128, 128))
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; color = Color.WHITE }
        cells.forEachIndexed { i, (label, b) ->
            val x = (i % 2) * (W + 30); val y = (i / 2) * (H + pad)
            val bg = Paint().apply { color = if (i < 2) Color.WHITE else Color.BLACK }
            c.drawRect(x.toFloat(), (y + pad).toFloat(), (x + W).toFloat(), (y + pad + H).toFloat(), bg)
            c.drawText(label, x + 10f, y + 42f, tp)
            c.drawBitmap(b, x.toFloat(), (y + pad).toFloat(), null)
        }
        Previews.save(out, "v13_cloud_shading")
        assertTrue(out.width > 0)
    }
}
