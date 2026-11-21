package com.nilsreichhard.carbonion.widget

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * 1.1.3 gust parity with the watch face. Fixtures are one live Open-Meteo forecast for 43.3, 5.4
 * (best_match, gusts ≥ 50 km/h) on 21 Nov 2026 ≈ 09:38 Europe/Zurich:
 *  - gust_marseille_watch_payload.json: the payload the watch's pkjs built in the emulator (pypkjs localStorage);
 *  - gust_marseille_android_payload.json: the same request through the widget bundle (tools/run-node.js).
 * Both flag slots 37–54 and 65–67 (Sun 10:00 onwards), all outside the watch's default 24 h window
 * (slots 6..35), so the watch draws no wind icon. The app's "Next days" list used to say "gusts" anyway.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GustParityTest {
    private val tz = TimeZone.getTimeZone("Europe/Zurich")
    private fun res(n: String) = javaClass.classLoader!!.getResource(n)!!.readText()
    private val watchJson = JSONObject(res("gust_marseille_watch_payload.json"))
    private val androidJson = JSONObject(res("gust_marseille_android_payload.json"))
    private val p = WeatherPayload.fromJson(watchJson)
    private val now = p.fetchTime * 1000 + 60_000

    private fun windSlots(x: WeatherPayload) = (0 until WeatherPayload.HOURS).filter { x.wind[it] }

    /** main.c prv_push_weather_to_layers at the fetch hour: GRAPH_HOURS slots from 12 − FORECAST/4. */
    private fun watchWindow(fh: Int) = (12 - fh / 4) until (12 - fh / 4 + fh + fh / 4)

    @Test
    fun bundleAndWatchFlagTheSameHours() {
        assertEquals(watchJson.getJSONArray("wind_bits").toString(), androidJson.getJSONArray("wind_bits").toString())
        val expected = (37..54).toList() + (65..67).toList()
        assertEquals(expected, windSlots(p))
        assertEquals(expected, windSlots(WeatherPayload.fromJson(androidJson)))
    }

    @Test
    fun widgetDrawsExactlyTheWatchWindsForEveryRange() {
        for (fh in intArrayOf(12, 18, 24, 36, 48)) {
            val s = WidgetSettings(forecastHours = fh)
            val w = WidgetRenderer.Window.of(p, s, now, tz)
            assertEquals("start slot $fh h", watchWindow(fh).first, w.startSlot)
            val drawn = WidgetRenderer.eventSpans(p, s, w.startSlot, w.hoursRemaining)
                .filter { it.kind == WidgetRenderer.EventKind.WIND }.flatMap { (it.start..it.end).map { i -> i + w.startSlot } }
            val watch = windSlots(p).filter { it in watchWindow(fh) }
            assertEquals("wind hours drawn at $fh h", watch, drawn)
            assertEquals(watchWindow(fh), DetailData.watchSlots(p, s, now, tz))
        }
    }

    /**
     * 1.1.4 (PebbleFaceChecker): main.c prv_weather_data_offset counts whole hours from the top of the
     * fetch hour, so the "now" column moves at 10:00 after a 09:38 fetch. Android used to wait a full
     * hour after the fetch (10:38) and drew one hour stale meanwhile.
     */
    @Test
    fun windowMovesAtTheTopOfTheHourLikeTheWatch() {
        val cal = java.util.Calendar.getInstance(tz).apply { timeInMillis = p.fetchTime * 1000 }
        val hourTop = p.fetchTime * 1000 - (cal.get(java.util.Calendar.MINUTE) * 60 + cal.get(java.util.Calendar.SECOND)) * 1000L
        fun watchOffset(nowMs: Long) = ((nowMs - hourTop) / 3_600_000L).toInt()
        for (mins in intArrayOf(1, 21, 23, 59, 61, 83, 150)) {
            val at = p.fetchTime * 1000 + mins * 60_000L
            val off = watchOffset(at)
            assertEquals("offset after $mins min", off, p.hoursSinceFetchHour(at, tz))
            for (fh in intArrayOf(24, 48)) {
                val s = WidgetSettings(forecastHours = fh)
                val w = WidgetRenderer.Window.of(p, s, at, tz)
                val ww = watchWindow(fh).let { (it.first + off) until (it.last + 1 + off) }
                assertEquals("start $fh h after $mins min", ww.first, w.startSlot)
                val drawn = WidgetRenderer.eventSpans(p, s, w.startSlot, w.hoursRemaining)
                    .filter { it.kind == WidgetRenderer.EventKind.WIND }.flatMap { (it.start..it.end).map { i -> i + w.startSlot } }
                assertEquals("winds $fh h after $mins min", windSlots(p).filter { it in ww }, drawn)
            }
        }
        // 09:38 fetch, 10:03: the watch is already on the 10:00 column; the 1.1.3 floor((now − fetch)/1 h) said 0.
        val at = hourTop + 3_600_000L + 3 * 60_000L
        assertEquals(1, p.hoursSinceFetchHour(at, tz))
        assertEquals(0L, (at / 1000 - p.fetchTime) / 3600)
    }

    @Test
    fun nextDaysOnlyReportGustsTheWatchShows() {
        val s24 = WidgetSettings(forecastHours = 24)
        val before = DetailData.days(p, now, tz)                                  // 1.1.2: whole 72 h payload
        assertEquals("1.1.2 said gusts on Sun and Mon", listOf(false, true, true), before.map { "gusts" in it.events })
        val after = DetailData.days(p, now, tz, eventSlots = DetailData.watchSlots(p, s24, now, tz))
        assertTrue("24 h: the watch shows no gust, nor does the app", after.none { "gusts" in it.events })
        assertEquals(before.map { it.high to it.low }, after.map { it.high to it.low })  // only the event words change
        // 48 h: the watch window (slots 0..59) reaches Sun 10:00–Mon 03:00 (slots 37..54) but not Mon 14:00 (65..67).
        val s48 = WidgetSettings(forecastHours = 48)
        val d48 = DetailData.days(p, now, tz, eventSlots = DetailData.watchSlots(p, s48, now, tz))
        assertEquals(listOf(false, true, true), d48.map { "gusts" in it.events })
    }
}
