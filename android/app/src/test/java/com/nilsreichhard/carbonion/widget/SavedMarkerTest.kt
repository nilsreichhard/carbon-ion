package com.nilsreichhard.carbonion.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt

/**
 * 1.1.9 (Nils 16:06): the widget's city row shows only the city while the saved phone fix is younger than
 * [WidgetUpdater.SAVED_MARKER_MS] (3 h); from then on a compact "Ollon · 3 h". Fresh vs saved is still 30 min.
 * Header renders at 1 h and 5 h (light / dark) go to build/outputs/preview/saved_marker_*.png.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SavedMarkerTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val now = 1_763_460_000_000L
    private val min = 60_000L
    private val hour = 3_600_000L

    private fun city(ageMs: Long) = WidgetUpdater.staleCity(ctx, "Ollon", null, now, noLocation = false, savedFixTime = now - ageMs)
    private fun spoken(ageMs: Long) = WidgetUpdater.staleCity(ctx, "Ollon", null, now, noLocation = false, savedFixTime = now - ageMs, spoken = true)

    @Test
    fun markerHiddenUnderThreeHours() {
        assertEquals(3 * hour, WidgetUpdater.SAVED_MARKER_MS)
        // 31 min: already "saved" (not fresh) for refetching and the app's detail line, but the widget shows only the city.
        assertTrue(!LocationHelper.isRecent(31 * min))
        for (age in longArrayOf(31 * min, 2 * hour + 59 * min)) {
            assertEquals("Ollon", city(age))
            assertEquals("Ollon", spoken(age))          // TalkBack doesn't mention the age either
            assertNull(WidgetUpdater.savedMarkerAge(now - age, now))
        }
        // Fresh fix, or a saved one of unknown age: just the city.
        assertEquals("Ollon", WidgetUpdater.staleCity(ctx, "Ollon", null, now, noLocation = false, savedFixTime = null))
        assertEquals("Ollon", WidgetUpdater.staleCity(ctx, "Ollon", null, now, noLocation = false, savedFixTime = 0L))
    }

    @Test
    fun markerShownFromThreeHours() {
        assertEquals("Ollon · 3 h", city(3 * hour))
        assertEquals("Ollon · 5 h", city(5 * hour))
        assertEquals("Ollon · 26 h", city(26 * hour))
        assertEquals("Ollon · location saved 5 h ago", spoken(5 * hour))
        // With stale data too, both marks stay distinguishable ("ago" only on the data age).
        WidgetStore.saveWeather(ctx, Previews.payloadJson(), "Ollon", "{}")
        val p = WidgetStore.payload(ctx, true)!!
        val t = p.fetchTime * 1000 + 4 * hour
        assertEquals("Ollon · 5 h · 4 h ago", WidgetUpdater.staleCity(ctx, "Ollon", p, t, noLocation = false, savedFixTime = t - 5 * hour))
    }

    private fun render(light: Boolean, ageMs: Long, city: String, size: WidgetUpdater.Size, textFont: FontOption = FontOption.AUTO): View {
        android.provider.Settings.System.putString(ctx.contentResolver, android.provider.Settings.System.TIME_12_24, "24")
        val dict = JSONObject().apply { put("SETTING_LIGHT_THEME", if (light) 1 else 0); put("SETTING_TEMP_UNIT", 0) }.toString()
        WidgetStore.saveWeather(ctx, Previews.payloadJson(), city, dict)
        WidgetStore.saveBackground(ctx, WidgetStore.Background())
        WidgetStore.saveUi(ctx, UiOptions(textFont = textFont))
        val p = WidgetStore.payload(ctx, true)!!
        val t = p.fetchTime * 1000 + 8 * min
        WidgetStore.savePlaceUsed(ctx, LocationHelper.Place(46.3, 7.06, false, LocationHelper.SOURCE_SAVED, 15f, true, t - ageMs))
        val view = WidgetUpdater.build(ctx, size, 1, "Thu 06:30", useAlarmOverride = true, nowMillis = t,
            batteryOverride = WidgetRenderer.Battery(64, false, t / 1000, 64, 900), useBatteryOverride = true).apply(ctx, FrameLayout(ctx))
        view.findViewById<TextClock>(R.id.clock).apply { format24Hour = "'10:08'"; format12Hour = format24Hour }
        view.findViewById<TextClock>(R.id.date).apply { format24Hour = "'Wednesday, Nov 18'"; format12Hour = format24Hour }
        val d = ctx.resources.displayMetrics.density
        val w = (size.wDp * d).roundToInt(); val h = (size.hDp * d).roundToInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        return view
    }

    private fun bitmap(v: View): Bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }

    private fun absLeft(v: View): Int { var x = 0; var c: View? = v; while (c != null) { x += c.left; c = c.parent as? View }; return x }

    @Test
    fun conditionIconStaysOnFourByFour() {
        // Long city + the longest marker on 4x4 (~360 dp wide) and the tighter 3x4: the condition icon stays inside.
        for (size in listOf(WidgetUpdater.Size(360, 460), WidgetUpdater.Size(270, 460))) {
            val v = render(light = false, ageMs = 26 * hour, city = "Villars-sur-Ollon", size = size)
            val cityTv = v.findViewById<TextView>(R.id.city)
            assertEquals("Villars-sur-Ollon · 26 h", cityTv.text.toString())
            assertEquals("Villars-sur-Ollon · location saved 26 h ago", cityTv.contentDescription.toString())
            val cond = v.findViewById<View>(R.id.cond)
            if (cond.visibility == View.VISIBLE) {
                assertTrue("cond width ${cond.width}", cond.width > 0)
                assertTrue("cond right ${absLeft(cond) + cond.width} > ${v.width}", absLeft(cond) + cond.width <= v.width)
            }
        }
    }

    @Test
    fun renderHeaders() {
        val out = File("build/outputs/preview").apply { mkdirs() }
        // Default text (TextView) and a bundled font (Inter: the row is drawn into the bitmap).
        for (font in listOf(FontOption.AUTO, FontOption.INTER))
        for (light in listOf(true, false)) for ((age, tag) in listOf(1 * hour to "1h", 5 * hour to "5h")) {
            val v = render(light, age, "Villars-sur-Ollon", WidgetUpdater.Size(360, 344), font)
            val b = bitmap(v)
            val header = Bitmap.createBitmap(b, 0, (b.height * 0.30f).roundToInt(), b.width, (b.height * 0.45f).roundToInt())
            File(out, "saved_marker_${font.key}_${if (light) "light" else "dark"}_$tag.png").outputStream().use { header.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val text = v.findViewById<TextView>(R.id.city).text
            assertEquals(if (age < WidgetUpdater.SAVED_MARKER_MS) "Villars-sur-Ollon" else "Villars-sur-Ollon · 5 h", text.toString())
            // The marker is drawn smaller than the city name.
            if (age >= WidgetUpdater.SAVED_MARKER_MS) {
                val spans = (text as android.text.Spanned).getSpans(0, text.length, android.text.style.RelativeSizeSpan::class.java)
                assertEquals(1, spans.size); assertEquals("Villars-sur-Ollon".length, text.getSpanStart(spans[0]))
            }
        }
    }
}
