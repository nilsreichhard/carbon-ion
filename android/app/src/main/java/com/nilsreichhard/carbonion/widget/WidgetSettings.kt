package com.nilsreichhard.carbonion.widget

import org.json.JSONObject

/**
 * The settings dict the watch receives (src/pkjs/settings.js
 * buildSettingsDict, computed by the bundled JS), with the C defaults from
 * src/c/modules/settings.c for anything missing.
 */
data class WidgetSettings(
    /** Resolved light/dark for drawing (WidgetStore.settings resolves [themeMode] SYSTEM against the phone). */
    val lightTheme: Boolean = true,
    /** SETTING_LIGHT_THEME: 0 Dark, 1 Light, 2 Follow system (Android only; also when unset). */
    val themeMode: Int = THEME_SYSTEM,
    val celsius: Boolean = true,
    val forecastHours: Int = 24,
    val cloudSensitivity: Int = 1,
    val cloudSplit: Boolean = false,
    val cloudThick: Boolean = true,
    val sunSensitivity: Int = 4,
    val precipSensitivity: Int = 1,
    /** 0 Probability, 1 Amount + checked dots, 2 Amount + line (RainHeightMode). */
    val rainHeight: Int = 0,
    /** 0 Range + icon, 1 Icon only, 2 None (WeatherEventDisplay). */
    val eventDisplay: Int = 0,
    /** 0 Future, 1 Past, 2 Full, 3 None (InfillMode). */
    val infill: Int = 0,
    /** 0 Top and bottom, 1 Top only, 2 Bottom only, 3 Hidden (NeedleMode). */
    val needle: Int = 0,
    /** 0 Auto, 1 On, 2 Off (LeadingZero). */
    val leadingZero: Int = 0,
    val dateFormat: String = "%A, %b %d",
    /** SETTING_EVENT_FLAGS (EVENT_HIDE_* | EVENT_THUNDER_STRONG | snow level << 4). */
    val eventFlags: Int = 0,
    /** SETTING_FOG_INDICATOR */
    val fogIndicator: Boolean = true,
    /** SETTING_TIMELINE_BATTERY, Android levels (1.1.1): 0 20/10/5/0, 1 10/5/0, 2 5/0, 6 20/0, 7 10/0, 5 0 only, 8 off (1.1.3).
     *  The watch's 4 % (battery saver) is 5 % here; its "10 / 4" (4) reads as 10/5/0 and "None" (3) as 0 only. */
    val timelineBattery: Int = 0,
    /** SETTING_PHONE_ALARM (1.1.3 on the phone): next alarm after the date on the widget and in the app. */
    val showAlarm: Boolean = true,
    /** SETTING_ANDROID_ALARM_WATCH (1.1.3, phone only): send the next alarm to the watch face. */
    val alarmToWatch: Boolean = true,
) {
    val pastHours get() = forecastHours / 4
    /** Battery marker levels (%) for [timelineBattery], highest first. */
    val batteryLevels: List<Int> get() = batteryLevels(timelineBattery)
    val totalHours get() = forecastHours + forecastHours / 4

    companion object {
        const val THEME_DARK = 0
        const val THEME_LIGHT = 1
        const val THEME_SYSTEM = 2

        fun fromDict(json: String?): WidgetSettings {
            if (json.isNullOrEmpty() || json == "null") return WidgetSettings()
            val d = JSONObject(json)
            val def = WidgetSettings()
            fun i(k: String, dv: Int) = if (d.has(k)) d.optInt(k, dv) else dv
            val fh = i("SETTING_FORECAST_HOURS", def.forecastHours)
            return WidgetSettings(
                // Without a phone context SYSTEM draws light; WidgetStore.settings() resolves it.
                themeMode = i("SETTING_LIGHT_THEME", THEME_SYSTEM).let { if (it in 0..2) it else THEME_SYSTEM },
                lightTheme = i("SETTING_LIGHT_THEME", THEME_SYSTEM) != THEME_DARK,
                celsius = i("SETTING_TEMP_UNIT", 0) == 0,
                forecastHours = if (fh in intArrayOf(12, 18, 24, 36, 48)) fh else 24,
                cloudSensitivity = i("SETTING_CLOUD_SENSITIVITY", 1).coerceIn(0, 4),
                cloudSplit = i("SETTING_CLOUD_DISPLAY_MODE", 0) == 1,
                cloudThick = i("SETTING_CLOUD_THICK", 0) == 0,
                sunSensitivity = i("SETTING_SUNLIGHT_SENSITIVITY", 4).coerceIn(0, 4),
                precipSensitivity = i("SETTING_PRECIP_SENSITIVITY", 1).coerceIn(0, 4),
                rainHeight = i("SETTING_RAIN_HEIGHT", 0).let { if (it in 0..2) it else 0 },
                eventDisplay = i("SETTING_WEATHER_EVENT_DISPLAY", 0).coerceIn(0, 2),
                infill = i("SETTING_INFILL_MODE", 0).coerceIn(0, 3),
                needle = i("SETTING_NEEDLE_MODE", 0).coerceIn(0, 3),
                leadingZero = i("SETTING_LEADING_ZERO", 0).coerceIn(0, 2),
                dateFormat = d.optString("SETTING_DATE_FORMAT", def.dateFormat).ifEmpty { def.dateFormat },
                eventFlags = i("SETTING_EVENT_FLAGS", 0) and 0x3F,
                fogIndicator = i("SETTING_FOG_INDICATOR", 1) != 0,
                timelineBattery = normalizeBattery(i("SETTING_TIMELINE_BATTERY", BATTERY_ALL)),
                showAlarm = i("SETTING_PHONE_ALARM", 1) != 0,
                alarmToWatch = i("SETTING_ANDROID_ALARM_WATCH", 1) != 0,
            )
        }

        const val BATTERY_ALL = 0
        const val BATTERY_ZERO_ONLY = 5
        /** 1.1.3: no battery markers at all (Android only; not a watch value). */
        const val BATTERY_OFF = 8

        /** Old or unknown values onto the Android options (watch "10 / 4" → 10/5/0, "None" → 0 only). */
        fun normalizeBattery(v: Int): Int = when (v) {
            0, 1, 2, BATTERY_ZERO_ONLY, 6, 7, BATTERY_OFF -> v
            4 -> 1
            3 -> BATTERY_ZERO_ONLY
            else -> BATTERY_ALL
        }

        fun batteryLevels(v: Int): List<Int> = when (normalizeBattery(v)) {
            1 -> listOf(10, 5, 0)
            2 -> listOf(5, 0)
            6 -> listOf(20, 0)
            7 -> listOf(10, 0)
            BATTERY_ZERO_ONLY -> listOf(0)
            BATTERY_OFF -> emptyList()
            else -> listOf(20, 10, 5, 0)
        }

        /** TextClock pattern for the watch's leading-zero rule (time_layer.c). */
        fun clockPattern(is24h: Boolean, leadingZero: Int): String {
            val strip = leadingZero == 2 || (leadingZero == 0 && !is24h)
            return if (is24h) (if (strip) "H:mm" else "HH:mm") else (if (strip) "h:mm" else "hh:mm")
        }

        /** strftime (Clay "Date format") → ICU pattern; the watch strips
         *  leading zeros from day/month (time_layer.c prv_format_date). */
        fun datePattern(strftime: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < strftime.length) {
                val c = strftime[i]
                if (c == '%' && i + 1 < strftime.length) {
                    sb.append(when (strftime[i + 1]) {
                        'A' -> "EEEE"; 'a' -> "EEE"; 'B' -> "MMMM"; 'b' -> "MMM"
                        'd', 'e' -> "d"; 'm' -> "M"; 'Y' -> "yyyy"; 'y' -> "yy"
                        else -> ""
                    })
                    i += 2
                } else {
                    if (c.isLetter()) sb.append('\'').append(c).append('\'') else sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }
    }
}
