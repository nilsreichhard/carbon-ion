package com.nilsreichhard.carbonion.widget

import org.json.JSONArray
import org.json.JSONObject

/**
 * The weather payload exactly as the watch receives it: the object returned
 * by src/pkjs/weather/provider.js toPayload() (72 hourly slots, slot 12 =
 * the fetch hour). Only the fields the widget draws are kept.
 */
class WeatherPayload(
    val precipProb: IntArray,
    val tempHourly: DoubleArray,
    val apparentHourly: DoubleArray,
    val cloudCover: IntArray,
    val cloudLow: IntArray,
    val cloudMid: IntArray,
    val cloudHigh: IntArray,
    val weatherCode: IntArray,
    /** W/m² (payload sends W/m² / 4, like the watch's packed byte). */
    val shortwaveWm2: IntArray,
    /** 0.1 mm/h steps (packMm). */
    val precipMmTenths: IntArray,
    /** 0 none, 1 light, 2 normal, 3 thick (thickBits, 2 bits per hour). */
    val cloudThick: IntArray,
    /** Fog / strong-gust / strong-storm hours (fog_bits, wind_bits, storm_bits). */
    val fog: BooleanArray,
    val wind: BooleanArray,
    val storm: BooleanArray,
    /** Current WMO code (weather_code), for the condition icon by the city. */
    val currentCode: Int,
    val haveMm: Boolean,
    val validHours: Int,
    val currentTemp: Double?,
    val highTemp: Double?,
    val lowTemp: Double?,
    val sunriseHour: Int,
    val sunriseMinute: Int,
    val sunsetHour: Int,
    val sunsetMinute: Int,
    val fetchTime: Long,
    val provider: String,
    /** The payload JSON as received, for caching. */
    val rawJson: String,
) {
    /** The same forecast with temperatures converted between °C and °F (the JS sends the unit chosen at fetch time). */
    fun inUnit(toFahrenheit: Boolean): WeatherPayload {
        fun cv(t: Double) = if (toFahrenheit) t * 9.0 / 5.0 + 32.0 else (t - 32.0) * 5.0 / 9.0
        return WeatherPayload(precipProb, DoubleArray(tempHourly.size) { cv(tempHourly[it]) },
            DoubleArray(apparentHourly.size) { cv(apparentHourly[it]) }, cloudCover, cloudLow, cloudMid, cloudHigh, weatherCode,
            shortwaveWm2, precipMmTenths, cloudThick, fog, wind, storm, currentCode, haveMm, validHours,
            currentTemp?.let(::cv), highTemp?.let(::cv), lowTemp?.let(::cv), sunriseHour, sunriseMinute, sunsetHour, sunsetMinute,
            fetchTime, provider, rawJson)
    }

    companion object {
        const val HOURS = 72

        fun fromJson(json: String): WeatherPayload = fromJson(JSONObject(json))

        fun fromJson(o: JSONObject): WeatherPayload {
            fun ints(k: String, n: Int = HOURS) = IntArray(n) { i -> o.optJSONArray(k)?.optDouble(i, 0.0)?.toInt() ?: 0 }
            fun dbls(k: String) = DoubleArray(HOURS) { i -> o.optJSONArray(k)?.optDouble(i, 0.0) ?: 0.0 }
            fun optD(k: String): Double? = if (o.isNull(k) || !o.has(k)) null else o.optDouble(k)
            val sw = o.optJSONArray("shortwave_radiation") ?: JSONArray()
            /** main.c prv_bit_view: bit k of byte k/8. */
            fun bits(k: String): BooleanArray {
                val b = ints(k, HOURS / 8 + 1)
                return BooleanArray(HOURS) { i -> (b[i shr 3] shr (i and 7)) and 1 == 1 }
            }
            val thickBytes = ints("cloud_thick", HOURS / 4)
            return WeatherPayload(
                precipProb = ints("precip_prob"),
                tempHourly = dbls("temp_hourly"),
                apparentHourly = dbls("apparent_temp_hourly"),
                cloudCover = ints("cloud_cover"),
                cloudLow = ints("cloud_cover_low"),
                cloudMid = ints("cloud_cover_mid"),
                cloudHigh = ints("cloud_cover_high"),
                weatherCode = ints("hourly_weather_code"),
                shortwaveWm2 = IntArray(HOURS) { i -> Math.round(sw.optDouble(i, 0.0) * 4).toInt() },
                precipMmTenths = ints("precip_mm"),
                cloudThick = IntArray(HOURS) { k -> (thickBytes[k shr 2] shr ((k and 3) * 2)) and 3 },
                fog = bits("fog_bits"),
                wind = bits("wind_bits"),
                storm = bits("storm_bits"),
                currentCode = o.optInt("weather_code", -1),
                haveMm = (o.optInt("wx_flags", 0) and 2) != 0,
                validHours = o.optInt("valid_hours", HOURS),
                currentTemp = optD("current_temp"),
                highTemp = optD("high_temp"),
                lowTemp = optD("low_temp"),
                sunriseHour = o.optInt("sunrise_hour", 6),
                sunriseMinute = o.optInt("sunrise_minute", 0),
                sunsetHour = o.optInt("sunset_hour", 20),
                sunsetMinute = o.optInt("sunset_minute", 0),
                fetchTime = o.optLong("fetch_time", 0L),
                provider = o.optString("provider", ""),
                rawJson = o.toString(),
            )
        }
    }
}
