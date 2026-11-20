package com.nilsreichhard.carbonion.widget

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Crafted Open-Meteo responses for the sample gallery: the recorded Villars
 * response shifted to the current hour, with the hourly fields rewritten per
 * scenario. They run through the real watch JS, so the payloads carry the
 * same bits (fog / wind / storm), amounts and thickness the watch would get.
 */
object SampleScenarios {
    val names = listOf("villars", "sunny", "snow", "thunder", "fog")
    private val tz = TimeZone.getTimeZone("Europe/Zurich")

    fun response(name: String, nowSec: Long): String {
        val src = javaClass.classLoader!!.getResource("fixtures/api.open-meteo.com.json")!!.readText()
        val d = JSONObject(src)
        val h = d.getJSONObject("hourly")
        val t0 = h.getJSONArray("time").getLong(12)
        val delta = nowSec / 3600 * 3600 - t0
        shift(h.getJSONArray("time"), delta)
        d.getJSONObject("current").let { it.put("time", it.getLong("time") + delta) }
        d.getJSONObject("daily").let { dl -> listOf("time", "sunrise", "sunset").forEach { shift(dl.getJSONArray(it), delta) } }
        val n = h.getJSONArray("time").length()
        fun set(k: String, f: (i: Int, hod: Int) -> Number) {
            val a = JSONArray()
            for (i in 0 until n) {
                val hod = Calendar.getInstance(tz).apply { timeInMillis = h.getJSONArray("time").getLong(i) * 1000 }.get(Calendar.HOUR_OF_DAY)
                a.put(f(i, hod))
            }
            h.put(k, a)
        }
        fun sun(hod: Int, peak: Double) = max(0.0, sin((hod - 7.5) / 9.0 * PI)) * peak
        fun wave(i: Int, base: Double, amp: Double, hod: Int) = base + amp * sin((hod - 9) / 24.0 * 2 * PI) + (i % 5) * 0.1
        val cur = d.getJSONObject("current")
        when (name) {
            "villars" -> {}
            "sunny" -> {
                set("weather_code") { i, hod -> if (i in 40..46) 2 else if (sun(hod, 1.0) > 0.2) 0 else 1 }
                set("cloud_cover_low") { i, _ -> if (i in 40..46) 35 else 0 }
                set("cloud_cover_mid") { i, _ -> if (i in 38..48) 20 else 0 }
                set("cloud_cover_high") { i, _ -> if (i in 18..26) 45 else if (i in 36..50) 30 else 5 }
                set("cloud_cover") { i, _ -> if (i in 40..46) 45 else if (i in 18..26) 30 else 5 }
                set("precipitation") { _, _ -> 0.0 }; set("precipitation_probability") { _, _ -> 0 }
                set("snowfall") { _, _ -> 0.0 }
                set("shortwave_radiation") { _, hod -> sun(hod, 520.0) }
                set("temperature_2m") { i, hod -> wave(i, 9.0, 6.0, hod) }
                set("apparent_temperature") { i, hod -> wave(i, 8.0, 6.0, hod) }
                set("visibility") { _, _ -> 40000 }; set("wind_gusts_10m") { _, _ -> 12 }
                cur.put("weather_code", 0).put("temperature_2m", 12.4)
            }
            "snow" -> {
                set("weather_code") { i, _ -> when (i) { in 10..15 -> 71; in 16..22 -> 73; in 23..28 -> 75; in 29..33 -> 73; in 34..40 -> 3; in 41..52 -> 85; else -> 3 } }
                set("cloud_cover_low") { i, _ -> if (i in 34..40) 60 else 100 }
                set("cloud_cover_mid") { _, _ -> 100 }
                set("cloud_cover_high") { i, _ -> if (i in 34..40) 30 else 80 }
                set("cloud_cover") { _, _ -> 100 }
                set("precipitation") { i, _ -> when (i) { in 10..15 -> 0.4; in 16..22 -> 1.2; in 23..28 -> 2.8; in 29..33 -> 1.0; in 41..52 -> 0.6; else -> 0.0 } }
                set("snowfall") { i, _ -> when (i) { in 10..15 -> 0.3; in 16..22 -> 0.9; in 23..28 -> 2.0; in 29..33 -> 0.7; in 41..52 -> 0.4; else -> 0.0 } }
                set("precipitation_probability") { i, _ -> if (i in 10..33 || i in 41..52) 90 else 20 }
                set("shortwave_radiation") { _, hod -> sun(hod, 60.0) }
                set("temperature_2m") { i, hod -> wave(i, -4.0, 2.5, hod) }
                set("apparent_temperature") { i, hod -> wave(i, -9.0, 2.5, hod) }
                set("visibility") { i, _ -> if (i in 23..28) 1500 else 8000 }; set("wind_gusts_10m") { _, _ -> 25 }
                cur.put("weather_code", 73).put("temperature_2m", -3.2).put("snowfall", 0.9)
            }
            "thunder" -> {
                set("weather_code") { i, _ -> when (i) { in 14..15 -> 80; in 16..19 -> 95; 20 -> 96; in 21..23 -> 81; in 30..32 -> 95; else -> 2 } }
                set("cape") { i, _ -> if (i in 16..20) 2200 else if (i in 30..32) 900 else 150 }
                set("cloud_cover_low") { i, _ -> if (i in 14..24 || i in 29..33) 90 else 20 }
                set("cloud_cover_mid") { i, _ -> if (i in 13..25 || i in 28..34) 100 else 30 }
                set("cloud_cover_high") { i, _ -> if (i in 10..26) 70 else 40 }
                set("cloud_cover") { i, _ -> if (i in 13..25 || i in 28..34) 100 else 40 }
                set("precipitation") { i, _ -> when (i) { in 14..15 -> 1.5; in 16..19 -> 9.0; 20 -> 14.0; in 21..23 -> 3.0; in 30..32 -> 5.0; else -> 0.0 } }
                set("precipitation_probability") { i, _ -> if (i in 14..23 || i in 30..32) 85 else 10 }
                set("snowfall") { _, _ -> 0.0 }
                set("shortwave_radiation") { i, hod -> if (i in 14..24) sun(hod, 90.0) else sun(hod, 420.0) }
                set("wind_gusts_10m") { i, _ -> if (i in 13..22) 72 else if (i in 36..40) 58 else 20 }
                set("temperature_2m") { i, hod -> wave(i, 21.0, 5.0, hod) - if (i in 16..22) 4 else 0 }
                set("apparent_temperature") { i, hod -> wave(i, 22.0, 5.0, hod) }
                set("visibility") { _, _ -> 20000 }
                cur.put("weather_code", 95).put("temperature_2m", 22.8)
            }
            "fog" -> {
                set("weather_code") { i, _ -> if (i in 6..20 || i in 32..42) 45 else if (i in 21..31) 1 else 3 }
                set("visibility") { i, _ -> if (i in 6..20 || i in 32..42) 150 else 6000 }
                set("cloud_cover_low") { i, _ -> if (i in 6..20 || i in 32..42) 100 else 10 }
                set("cloud_cover_mid") { _, _ -> 0 }
                set("cloud_cover_high") { i, _ -> if (i in 21..31) 25 else 10 }
                set("cloud_cover") { i, _ -> if (i in 6..20 || i in 32..42) 100 else 25 }
                set("precipitation") { _, _ -> 0.0 }; set("precipitation_probability") { _, _ -> 5 }
                set("snowfall") { _, _ -> 0.0 }
                set("shortwave_radiation") { i, hod -> if (i in 21..31) sun(hod, 380.0) else sun(hod, 40.0) }
                set("temperature_2m") { i, hod -> wave(i, 3.0, 3.0, hod) }
                set("apparent_temperature") { i, hod -> wave(i, 1.0, 3.0, hod) }
                set("wind_gusts_10m") { _, _ -> 6 }
                cur.put("weather_code", 45).put("temperature_2m", 2.1)
            }
            else -> error(name)
        }
        return d.toString()
    }

    private fun shift(a: JSONArray, delta: Long) { for (i in 0 until a.length()) a.put(i, a.getLong(i) + delta) }
}
