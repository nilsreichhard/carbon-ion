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

    /** The settings dict with the theme resolved: Follow system (the default) reads the phone's dark mode now. */
    fun settings(ctx: Context): WidgetSettings {
        val s = WidgetSettings.fromDict(prefs(ctx).getString("dict", null))
        val mode = themeMode(ctx, s)
        return s.copy(themeMode = mode, lightTheme = when (mode) {
            WidgetSettings.THEME_DARK -> false
            WidgetSettings.THEME_LIGHT -> true
            else -> !systemDark()
        })
    }

    /**
     * 1.0.0 stored Light (1) as the seeded default, so until the user saves settings in 1.0.1+ a stored
     * Light counts as Follow system (the JS migrates its copy the same way on its next run).
     */
    private fun themeMode(ctx: Context, s: WidgetSettings): Int =
        if (s.themeMode == WidgetSettings.THEME_LIGHT && !prefs(ctx).getBoolean(THEME_CHOSEN, false)) WidgetSettings.THEME_SYSTEM
        else s.themeMode

    /** Set once the user saves the settings page, so an explicit Light is kept from then on. */
    fun markThemeChosen(ctx: Context) = prefs(ctx).edit().putBoolean(THEME_CHOSEN, true).apply()
    private const val THEME_CHOSEN = "theme_chosen"

    /** The phone's current dark mode (system configuration, not an activity's AppCompat override). */
    fun systemDark(): Boolean =
        (android.content.res.Resources.getSystem().configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** AppCompat night mode for the app screens. */
    fun nightMode(s: WidgetSettings): Int = when (s.themeMode) {
        WidgetSettings.THEME_DARK -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        WidgetSettings.THEME_LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    fun background(ctx: Context): Background = prefs(ctx).let {
        Background(it.getString("bg_mode", BG_TRANSPARENT) ?: BG_TRANSPARENT, it.getInt("bg_color", 0xFFFFFF), it.getInt("bg_opacity", 100))
    }

    fun saveBackground(ctx: Context, b: Background) =
        prefs(ctx).edit().putString("bg_mode", b.mode).putInt("bg_color", b.color).putInt("bg_opacity", b.opacity).apply()

    fun ui(ctx: Context): UiOptions = UiOptions.decode(prefs(ctx).getString("ui", null))

    fun saveUi(ctx: Context, o: UiOptions) = prefs(ctx).edit().putString("ui", o.encode()).apply()

    fun savePhoneLocation(ctx: Context, lat: Double, lon: Double, accuracyM: Float? = null, precise: Boolean = false,
                          fixTime: Long = System.currentTimeMillis()) =
        prefs(ctx).edit().putString("phone_loc", JSONObject().put("lat", lat).put("lon", lon).put("t", fixTime)
            .put("precise", precise).apply { accuracyM?.let { put("acc", it.toDouble()) } }.toString()).apply()

    /** The saved phone fix as a place (accuracy / precise unknown for fixes saved before 1.1.4). */
    fun phoneFix(ctx: Context): LocationHelper.Place? = prefs(ctx).getString("phone_loc", null)?.let {
        runCatching { JSONObject(it).let { o -> LocationHelper.Place(o.getDouble("lat"), o.getDouble("lon"), false, LocationHelper.SOURCE_PHONE,
            if (o.has("acc")) o.getDouble("acc").toFloat() else null, o.optBoolean("precise", false),
            if (o.has("t")) o.getLong("t") else null) } }.getOrNull()
    }

    /** 1.1.4: provider / model the last fetch really used ({provider, requested, model, fallback}). */
    fun saveUsed(ctx: Context, json: String?) = prefs(ctx).edit().putString("wx_used", json).apply()
    fun used(ctx: Context): JSONObject? = prefs(ctx).getString("wx_used", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** 1.1.4: the place the last fetch used, shown in the app (coordinates, source, accuracy). */
    fun savePlaceUsed(ctx: Context, p: LocationHelper.Place) = prefs(ctx).edit().putString("place_used", JSONObject()
        .put("lat", p.lat).put("lon", p.lon).put("source", p.source).put("precise", p.precise)
        .apply { p.accuracyM?.let { put("acc", it.toDouble()) }; p.fixTime?.let { put("ft", it) } }.toString()).putString("place_source", p.source).apply()

    fun placeUsed(ctx: Context): LocationHelper.Place? = prefs(ctx).getString("place_used", null)?.let {
        runCatching { JSONObject(it).let { o -> LocationHelper.Place(o.getDouble("lat"), o.getDouble("lon"), o.getString("source") == LocationHelper.SOURCE_FIXED,
            o.getString("source"), if (o.has("acc")) o.getDouble("acc").toFloat() else null, o.optBoolean("precise", false),
            if (o.has("ft")) o.getLong("ft") else null) } }.getOrNull()
    }

    fun phoneLocation(ctx: Context): Pair<Double, Double>? = prefs(ctx).getString("phone_loc", null)?.let {
        runCatching { JSONObject(it).let { o -> o.getDouble("lat") to o.getDouble("lon") } }.getOrNull()
    }
}
