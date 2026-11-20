package com.nilsreichhard.carbonion.widget

import android.content.Context
import org.json.JSONObject

/**
 * Widget state outside the watch's Clay settings: last good weather, the
 * settings dict computed by the JS, the last phone location, and the
 * widget-only background and look options ([UiOptions]).
 */
object WidgetStore {
    const val PREFS = "carbon_widget"
    const val BG_TRANSPARENT = "transparent"
    const val BG_COLOR = "color"

    data class Background(val mode: String = BG_TRANSPARENT, val color: Int = 0xFFFFFF, val opacity: Int = 100) {
        /** ARGB for the root view; 0 when transparent. */
        val argb: Int get() = if (mode == BG_TRANSPARENT) 0 else ((opacity.coerceIn(0, 100) * 255 / 100) shl 24) or (color and 0xFFFFFF)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Where the last fetch's coordinates came from ([LocationHelper.Place.source]). */
    fun placeSource(ctx: Context): String? = prefs(ctx).getString("place_source", null)
    fun savePlaceSource(ctx: Context, source: String) = prefs(ctx).edit().putString("place_source", source).apply()

    fun saveWeather(ctx: Context, payloadJson: String, city: String, settingsDict: String) {
        // The JS converts temperatures to the unit chosen at fetch time; remember it for unit changes while offline.
        val fahrenheit = !WidgetSettings.fromDict(settingsDict).celsius
        prefs(ctx).edit().putString("payload", payloadJson).putString("city", city).putString("dict", settingsDict)
            .putBoolean("payload_f", fahrenheit).apply()
    }

    fun saveDict(ctx: Context, settingsDict: String) = prefs(ctx).edit().putString("dict", settingsDict).apply()

    fun payload(ctx: Context): WeatherPayload? =
        prefs(ctx).getString("payload", null)?.let { runCatching { WeatherPayload.fromJson(it) }.getOrNull() }

    /** The last good weather in the unit [celsius] asks for (cached data is converted when the unit changed since). */
    fun payload(ctx: Context, celsius: Boolean): WeatherPayload? {
        val p = payload(ctx) ?: return null
        val savedF = prefs(ctx).getBoolean("payload_f", false)
        return if (savedF == !celsius) p else p.inUnit(toFahrenheit = !celsius)
    }

    fun city(ctx: Context): String = prefs(ctx).getString("city", null) ?: ""

    fun settings(ctx: Context): WidgetSettings = WidgetSettings.fromDict(prefs(ctx).getString("dict", null))

    fun background(ctx: Context): Background = prefs(ctx).let {
        Background(it.getString("bg_mode", BG_TRANSPARENT) ?: BG_TRANSPARENT, it.getInt("bg_color", 0xFFFFFF), it.getInt("bg_opacity", 100))
    }

    fun saveBackground(ctx: Context, b: Background) =
        prefs(ctx).edit().putString("bg_mode", b.mode).putInt("bg_color", b.color).putInt("bg_opacity", b.opacity).apply()

    fun ui(ctx: Context): UiOptions = UiOptions.decode(prefs(ctx).getString("ui", null))

    fun saveUi(ctx: Context, o: UiOptions) = prefs(ctx).edit().putString("ui", o.encode()).apply()

    fun savePhoneLocation(ctx: Context, lat: Double, lon: Double) =
        prefs(ctx).edit().putString("phone_loc", JSONObject().put("lat", lat).put("lon", lon).put("t", System.currentTimeMillis()).toString()).apply()

    fun phoneLocation(ctx: Context): Pair<Double, Double>? = prefs(ctx).getString("phone_loc", null)?.let {
        runCatching { JSONObject(it).let { o -> o.getDouble("lat") to o.getDouble("lon") } }.getOrNull()
    }
}
