package com.nilsreichhard.carbonion.widget

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt
import com.google.android.material.R as M

/**
 * The expanded screen (tap the widget, or the app icon): header with city,
 * temperature, condition and next alarm; the large Modern chart with
 * touch-to-inspect; the next days from the hourly forecast; when and where
 * the data came from; Refresh and Settings in the toolbar. Light/dark follows
 * the theme setting (Follow system by default); Material You colours on Android 12+.
 */
class DetailActivity : AppCompatActivity() {
    private val dp get() = resources.displayMetrics.density
    private lateinit var content: LinearLayout
    private lateinit var chart: ChartView
    private lateinit var details: LinearLayout
    private var hours = 0
    private var light = true
    private var hadPermission = false
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "payload" || key == "dict" || key == "city" || key == "ui" || key == "place_source") runOnUiThread {
            if (WidgetStore.settings(this).lightTheme != light) recreate() else bind()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val st = WidgetStore.settings(this)
        light = st.lightTheme
        // Follow system (default): AppCompat tracks the phone; Light / Dark force it.
        AppCompatDelegate.setDefaultNightMode(WidgetStore.nightMode(st))
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        WidgetUpdater.ensureIconFont(this)
        hours = savedInstanceState?.getInt("hours") ?: 0
        hadPermission = LocationHelper.hasPermission(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        val bar = MaterialToolbar(this).apply {
            title = getString(R.string.app_name)
            menu.add(0, 1, 0, R.string.detail_refresh).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(0, 2, 1, R.string.detail_settings).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { if (it.itemId == 1) refresh() else openSettings(); true }
        }
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(4), px(16), px(24))
        }
        root.addView(NestedScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
        // Status/navigation bar icons dark on the light theme, light on the dark one.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        bind()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); outState.putInt("hours", hours)
    }

    override fun onStart() {
        super.onStart()
        getSharedPreferences(WidgetStore.PREFS, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener)
        LocationHelper.refreshInForeground(applicationContext)
    }

    override fun onStop() {
        getSharedPreferences(WidgetStore.PREFS, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onStop()
    }

    private fun px(v: Int) = (v * dp).roundToInt()
    private fun color(attr: Int) = MaterialColors.getColor(content, attr)

    private fun text(s: CharSequence, appearance: Int, colorAttr: Int = M.attr.colorOnSurface) = TextView(this).apply {
        text = s
        val tv = TypedValue(); theme.resolveAttribute(appearance, tv, true)
        TextViewCompat.setTextAppearance(this, tv.resourceId)
        setTextColor(color(colorAttr))
    }

    private fun card(filled: Boolean = false) = MaterialCardView(this, null, if (filled) M.attr.materialCardViewFilledStyle else M.attr.materialCardViewOutlinedStyle).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(12) }
    }

    private fun column(pad: Int = 16) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(pad), px(pad), px(pad), px(pad)) }

    /** (Re)builds everything from the stored payload and settings. */
    private fun bind() {
        content.removeAllViews()
        val s = WidgetStore.settings(this)
        val p = WidgetStore.payload(this, s.celsius)
        val now = System.currentTimeMillis()
        val tz = TimeZone.getDefault()
        val ink = color(M.attr.colorOnSurface)

        // ---- header
        val head = card(filled = true)
        val hc = column(20)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(text(WidgetStore.city(this).ifEmpty { getString(R.string.app_name) }, M.attr.textAppearanceTitleLarge))
        val cond = p?.let { DetailData.conditionName(it.currentCode) } ?: getString(R.string.detail_no_data)
        left.addView(text(cond, M.attr.textAppearanceBodyLarge, M.attr.colorOnSurfaceVariant))
        if (p != null) left.addView(text(getString(R.string.detail_high_low, DetailData.fmtTemp(p.highTemp, true), DetailData.fmtTemp(p.lowTemp, true)),
            M.attr.textAppearanceBodyMedium, M.attr.colorOnSurfaceVariant))
        top.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        WidgetRenderer.conditionIcon(p, now, tz, ink, px(44))?.let { b ->
            top.addView(ImageView(this).apply { setImageBitmap(b); contentDescription = cond },
                LinearLayout.LayoutParams(px(44), px(44)).apply { marginEnd = px(10) })
        }
        top.addView(text(p?.currentTemp?.let { DetailData.fmtTemp(it, true) } ?: "—", M.attr.textAppearanceDisplayMedium))
        hc.addView(top)
        // No phone location: say so instead of silently showing the default place, with the fix right there.
        when (LocationPermission.state(this)) {
            LocationPermission.State.DENIED -> {
                hc.addView(text(getString(R.string.loc_unavailable), M.attr.textAppearanceBodyMedium, androidx.appcompat.R.attr.colorError).apply { setPadding(0, px(8), 0, 0) })
                hc.addView(MaterialButton(this, null, M.attr.materialButtonTonalStyle).apply {
                    setText(R.string.location_allow)
                    setOnClickListener { LocationPermission.ask(this@DetailActivity) }
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = px(6) })
            }
            LocationPermission.State.NO_FIX ->
                hc.addView(text(getString(R.string.loc_no_fix), M.attr.textAppearanceBodyMedium, androidx.appcompat.R.attr.colorError).apply { setPadding(0, px(8), 0, 0) })
            LocationPermission.State.OK -> {}
        }
        NextAlarm.info(this)?.let { a ->
            val label = NextAlarm.format(a.triggerMillis, now, DateFormat.is24HourFormat(this), s.leadingZero)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(12), 0, 0)
                isClickable = true; isFocusable = true
                val tv = TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); setBackgroundResource(tv.resourceId)
                setOnClickListener { openAlarms(a) }
                contentDescription = getString(R.string.alarm_description_at, label)
            }
            val ic = Bitmap.createBitmap(px(22), px(22), Bitmap.Config.ARGB_8888)
            AlarmBadge.drawIcon(Canvas(ic), px(11).toFloat(), px(11).toFloat(), px(20).toFloat(), color(androidx.appcompat.R.attr.colorPrimary))
            row.addView(ImageView(this).apply { setImageBitmap(ic) }, LinearLayout.LayoutParams(px(22), px(22)).apply { marginEnd = px(8) })
            row.addView(text(getString(R.string.detail_next_alarm, label), M.attr.textAppearanceTitleSmall, androidx.appcompat.R.attr.colorPrimary))
            hc.addView(row)
        }
        head.addView(hc); content.addView(head)

        // ---- chart + range chips + scrub details
        val cc = card()
        val col = column(0).apply { setPadding(0, px(12), 0, px(12)) }
        val chips = ChipGroup(this).apply { isSingleSelection = true; isSelectionRequired = true; setPadding(px(12), 0, px(12), px(6)) }
        val choices = listOf(12, 24, 36, 48)
        if (hours !in choices) hours = s.forecastHours.takeIf { it in choices } ?: 24
        for (hh in choices) chips.addView(Chip(this, null, M.attr.chipStyle).apply {
            id = View.generateViewId(); text = getString(R.string.detail_range, hh); isCheckable = true; isChecked = hh == hours
            setOnClickListener { hours = hh; chart.settings = s.copy(forecastHours = hh); chart.select(chart.selected) }
        })
        col.addView(text(getString(R.string.detail_forecast), M.attr.textAppearanceTitleMedium).apply { setPadding(px(16), 0, px(16), px(6)) })
        col.addView(chips)
        chart = ChartView(this).apply {
            textTypeface = WidgetStore.ui(this@DetailActivity).text().typeface(this@DetailActivity, clock = false)
            light = s.lightTheme
            accent = color(androidx.appcompat.R.attr.colorPrimary)
            nowMillis = now
            settings = s.copy(forecastHours = hours)
            payload = p
            onHour = { slot -> showHour(p, slot) }
            contentDescription = getString(R.string.detail_chart_description) + ". " + WidgetUpdater.chartDescription(this@DetailActivity, p)
        }
        col.addView(chart, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        details = column(16).apply { setPadding(px(16), px(10), px(16), 0) }
        col.addView(details)
        cc.addView(col); content.addView(cc)
        showHour(p, null)

        // ---- next days
        if (p != null) {
            val days = DetailData.days(p, now, tz)
            if (days.isNotEmpty()) {
                val dc = card(); val dl = column(16)
                dl.addView(text(getString(R.string.detail_days), M.attr.textAppearanceTitleMedium))
                val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())
                for ((i, d) in days.withIndex()) {
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(10), 0, 0) }
                    val name = if (i == 0 && d.startMillis <= now) getString(R.string.detail_today) else dayFmt.format(Date(d.startMillis))
                    val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    l.addView(text(name, M.attr.textAppearanceTitleSmall))
                    val bits = mutableListOf(DetailData.conditionName(d.code))
                    if (d.rainMm != null && d.rainMm >= 0.1) bits += getString(R.string.detail_rain_mm, d.rainMm, d.maxChance)
                    else if (d.maxChance > 0) bits += getString(R.string.detail_rain_chance, d.maxChance)
                    if (d.events.isNotEmpty()) bits += d.events.joinToString(", ")
                    if (d.hours < 24) bits += getString(R.string.detail_partial, d.hours)
                    l.addView(text(bits.joinToString(" · "), M.attr.textAppearanceBodySmall, M.attr.colorOnSurfaceVariant))
                    row.addView(l, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    row.addView(text("${DetailData.fmtTemp(d.high, true)} / ${DetailData.fmtTemp(d.low, true)}", M.attr.textAppearanceTitleMedium))
                    dl.addView(row)
                }
                dc.addView(dl); content.addView(dc)
            }
        }

        // ---- source + actions
        val dict = runCatching { JSONObject(getSharedPreferences(WidgetStore.PREFS, MODE_PRIVATE).getString("dict", null) ?: "{}") }.getOrNull()
        val provider = p?.provider?.ifEmpty { null } ?: dict?.optString("SETTING_WEATHER_PROVIDER")?.ifEmpty { null } ?: "openmeteo"
        val model = dict?.optString("SETTING_OM_MODEL")?.ifEmpty { null } ?: "best_match"
        val updated = p?.let { updatedLabel(it.fetchTime * 1000, now) } ?: "—"
        val src = if (provider.startsWith("openmeteo") || provider == "om") getString(R.string.detail_source_model, updated, providerName(provider), model)
            else getString(R.string.detail_source, updated, providerName(provider))
        // Refresh / Settings live in the toolbar only.
        content.addView(text(src, M.attr.textAppearanceBodySmall, M.attr.colorOnSurfaceVariant).apply { setPadding(px(4), px(16), px(4), px(8)) })
    }

    override fun onResume() {
        super.onResume()
        // Back from the app's system settings page with location now allowed: fetch and redraw.
        val granted = LocationHelper.hasPermission(this)
        if (granted && !hadPermission && WidgetStore.placeSource(this) == LocationHelper.VILLARS.source) LocationPermission.onGranted(this)
        if (granted != hadPermission) bind()
        hadPermission = granted
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != LocationPermission.REQUEST) return
        hadPermission = LocationHelper.hasPermission(this)
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) LocationPermission.onGranted(this)
        bind()
    }

    /** "14:05" today, "yesterday 14:05" / "Tue 14:05" otherwise, so an old forecast reads as old. */
    private fun updatedLabel(t: Long, now: Long): String {
        val time = SimpleDateFormat(if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a", Locale.getDefault()).format(Date(t))
        if (android.text.format.DateUtils.isToday(t)) return time
        val day = if (android.text.format.DateUtils.isToday(t + 86_400_000L)) getString(R.string.detail_yesterday)
            else SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(t))
        return "$day $time"
    }

    /** Provider labels as in src/pkjs/weather/caps.js. */
    private fun providerName(p: String) = PROVIDER_LABELS[p.lowercase()] ?: p

    companion object {
        val PROVIDER_LABELS = mapOf("openmeteo" to "Open-Meteo", "om" to "Open-Meteo", "metno" to "MET Norway (yr.no)", "yr" to "MET Norway (yr.no)",
            "geosphere" to "GeoSphere Austria", "meteoblue" to "Meteoblue", "tomorrow" to "Tomorrow.io",
            "owm" to "OpenWeatherMap", "openweathermap" to "OpenWeatherMap", "weatherapi" to "WeatherAPI.com")
    }

    /** The scrub details for payload [slot] (null: the hint). */
    private fun showHour(p: WeatherPayload?, slot: Int?) {
        details.removeAllViews()
        if (p == null || slot == null) {
            details.addView(text(getString(R.string.detail_scrub_hint), M.attr.textAppearanceBodyMedium, M.attr.colorOnSurfaceVariant)); return
        }
        val h = DetailData.hour(p, slot)
        val time = SimpleDateFormat(if (DateFormat.is24HourFormat(this)) "EEEE HH:mm" else "EEEE h a", Locale.getDefault()).format(Date(h.timeMillis))
        details.addView(text("$time · ${DetailData.conditionName(h.code)}", M.attr.textAppearanceTitleSmall))
        val rain = if (h.rainMm != null) getString(R.string.detail_rain_mm, h.rainMm, h.chance) else getString(R.string.detail_rain_chance, h.chance)
        val wind = when {
            h.storm -> getString(R.string.detail_wind_storm)
            h.gusts -> getString(R.string.detail_wind_gusts)
            else -> getString(R.string.detail_wind_none)
        }
        val items = listOf(
            R.string.detail_temp to "${DetailData.fmtTemp(h.temp, true)}  (${getString(R.string.detail_feels, DetailData.fmtTemp(h.feels, true))})",
            R.string.detail_rain to rain,
            R.string.detail_clouds to getString(R.string.detail_clouds_val, h.cloudLow, h.cloudMid, h.cloudHigh),
            R.string.detail_wind to wind,
            R.string.detail_sun to getString(R.string.detail_sun_val, h.sunWm2, DetailData.sunPercent(h.sunWm2)) +
                if (h.fog) " · " + getString(R.string.detail_fog) else "",
        )
        for ((label, value) in items) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(6), 0, 0) }
            // Label column grows with the font scale (104 dp at 100 %).
            row.addView(text(getString(label), M.attr.textAppearanceBodyMedium, M.attr.colorOnSurfaceVariant),
                LinearLayout.LayoutParams((px(104) * resources.configuration.fontScale.coerceAtLeast(1f)).roundToInt(), WRAP_CONTENT))
            row.addView(text(value, M.attr.textAppearanceBodyMedium).apply { typeface = Typeface.create(typeface, Typeface.NORMAL) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            details.addView(row)
        }
    }

    private fun refresh() {
        WeatherWorker.runNow(this)
        Snackbar.make(content, R.string.detail_refreshing, Snackbar.LENGTH_SHORT).show()
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openAlarms(a: NextAlarm.Info) {
        val i = TapIntents.showAlarms(this) ?: TapIntents.clockApp(this)
        if (i != null) startActivity(i) else runCatching { a.showIntent?.send() }
    }
}
