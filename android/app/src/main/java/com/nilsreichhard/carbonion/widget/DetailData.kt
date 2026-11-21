package com.nilsreichhard.carbonion.widget

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pure helpers for the expanded screen: per-hour details, daily summaries, condition names. */
object DetailData {
    /** One payload hour, as shown by the scrub line. */
    class Hour(
        val slot: Int, val timeMillis: Long, val temp: Double, val feels: Double,
        val rainMm: Double?, val chance: Int, val cloudLow: Int, val cloudMid: Int, val cloudHigh: Int,
        val gusts: Boolean, val storm: Boolean, val fog: Boolean, val sunWm2: Int, val code: Int,
    )

    class Day(
        val startMillis: Long, val high: Double, val low: Double, val rainMm: Double?, val maxChance: Int,
        val code: Int, val events: List<String>, val hours: Int,
    )

    /** Start of payload slot [slot]: slot 12 is the fetch hour. */
    fun slotMillis(p: WeatherPayload, slot: Int, tz: TimeZone = TimeZone.getDefault()): Long {
        // Slots are local hours: start of the fetch's local hour (UTC+5:30, +5:45 … aren't whole UTC hours).
        val t = p.fetchTime * 1000
        val off = tz.getOffset(t)
        val hourStart = Math.floorDiv(t + off, 3_600_000L) * 3_600_000L - off
        return hourStart + (slot - CarbonStyle.PAYLOAD_NOW_SLOT) * 3_600_000L
    }

    fun hour(p: WeatherPayload, slot: Int): Hour {
        val j = slot.coerceIn(0, WeatherPayload.HOURS - 1)
        return Hour(j, slotMillis(p, j), p.tempHourly[j], p.apparentHourly[j],
            if (p.haveMm) p.precipMmTenths[j] / 10.0 else null, p.precipProb[j],
            p.cloudLow[j], p.cloudMid[j], p.cloudHigh[j], p.wind[j], p.storm[j], p.fog[j], p.shortwaveWm2[j], p.weatherCode[j])
    }

    /**
     * The payload slots the watch face shows for these settings (main.c prv_push_weather_to_layers:
     * GRAPH_HOURS from 12 − past + hours since the fetch, cut at valid_hours). Gust / storm / fog / snow
     * flags outside this window never reach the watch's event row, so the app mustn't report them.
     */
    fun watchSlots(p: WeatherPayload, s: WidgetSettings, nowMillis: Long, tz: TimeZone): IntRange {
        val w = WidgetRenderer.Window.of(p, s, nowMillis, tz)
        return w.startSlot until min(w.startSlot + s.totalHours, min(p.validHours, WeatherPayload.HOURS))
    }

    /**
     * Calendar days from today with at least [minHours] forecast hours in the payload. Event words
     * (gusts, thunder, fog, snow) only come from [eventSlots], the hours the watch face shows.
     */
    fun days(p: WeatherPayload, nowMillis: Long, tz: TimeZone, minHours: Int = 6,
             eventSlots: IntRange = 0 until WeatherPayload.HOURS): List<Day> {
        val cal = Calendar.getInstance(tz)
        fun dayStart(t: Long): Long = cal.run {
            timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); timeInMillis
        }
        val today = dayStart(nowMillis)
        val groups = LinkedHashMap<Long, MutableList<Int>>()
        for (j in 0 until min(p.validHours, WeatherPayload.HOURS)) {
            val d = dayStart(slotMillis(p, j))
            if (d >= today) groups.getOrPut(d) { ArrayList() }.add(j)
        }
        return groups.filter { it.value.size >= minHours }.map { (d, js) ->
            val ev = LinkedHashSet<String>()
            js.filter { it in eventSlots }.forEach { j ->
                if (p.storm[j] || p.weatherCode[j] in 95..99) ev += "thunder"
                if (p.wind[j]) ev += "gusts"
                if (p.fog[j]) ev += "fog"
                if (p.weatherCode[j] in 71..77 || p.weatherCode[j] in 85..86) ev += "snow"
            }
            // Daytime condition: the most severe code between 8 and 20 h (else any hour).
            val day = js.filter { hourOf(slotMillis(p, it), tz) in 8..20 }.ifEmpty { js }
            Day(d, js.maxOf { p.tempHourly[it] }, js.minOf { p.tempHourly[it] },
                if (p.haveMm) js.sumOf { p.precipMmTenths[it] } / 10.0 else null, js.maxOf { p.precipProb[it] },
                day.maxBy { severity(p.weatherCode[it]) }.let { p.weatherCode[it] }, ev.toList(), js.size)
        }
    }

    private fun hourOf(t: Long, tz: TimeZone) = Calendar.getInstance(tz).apply { timeInMillis = t }.get(Calendar.HOUR_OF_DAY)

    private fun severity(code: Int) = when {
        code >= 95 -> 9; code in 71..77 || code in 85..86 -> 8; code in 61..67 || code in 80..82 -> 7
        code in 51..57 -> 6; code in 45..48 || code in 10..12 -> 5; code == 3 -> 4; code == 2 -> 3; code == 1 -> 2
        else -> 1
    }

    /** WMO weather code → short English name (weather.c weather_code_to_condition groups). */
    fun conditionName(code: Int): String = when {
        code < 0 -> "—"
        code == 0 -> "Clear"
        code == 1 -> "Mainly clear"
        code == 2 -> "Partly cloudy"
        code <= 5 -> "Overcast"
        code <= 9 -> "Dust / wind"
        code <= 12 -> "Mist"
        code == 13 || code == 17 || code == 29 -> "Thunder"
        code <= 16 -> "Showers nearby"
        code == 18 -> "Squalls"
        code == 19 -> "Funnel cloud"
        code == 20 -> "Drizzle"
        code == 21 || code == 25 -> "Rain"
        code == 22 -> "Snow"
        code <= 24 || code == 26 -> "Sleet"
        code == 27 -> "Hail"
        code == 28 -> "Fog"
        code <= 35 -> "Dust storm"
        code <= 39 -> "Blowing snow"
        code <= 49 -> "Fog"
        code <= 55 -> "Drizzle"
        code <= 57 -> "Freezing drizzle"
        code <= 63 -> "Rain"
        code <= 65 -> "Heavy rain"
        code <= 69 -> "Freezing rain"
        code <= 73 -> "Snow"
        code <= 75 -> "Heavy snow"
        code <= 79 -> "Sleet"
        code <= 81 -> "Rain showers"
        code == 82 -> "Heavy showers"
        code <= 84 -> "Sleet showers"
        code == 85 -> "Snow showers"
        code == 86 -> "Heavy snow showers"
        code <= 90 -> "Hail"
        code <= 94 -> "Showers after thunder"
        code <= 96 || code == 98 -> "Thunderstorm"
        else -> "Severe thunderstorm"
    }

    fun fmtTemp(t: Double?, celsius: Boolean): String = if (t == null) "—" else "${(if (celsius) t else t * 9 / 5 + 32).roundToInt()}°"

    /** Sunlight as a share of a clear midday sky (≈ 1000 W/m²). */
    fun sunPercent(wm2: Int): Int = max(0, min(100, (wm2 / 10.0).roundToInt()))
}
