package com.nilsreichhard.carbonion.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.SizeF
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.TimeZone

/** Findings of the Nov 2026 review: colours on solid backgrounds, security guards, stale data, API gating. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReviewFixesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun bg(c: Int, op: Int = 100) = WidgetStore.Background(WidgetStore.BG_COLOR, c, op)

    // ------------------------------------------------------------ colours
    @Test
    fun luminanceAndContrastFollowWcag() {
        assertEquals(1.0, WidgetColors.luminance(0xFFFFFF), 1e-6)
        assertEquals(0.0, WidgetColors.luminance(0x000000), 1e-6)
        assertEquals(21.0, WidgetColors.contrast(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 1e-6)
        assertEquals(1.0, WidgetColors.contrast(0xFF777777.toInt(), 0xFF777777.toInt()), 1e-6)
    }

    @Test
    fun transparentBackgroundKeepsTheTheme() {
        val t = WidgetStore.Background()
        assertTrue(WidgetColors.effectiveLight(t, true)); assertFalse(WidgetColors.effectiveLight(t, false))
    }

    @Test
    fun solidBackgroundPicksInkByLuminanceInBothThemes() {
        for (theme in listOf(true, false)) {
            assertTrue("cream → dark ink", WidgetColors.effectiveLight(bg(0xF3E5C8), theme))
            assertTrue("white → dark ink", WidgetColors.effectiveLight(bg(0xFFFFFF), theme))
            assertFalse("black → light ink", WidgetColors.effectiveLight(bg(0x000000), theme))
            assertFalse("navy → light ink", WidgetColors.effectiveLight(bg(0x1A237E), theme))
            assertFalse("dark grey 70 % → light ink", WidgetColors.effectiveLight(bg(0x202124, 70), theme))
            assertFalse("Classic on navy", WidgetColors.effectiveLight(bg(0x1A237E), theme, modern = false))
        }
    }

    @Test
    fun chosenInkMeetsWcagOnEveryPreset() {
        for (c in SettingsActivity.PRESETS) for (op in listOf(100, 85, 70)) for (theme in listOf(true, false)) for (modern in listOf(true, false)) {
            val b = bg(c and 0xFFFFFF, op)
            val light = WidgetColors.effectiveLight(b, theme, modern)
            val behind = WidgetColors.blended(b, theme)
            val ink = if (modern) ModernRenderer.Palette(light, null).ink else CarbonStyle.Palette(light).ink
            val r = WidgetColors.contrast(ink, behind)
            // Opaque: WCAG AA 4.5:1. See-through mid-tones (teal / slate at 70–85 % over the theme's
            // wallpaper) blend to a mid-grey where neither palette reaches 4.5; the better one is still ≥ 3.8.
            val min = if (op == 100) 4.5 else 3.8
            assertTrue("text %06X %d%% theme=%s modern=%s: %.2f".format(c and 0xFFFFFF, op, theme, modern, r), r >= min)
            // Secondary labels: 3:1 (large text / graphics) while the colour dominates; at 70 % over the
            // other-theme wallpaper a mid-grey results where only the primary text can reach 4.5:1.
            if (modern && op == 100) {
                val r2 = WidgetColors.contrast(ModernRenderer.Palette(light, null).ink2, behind)
                assertTrue("secondary %06X %d%%: %.2f".format(c and 0xFFFFFF, op, r2), r2 >= 3.0)
            }
        }
    }

    // ------------------------------------------------------------ security
    @Test
    fun onlyHttpsUrlsAreFetched() {
        assertTrue(JsRuntime.allowedUrl("https://api.open-meteo.com/v1/forecast?latitude=1"))
        assertFalse(JsRuntime.allowedUrl("http://api.open-meteo.com/v1/forecast"))
        assertFalse(JsRuntime.allowedUrl("file:///data/data/x/shared_prefs/carbon_js_store.xml"))
        assertFalse(JsRuntime.allowedUrl("content://settings/secure"))
        assertFalse(JsRuntime.allowedUrl("not a url"))
        val r = JsRuntime.urlConnectionGet("http://example.com/", emptyMap())
        assertEquals(0, r.status); assertTrue(r.error!!.contains("https"))
    }

    @Test
    fun responseBodiesAreCapped() {
        assertEquals("ok", JsRuntime.readCapped(ByteArrayInputStream("ok".toByteArray())))
        val big = ByteArray(JsRuntime.MAX_BODY_BYTES + 10) { 'a'.code.toByte() }
        assertTrue(runCatching { JsRuntime.readCapped(ByteArrayInputStream(big)) }.exceptionOrNull() is java.io.IOException)
    }

    @Test
    fun debugReceiverRejectsPathsInNames() {
        assertEquals("villars", DebugSamplesReceiver.safeName("villars"))
        assertEquals("wall_dark.png", DebugSamplesReceiver.safeName("wall_dark.png"))
        for (bad in listOf("../shared_prefs/carbon_widget", "a/b", "..", "", " x", "a\\b")) assertNull(bad, DebugSamplesReceiver.safeName(bad))
        assertNull(DebugSamplesReceiver.safeName(null))
    }

    @Test
    fun manifestComponentsAndAppFlags() {
        val pm = ctx.packageManager
        val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_RECEIVERS or PackageManager.GET_ACTIVITIES)
        val provider = info.receivers!!.first { it.name.endsWith(".CarbonWidgetProvider") }
        assertFalse("widget provider not exported", provider.exported)
        val debug = info.receivers!!.firstOrNull { it.name.endsWith(".DebugSamplesReceiver") }
        if (debug != null) assertEquals("debug receiver needs DUMP", "android.permission.DUMP", debug.permission)
        val app = info.applicationInfo!!
        assertEquals("no backup", 0, app.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertFalse("cleartext traffic off", (app.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0)
        // Exported activities are only the launcher entry and the widget configure screen.
        val exported = info.activities!!.filter { it.exported }.map { it.name.substringAfterLast('.') }.toSet()
        assertEquals(setOf("DetailActivity", "SettingsActivity"), exported)
    }

    // ------------------------------------------------------------ gaps
    @Test
    fun cachedForecastFollowsAUnitChangeWhileOffline() {
        val json = Previews.payloadJson()
        val celsiusDict = """{"SETTING_TEMP_UNIT":0}"""
        WidgetStore.saveWeather(ctx, json, "Villars", celsiusDict)
        val c = WidgetStore.payload(ctx, celsius = true)!!
        val f = WidgetStore.payload(ctx, celsius = false)!!
        assertEquals(c.tempHourly[12] * 9 / 5 + 32, f.tempHourly[12], 1e-9)
        assertEquals(c.currentTemp!! * 9 / 5 + 32, f.currentTemp!!, 1e-9)
        assertEquals(c.tempHourly[3], f.inUnit(toFahrenheit = false).tempHourly[3], 1e-9)
    }

    @Test
    fun staleAndNoLocationMarkersOnTheCityRow() {
        val p = WeatherPayload.fromJson(Previews.payloadJson())
        val t0 = p.fetchTime * 1000
        assertEquals("Villars", WidgetUpdater.staleCity(ctx, "Villars", p, t0 + 30 * 60_000L, noLocation = false))
        assertEquals("Villars · 2 h ago", WidgetUpdater.staleCity(ctx, "Villars", p, t0 + 2 * 3_600_000L, noLocation = false))
        assertEquals("Villars · 3 d ago", WidgetUpdater.staleCity(ctx, "Villars", p, t0 + 73 * 3_600_000L, noLocation = false))
        assertEquals("Villars · no location", WidgetUpdater.staleCity(ctx, "Villars", p, t0, noLocation = true))
    }

    @Test
    fun slotsStartOnLocalHoursInHalfHourZones() {
        val p = WeatherPayload.fromJson(Previews.payloadJson())
        val tz = TimeZone.getTimeZone("Asia/Kolkata")
        val t = DetailData.slotMillis(p, CarbonStyle.PAYLOAD_NOW_SLOT, tz)
        val local = (t + tz.getOffset(t)) % 3_600_000L
        assertEquals("slot starts on a local hour", 0L, local)
        assertTrue(t <= p.fetchTime * 1000 && p.fetchTime * 1000 - t < 3_600_000L)
        assertEquals(3_600_000L, DetailData.slotMillis(p, 13, tz) - t)
    }

    @Test
    fun separatorKeepsQuarterEmGapsForNarrowSpaceFonts() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        for (font in FontOption.values().filter { it.bundled }) {
            val p = AlarmBadge.paint(font.typeface(ctx, clock = false), 40f)
            val dot = android.graphics.Rect().also { r -> p.getTextBounds("\u00B7", 0, 1, r) }
            assertTrue(font.name, AlarmBadge.sepSpace(p) + dot.left >= 0.3f * 40f)
            val w = AlarmBadge.measureSeparated("Villars \u00B7 5 h ago", p)
            assertEquals(font.name, p.measureText("Villars") + p.measureText("5 h ago") + AlarmBadge.separatorWidth(p), w, 0.01f)
            assertTrue(font.name, AlarmBadge.separatorWidth(p) >= p.measureText(AlarmBadge.SEPARATOR) - 0.01f)
        }
    }
}

/** Size options on Android 12 / 12L: the typed Bundle getter only exists from 13. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 32])
class SizesApiLevelTest {
    @Test
    fun widgetSizesParseOnAndroid12() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val o = Bundle().apply { putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(360f, 344f), SizeF(600f, 200f))) }
        assertEquals(listOf(SizeF(360f, 344f), SizeF(600f, 200f)), WidgetUpdater.sizesFrom(o))
        WidgetStore.saveWeather(ctx, Previews.payloadJson(), "Villars", "{}")
        val rv = WidgetUpdater.build(ctx, WidgetUpdater.Size(360, 344))
        assertTrue(rv.layoutId != 0)
        ComponentName(ctx, CarbonWidgetProvider::class.java) // provider class loads on 31/32
    }
}
