package com.nilsreichhard.carbonion.widget

import android.Manifest
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.location.LocationManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone
import kotlin.math.abs

/** 1.1.4: precise location + where the data is for, rain under the clouds, cloud weight in both themes, widget mirror. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Release114Test {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun prefs() = ctx.getSharedPreferences(WidgetStore.PREFS, Context.MODE_PRIVATE)
    private fun res(n: String) = javaClass.classLoader!!.getResource(n)!!.readText()

    /**
     * Nils's widget (21 Nov 10:43): "Ollon", 2°, chart 4° / 1°, gust icons at the far left and the last
     * three hours (threshold 40 km/h). The same live forecast (best_match) recorded through the widget
     * bundle for Villars centre (46.2985, 7.0544, 1255 m) and one approximate-grid cell up the slope
     * (46.3060, 7.0640, 1383 m): only the higher point gives his widget, both are named "Ollon".
     */
    @Test
    fun nilsWidgetMatchesThePointUpTheSlopeNotTheVillage() {
        fun look(name: String): Triple<List<Int>, Long, Long> {
            val p = WeatherPayload.fromJson(res(name))
            val w = (6 until 36)
            val t = w.map { p.tempHourly[it] }
            return Triple(w.filter { p.wind[it] }, Math.round(t.max()), Math.round(t.min()))
        }
        assertEquals(Triple(listOf(6, 33, 34, 35), 4L, 1L), look("loc_aiglon_payload.json"))
        assertEquals(Triple(emptyList<Int>(), 6L, 1L), look("loc_villars_payload.json"))
    }

    @Test
    fun preciseFixUsesFusedOrGpsApproximateUsesNetwork() {
        val all = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        assertEquals(LocationManager.FUSED_PROVIDER, LocationHelper.freshProvider(true, 35, all))
        assertEquals(LocationManager.GPS_PROVIDER, LocationHelper.freshProvider(true, 35, all - LocationManager.FUSED_PROVIDER))
        assertEquals(LocationManager.GPS_PROVIDER, LocationHelper.freshProvider(true, 30, all))
        assertEquals(LocationManager.NETWORK_PROVIDER, LocationHelper.freshProvider(false, 35, all))
        assertEquals("10 m", LocationHelper.describeAccuracy(12f))
        assertEquals("850 m", LocationHelper.describeAccuracy(851f))
        assertEquals("2.0 km", LocationHelper.describeAccuracy(2000f))
    }

    @Test
    fun placeUsedIsStoredAndApproximateIsFlagged() {
        prefs().edit().clear().commit()
        val app = shadowOf(ctx as Application)
        app.grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        WidgetStore.savePlaceUsed(ctx, LocationHelper.Place(46.306, 7.064, false, LocationHelper.SOURCE_PHONE, 2000f, false))
        val pl = WidgetStore.placeUsed(ctx)!!
        assertEquals(46.306, pl.lat, 1e-9); assertEquals(2000f, pl.accuracyM); assertEquals(false, pl.precise)
        assertEquals(LocationPermission.State.APPROXIMATE, LocationPermission.state(ctx))
        app.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertEquals(LocationPermission.State.OK, LocationPermission.state(ctx))
        WidgetStore.savePlaceUsed(ctx, LocationHelper.Place(46.3, 7.05, true, LocationHelper.SOURCE_FIXED, precise = true))
        assertEquals(LocationPermission.State.OK, LocationPermission.state(ctx))
    }

    @Test
    fun rainHangsOneRowUnderTheLowestCloud() {
        for (split in listOf(false, true)) {
            val s = WidgetSettings(cloudSplit = split)
            val h = WidgetRenderer.stripHeights(s).cloud
            val gap = h - ModernRenderer.rainTuck(s, h) - ModernRenderer.cloudBottom(s, h)
            assertEquals("split=$split", ModernRenderer.RAIN_GAP, gap, 1e-4f)
            assertTrue("moved up (split=$split)", ModernRenderer.rainTuck(s, h) > 2f)
        }
        assertEquals(0f, ModernRenderer.rainTuck(WidgetSettings(cloudSensitivity = 4), 14))
    }

    private fun lum(c: Int) = 0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)

    @Test
    fun heavierCloudsStandOutMoreInBothThemes() {
        for (light in listOf(true, false)) {
            val p = ModernRenderer.Palette(light, null)
            val bg = if (light) 255.0 else 0.0
            val contrast = listOf(p.cloudHigh, p.cloudMid, p.cloudLow, p.cloudHeavy).map { abs(lum(it) - bg) }
            assertTrue("light=$light high < mid < low < heavy: $contrast", contrast.zipWithNext().all { (a, b) -> a < b })
            assertTrue("light=$light total below heavy", abs(lum(p.cloudTotal) - bg) < abs(lum(p.cloudHeavy) - bg))
        }
    }

    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    @Test
    fun appOpensWithTheWidgetOnTopAndTheLocationUsed() {
        prefs().edit().clear().putString("payload", Previews.payloadJson()).putString("city", "Villars-sur-Ollon").commit()
        WidgetStore.savePlaceUsed(ctx, LocationHelper.Place(46.306, 7.064, false, LocationHelper.SOURCE_PHONE, 2000f, false))
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        DetailActivity.mirrorExecutor = java.util.concurrent.Executor { it.run() }
        val ctl = Robolectric.buildActivity(DetailActivity::class.java).setup()
        val act = ctl.get()
        val mirror = act.findViewById<ViewGroup>(R.id.widget_mirror)
        assertNotNull("widget mirror", mirror)
        val inMirror = all(mirror)
        assertNotNull("widget clock in the mirror", inMirror.firstOrNull { it.id == R.id.clock })
        // Modern draws city / date / temperatures into the chart bitmap, as on the home screen.
        // 1.1.6: inflated like a launcher, not by AppCompat (AppCompatImageView.setImageBitmap isn't remotable).
        assertTrue("no AppCompat views in the mirror", inMirror.none { it.javaClass.name.startsWith("androidx.appcompat") })
        assertTrue("chart bitmap in the mirror", inMirror.filterIsInstance<android.widget.ImageView>().any { it.drawable != null && it.drawable.intrinsicWidth > 300 })
        val content = act.findViewById<ViewGroup>(android.R.id.content)
        val views = all(content)
        assertTrue("mirror first in the scroll content", (mirror.parent as ViewGroup).indexOfChild(mirror) == 0)
        val texts = views.filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.toString(), texts.any { it == "Location 46.3060, 7.0640 · phone, approximate (±2.0 km)" })
        assertTrue("precise button", views.filterIsInstance<Button>().any { it.text.toString().equals("Use precise location", true) })
        ctl.pause().stop().destroy()
    }

    /** PebbleFaceChecker #3: provider.js hands over W/m²; only the watch's AppMessage packs /4 and unpacks ×4. */
    @Test
    fun shortwaveIsReadAsWattsPerSquareMetre() {
        val o = org.json.JSONObject(res("loc_villars_payload.json"))
        val raw = o.getJSONArray("shortwave_radiation")
        val p = WeatherPayload.fromJson(o)
        for (i in 0 until WeatherPayload.HOURS) assertEquals("slot $i", Math.round(raw.optDouble(i, 0.0)).toInt(), p.shortwaveWm2[i])
        assertTrue("a November day stays under the 800 W/m² full-sun mark", p.shortwaveWm2.max() < 800)
    }

    /** PebbleFaceChecker #5: main.c shows current_temp / weather_code only in the fetch hour, then the hourly slot. */
    @Test
    fun currentTemperatureAndConditionFollowTheWatch() {
        val tz = TimeZone.getTimeZone("Europe/Zurich")
        val o = org.json.JSONObject(res("loc_villars_payload.json"))
        val p0 = WeatherPayload.fromJson(o)
        // Give "current" values that differ from every hourly slot so the source is unambiguous.
        val p = WeatherPayload.fromJson(o.put("current_temp", 42.0).put("weather_code", 95))
        val cal = java.util.Calendar.getInstance(tz).apply { timeInMillis = p.fetchTime * 1000 }
        val hourTop = p.fetchTime * 1000 - (cal.get(java.util.Calendar.MINUTE) * 60 + cal.get(java.util.Calendar.SECOND)) * 1000L
        val inFetchHour = hourTop + 3_599_000L
        assertEquals(42.0, p.displayTemp(inFetchHour, tz)!!, 1e-9)
        assertEquals(95, p.displayCode(inFetchHour, tz))
        val nextHour = hourTop + 3_600_000L + 60_000L
        assertEquals(p0.tempHourly[13], p.displayTemp(nextHour, tz)!!, 1e-9)
        assertEquals(p0.weatherCode[13], p.displayCode(nextHour, tz))
        assertEquals(p0.tempHourly[15], p.displayTemp(hourTop + 3 * 3_600_000L + 5, tz)!!, 1e-9)
        assertEquals(1, WidgetRenderer.Window.of(p, WidgetSettings(), nextHour, tz).offset)
    }

    /** PebbleFaceChecker #1: an old phone fix is never passed off as the current place. */
    @Test
    fun oldPhoneFixIsMarkedSavedWithItsAge() {
        assertTrue(LocationHelper.isRecent(0)); assertTrue(LocationHelper.isRecent(29 * 60_000L))
        assertTrue(!LocationHelper.isRecent(31 * 60_000L)); assertTrue(!LocationHelper.isRecent(-1))
        assertEquals("5 min", LocationHelper.describeAge(5 * 60_000L))
        assertEquals("3 h", LocationHelper.describeAge(3 * 3_600_000L + 5))
        assertEquals("2 d", LocationHelper.describeAge(50 * 3_600_000L))
        prefs().edit().clear().commit()
        val now = System.currentTimeMillis()
        WidgetStore.savePhoneLocation(ctx, 46.2985, 7.0544, 15f, true, now - 3 * 3_600_000L)
        assertEquals(now - 3 * 3_600_000L, WidgetStore.phoneFix(ctx)!!.fixTime)
        // No permission → no fresh fix: the saved one is used, marked as such.
        val pl = LocationHelper.resolve(ctx, null, false)
        assertEquals(LocationHelper.SOURCE_SAVED, pl.source)
        WidgetStore.savePlaceUsed(ctx, pl)
        assertEquals(now - 3 * 3_600_000L, WidgetStore.placeUsed(ctx)!!.fixTime)
        assertEquals("Ollon · 3 h", WidgetUpdater.staleCity(ctx, "Ollon", null, now))
        WidgetStore.savePlaceUsed(ctx, LocationHelper.Place(46.3, 7.06, false, LocationHelper.SOURCE_PHONE, 10f, true, now))
        assertEquals("Ollon", WidgetUpdater.staleCity(ctx, "Ollon", null, now))
    }

    /** PebbleFaceChecker #4: the source line names the model the data came from, and says when a provider was skipped. */
    @Test
    fun sourceLineShowsTheModelActuallyUsed() {
        val dict = org.json.JSONObject().put("SETTING_OM_MODEL", "icon_d2").put("SETTING_WEATHER_PROVIDER", "meteoblue")
        prefs().edit().clear().putString("payload", Previews.payloadJson()).putString("city", "Ollon").putString("dict", dict.toString()).commit()
        WidgetStore.saveUsed(ctx, org.json.JSONObject().put("provider", "openmeteo").put("requested", "meteoblue").put("model", "best_match")
            .put("fallback", "meteoblue key/quota rejected (HTTP 401) → open-meteo").toString())
        val ctl = Robolectric.buildActivity(DetailActivity::class.java).setup()
        val texts = all(ctl.get().findViewById(android.R.id.content)).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.toString(), texts.any { "model best_match" in it })
        assertTrue(texts.toString(), texts.none { "model icon_d2" in it })
        assertTrue(texts.toString(), texts.any { it.startsWith("Meteoblue was not used") })
        ctl.pause().stop().destroy()
    }

    /** 1.1.6 review: permission revoked while a saved fix is used reads as "not allowed", not "approximate". */
    @Test
    fun revokedWithSavedFixIsDeniedNotApproximate() {
        prefs().edit().clear().commit()
        val app = shadowOf(ctx as Application)
        app.denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        WidgetStore.savePlaceUsed(ctx, LocationHelper.Place(46.306, 7.064, false, LocationHelper.SOURCE_SAVED, 15f, true, System.currentTimeMillis()))
        assertEquals(LocationPermission.State.DENIED, LocationPermission.state(ctx))
        WidgetStore.savePlaceUsed(ctx, LocationHelper.VILLARS)
        assertEquals(LocationPermission.State.DENIED, LocationPermission.state(ctx))
        WidgetStore.savePlaceUsed(ctx, LocationHelper.Place(46.3, 7.05, true, LocationHelper.SOURCE_FIXED, precise = true))
        assertEquals(LocationPermission.State.OK, LocationPermission.state(ctx))
        app.grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        WidgetStore.savePlaceUsed(ctx, LocationHelper.Place(46.306, 7.064, false, LocationHelper.SOURCE_SAVED, 2000f, false, 1L))
        assertEquals(LocationPermission.State.APPROXIMATE, LocationPermission.state(ctx))
        WidgetStore.savePlaceUsed(ctx, LocationHelper.VILLARS)
        assertEquals(LocationPermission.State.NO_FIX, LocationPermission.state(ctx))
        // The app says which place it shows.
        app.denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        prefs().edit().putString("payload", Previews.payloadJson()).commit()
        WidgetStore.savePlaceUsed(ctx, LocationHelper.Place(46.306, 7.064, false, LocationHelper.SOURCE_SAVED, 15f, true, System.currentTimeMillis() - 120_000))
        val ctl = Robolectric.buildActivity(DetailActivity::class.java).setup()
        val views = all(ctl.get().findViewById(android.R.id.content))
        val texts = views.filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.toString(), texts.any { it.startsWith("Location not allowed: showing the last saved phone place") })
        assertTrue(texts.toString(), texts.any { it.endsWith("saved phone fix from 2 min ago (no current fix)") })
        assertTrue("allow button", views.filterIsInstance<Button>().any { it.text.toString().equals("Allow location", true) })
        assertTrue("no precise button", views.filterIsInstance<Button>().none { it.text.toString().equals("Use precise location", true) })
        ctl.pause().stop().destroy()
    }
}
