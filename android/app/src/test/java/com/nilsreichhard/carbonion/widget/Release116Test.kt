package com.nilsreichhard.carbonion.widget

import android.Manifest
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.TextClock
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/** 1.1.6 (PebbleFaceChecker review): stale / expired data like the watch, window high/low, location resolve, alarm date, hourly redraw. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Release116Test {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val tz = TimeZone.getTimeZone("Europe/Zurich")
    private val H = 3_600_000L
    private fun payload() = WeatherPayload.fromJson(Previews.payloadJson())

    @Before fun clear() { ctx.getSharedPreferences(WidgetStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit() }

    // ------------------------------------------------------------------ stale / expired (main.c 2.8.7)

    @Test
    fun staleAfterSixHoursExpiredAfterTwentyFour() {
        val p = payload()
        val f = p.fetchTime * 1000
        assertFalse(p.isStale(f + 5 * H)); assertTrue(p.isStale(f + 6 * H + 60_000))
        assertFalse(p.isExpired(f + 23 * H, tz)); assertTrue(p.isExpired(f + 24 * H + 60_000, tz))
        assertTrue(p.isExpired(f + 70 * H, tz))
        // Watch: expired -> "--" and no condition; the app and the widget use the same calls.
        assertNotNull(p.displayTemp(f + 23 * H, tz)); assertNull(p.displayTemp(f + 25 * H, tz))
        assertEquals(-1, p.displayCode(f + 25 * H, tz)); assertTrue(p.displayCode(f + H, tz) >= 0)
        val s = WidgetSettings()
        val w23 = WidgetRenderer.Window.of(p, s, f + 23 * H, tz)
        assertTrue(w23.stale); assertFalse(w23.expired)
        assertTrue(WidgetRenderer.Window.of(p, s, f + 70 * H, tz).expired)
        assertNull(WidgetRenderer.windowHiLo(p, s, f + 30 * H, tz))
    }

    @Test
    fun dataRunningOutExpiresEvenWhenRecent() {
        // A provider with only 18 valid hours: 12 past + 6 ahead -> expired 6 h after the fetch hour (12 + offset >= valid_hours).
        val o = JSONObject(Previews.payloadJson()).put("valid_hours", 18)
        val p = WeatherPayload.fromJson(o)
        val f = p.fetchTime * 1000
        assertFalse(p.isExpired(f + H, tz))
        assertTrue(p.isExpired(f + 7 * H, tz))
    }

    @Test
    fun conditionIconDroppedWhenStale() {
        val p = payload(); val f = p.fetchTime * 1000
        WidgetUpdater.ensureIconFont(ctx)
        assertNotNull(WidgetRenderer.conditionIcon(p, f + H, tz, Color.WHITE, 48))
        assertNull(WidgetRenderer.conditionIcon(p, f + 7 * H, tz, Color.WHITE, 48))
    }

    private fun opaque(b: Bitmap): Int { var n = 0; for (y in 0 until b.height step 2) for (x in 0 until b.width step 2) if (b.getPixel(x, y) ushr 24 > 40) n++; return n }

    @Test
    fun expiredDrawsNoForecastInEitherStyle() {
        val p = payload(); val f = p.fetchTime * 1000
        for (light in listOf(true, false)) {
            val s = WidgetSettings(lightTheme = light)
            val mNow = ModernRenderer.render(p, s, ModernRenderer.Spec(1080, 1032, 3f), f + H, tz)
            val mOld = ModernRenderer.render(p, s, ModernRenderer.Spec(1080, 1032, 3f), f + 30 * H, tz)
            assertTrue("modern light=$light ${opaque(mOld)} vs ${opaque(mNow)}", opaque(mOld) * 3 < opaque(mNow))
            val cNow = WidgetRenderer.render(p, s, WidgetRenderer.Spec(), f + H, tz)
            val cOld = WidgetRenderer.render(p, s, WidgetRenderer.Spec(), f + 30 * H, tz)
            assertTrue("classic light=$light", opaque(cOld) * 3 < opaque(cNow))
        }
    }

    @Test
    fun talkBackAndAppUseTheVisibleWindow() {
        val p = payload(); val f = p.fetchTime * 1000
        val s = WidgetSettings()
        val (hi, lo) = WidgetRenderer.windowHiLo(p, s, f + H, tz)!!
        val win = WidgetRenderer.Window.of(p, s, f + H, tz)
        val vals = (0..s.totalHours).map { p.tempHourly[win.startSlot + it].roundToInt() }
        assertEquals(vals.max(), hi); assertEquals(vals.min(), lo)
        val d = WidgetUpdater.chartDescription(ctx, p, s, f + H, tz)
        assertTrue(d, d.contains("$hi°") && d.contains("$lo°"))
        assertEquals(ctx.getString(R.string.chart_description_expired), WidgetUpdater.chartDescription(ctx, p, s, f + 30 * H, tz))
    }

    /** Full widgets for the screenshots: fresh, stale (7 h, no condition icon, "· 7 h ago") and expired (30 h, "--"). */
    @Test
    fun staleAndExpiredWidgets() {
        android.provider.Settings.System.putString(ctx.contentResolver, android.provider.Settings.System.TIME_12_24, "24")
        for (light in listOf(false, true)) for ((name, ageH) in listOf("fresh" to 0L, "stale7h" to 7L, "expired30h" to 30L)) {
            WidgetStore.saveWeather(ctx, Previews.payloadJson(), "Villars-sur-Ollon", JSONObject().put("SETTING_LIGHT_THEME", if (light) 1 else 0).toString())
            val now = WidgetStore.payload(ctx, true)!!.fetchTime * 1000 + 8 * 60_000L + ageH * H
            val size = WidgetUpdater.Size(360, 300)
            val view = WidgetUpdater.build(ctx, size, 1, null, useAlarmOverride = true, nowMillis = now).apply(ctx, FrameLayout(ctx))
            view.findViewById<TextClock>(R.id.clock).apply { format24Hour = "'10:08'"; format12Hour = format24Hour }
            val dd = ctx.resources.displayMetrics.density
            val w = (size.wDp * dd).roundToInt(); val h = (size.hDp * dd).roundToInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            val out = Previews.wallpaper(w, h, !light)
            view.draw(Canvas(out))
            Previews.save(out, "v116_${name}_${if (light) "light" else "dark"}")
        }
    }

    // ------------------------------------------------------------------ location resolve

    private val lm get() = ctx.getSystemService(LocationManager::class.java)!!

    private fun fixAgo(ageMs: Long, lat: Double = 46.306, lon: Double = 7.064) = Location(LocationManager.NETWORK_PROVIDER).apply {
        latitude = lat; longitude = lon; accuracy = 900f; time = System.currentTimeMillis() - ageMs
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - ageMs * 1_000_000L
    }

    private fun grant(fine: Boolean) {
        val app = shadowOf(ctx as Application)
        app.grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine) app.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
    }

    @Test
    fun recentFixIsCurrentWithItsRealFixTime() {
        ShadowSystemClock.advanceBy(Duration.ofHours(3))
        grant(fine = false)
        shadowOf(lm).simulateLocation(fixAgo(10 * 60_000L))
        val pl = LocationHelper.resolve(ctx, null, fresh = false, timeoutMs = 50)
        assertEquals(LocationHelper.SOURCE_PHONE, pl.source)
        assertTrue("fix time is when it was taken", abs(System.currentTimeMillis() - 10 * 60_000L - pl.fixTime!!) < 5_000)
        assertEquals(pl.fixTime, WidgetStore.phoneFix(ctx)!!.fixTime)
    }

    @Test
    fun fixOlderThanThirtyMinutesIsSavedNotCurrent() {
        ShadowSystemClock.advanceBy(Duration.ofHours(6))
        grant(fine = true)
        shadowOf(lm).simulateLocation(fixAgo(31 * 60_000L))
        val pl = LocationHelper.resolve(ctx, null, fresh = false, timeoutMs = 50)
        assertEquals(LocationHelper.SOURCE_SAVED, pl.source)
        assertTrue(abs(System.currentTimeMillis() - 31 * 60_000L - pl.fixTime!!) < 5_000)
        // Not stored as a fresh phone fix.
        assertNull(WidgetStore.phoneFix(ctx))
    }

    @Test
    fun newerSavedFixBeatsAnOlderLastKnown() {
        ShadowSystemClock.advanceBy(Duration.ofHours(6))
        grant(fine = true)
        WidgetStore.savePhoneLocation(ctx, 46.30, 7.05, 20f, true, System.currentTimeMillis() - H)
        shadowOf(lm).simulateLocation(fixAgo(3 * H, 46.0, 6.0))
        val pl = LocationHelper.resolve(ctx, null, fresh = false, timeoutMs = 50)
        assertEquals(LocationHelper.SOURCE_SAVED, pl.source)
        assertEquals(46.30, pl.lat, 1e-9)
    }

    @Test
    fun deniedUsesSavedFixElseDefaultAndFixedAlwaysWins() {
        val app = shadowOf(ctx as Application)
        app.denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        assertEquals(LocationHelper.VILLARS.source, LocationHelper.resolve(ctx, null, fresh = false, timeoutMs = 50).source)
        WidgetStore.savePhoneLocation(ctx, 46.30, 7.05, 20f, true, System.currentTimeMillis() - 5 * H)
        val pl = LocationHelper.resolve(ctx, null, fresh = false, timeoutMs = 50)
        assertEquals(LocationHelper.SOURCE_SAVED, pl.source); assertEquals(46.30, pl.lat, 1e-9)
        val fixed = LocationHelper.resolve(ctx, """{"lat":46.1,"lon":7.2}""", fresh = false, timeoutMs = 50)
        assertEquals(LocationHelper.SOURCE_FIXED, fixed.source)
    }

    // ------------------------------------------------------------------ alarm date, hourly redraw

    @Test
    fun alarmMoreThanSixDaysAwayShowsTheDate() {
        val now = Calendar.getInstance(tz).apply { set(2026, Calendar.NOVEMBER, 21, 13, 0, 0) }.timeInMillis
        TimeZone.setDefault(tz)
        assertTrue(Regex("""^\d{1,2}:\d{2}$""").matches(NextAlarm.format(now + 2 * H, now, true, 0, Locale.UK)))
        assertTrue(Regex("""^\p{L}{3} \d{1,2}:\d{2}$""").matches(NextAlarm.format(now + 3 * 24 * H, now, true, 0, Locale.UK)))
        assertEquals("Mon 30 07:00".length, NextAlarm.format(now + 8 * 24 * H + (-6 * H), now, true, 0, Locale.UK).length)
        assertTrue(NextAlarm.format(now + 9 * 24 * H, now, true, 0, Locale.UK).startsWith("Mon 30"))
    }

    @Test
    fun redrawIsQueuedJustAfterTheNextHour() {
        val c = Calendar.getInstance(tz).apply { set(2026, Calendar.NOVEMBER, 21, 10, 59, 30); set(Calendar.MILLISECOND, 0) }
        assertEquals(30_000L + RedrawWorker.AFTER_HOUR_MS, RedrawWorker.delayToNextHour(c.timeInMillis, tz))
        c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0)
        assertEquals(H + RedrawWorker.AFTER_HOUR_MS, RedrawWorker.delayToNextHour(c.timeInMillis, tz))
    }
}
