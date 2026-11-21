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
 * The expanded screen (tap the widget, or the app icon): the widget itself (mirror), touch or drag
 * its chart for an hour's details right under it (1.1.6: replaces the separate Forecast chart);
 * temperature, condition and where the data is for; the next days from the hourly forecast; when and where
 * the data came from; Refresh and Settings in the toolbar. Light/dark follows
 * the theme setting (Follow system by default); Material You colours on Android 12+.
 */
class DetailActivity : AppCompatActivity() {
    private val dp get() = resources.displayMetrics.density
    private lateinit var content: LinearLayout
    private lateinit var details: LinearLayout
    /** Payload slot inspected on the mirror (kept across rebuilds while it's on screen). */
    private var selectedSlot: Int? = null
    private var light = true
    private var hadPermission = false
    /** The settings dict the screen was last built from. */
    private var boundDict: String? = null
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
        selectedSlot = savedInstanceState?.getInt("slot", -1)?.takeIf { it >= 0 }
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
        super.onSaveInstanceState(outState); outState.putInt("slot", selectedSlot ?: -1)
    }

    override fun onStart() {
        super.onStart()
        val prefs = getSharedPreferences(WidgetStore.PREFS, MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        // Settings saved while this screen was stopped (the listener is off then): redraw now, e.g. the alarm row.
        if (::content.isInitialized && prefs.getString("dict", null) != boundDict) {
            if (WidgetStore.settings(this).lightTheme != light) recreate() else bind()
        }
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
        boundDict = getSharedPreferences(WidgetStore.PREFS, MODE_PRIVATE).getString("dict", null)
        content.removeAllViews()
        val s = WidgetStore.settings(this)
        val p = WidgetStore.payload(this, s.celsius)
        val now = System.currentTimeMillis()
        val tz = TimeZone.getDefault()
        val ink = color(M.attr.colorOnSurface)

        // ---- 1.1.4: the widget itself, exactly as on the home screen (same style, city, clock, date + alarm,
        // timeline, clouds, rain, events and temperatures), then the details below.
        // 1.1.6: touch / drag on its chart shows that hour below (the separate Forecast chart is gone; its
        // range chips too: the mirror shows the widget's own range, Settings > forecast hours).
        widgetMirror(p)?.let { content.addView(it) }
        val dcard = card()
        details = column(16)
        dcard.addView(details); content.addView(dcard)
        if (p == null) dcard.visibility = View.GONE   // nothing to inspect yet
        showHour(p, selectedSlot)

        // ---- now + where the data is for
        val head = card(filled = true)
        val hc = column(16)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 1.1.6: as on the watch, expired data (over 24 h or run out) shows "--" and no now values.
        val expired = p?.isExpired(now, tz) == true
        val cond = when {
            p == null -> getString(R.string.detail_no_data)
            expired -> getString(R.string.detail_expired)
            else -> DetailData.conditionName(p.displayCode(now, tz))
        }
        left.addView(text(cond, M.attr.textAppearanceTitleMedium))
        // High / low of the window the widget shows (its labels), not the payload's 24 h values.
        val hl = p?.let { WidgetRenderer.windowHiLo(it, s, now, tz) }
        if (p != null) left.addView(text(getString(R.string.detail_high_low, hl?.let { DetailData.fmtTemp(it.first.toDouble(), true) } ?: "--",
            hl?.let { DetailData.fmtTemp(it.second.toDouble(), true) } ?: "--"),
            M.attr.textAppearanceBodyMedium, M.attr.colorOnSurfaceVariant))
        top.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        WidgetRenderer.conditionIcon(p, now, tz, ink, px(36))?.let { b ->
            top.addView(ImageView(this).apply { setImageBitmap(b); contentDescription = cond },
                LinearLayout.LayoutParams(px(36), px(36)).apply { marginEnd = px(10) })
        }
        top.addView(text(p?.displayTemp(now, tz)?.let { DetailData.fmtTemp(it, true) } ?: if (expired) "--" else "—", M.attr.textAppearanceHeadlineLarge))
        hc.addView(top)
        // Which coordinates the forecast is for, and how they were found (compare with the watch's).
        WidgetStore.placeUsed(this)?.let { pl ->
            hc.addView(text(getString(R.string.loc_used, "%.4f, %.4f".format(Locale.US, pl.lat, pl.lon), placeSourceLabel(pl)),
                M.attr.textAppearanceBodySmall, M.attr.colorOnSurfaceVariant).apply { setPadding(0, px(8), 0, 0) })
        }
        // No phone location: say so instead of silently showing the default place, with the fix right there.
        when (LocationPermission.state(this)) {
            LocationPermission.State.DENIED -> {
                val savedInUse = WidgetStore.placeSource(this) == LocationHelper.SOURCE_SAVED
                hc.addView(text(getString(if (savedInUse) R.string.loc_denied_saved else R.string.loc_unavailable), M.attr.textAppearanceBodyMedium, androidx.appcompat.R.attr.colorError).apply { setPadding(0, px(8), 0, 0) })
                hc.addView(MaterialButton(this, null, M.attr.materialButtonTonalStyle).apply {
                    setText(R.string.location_allow)
                    setOnClickListener { LocationPermission.ask(this@DetailActivity) }
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = px(6) })
            }
            LocationPermission.State.APPROXIMATE -> {
                hc.addView(text(getString(R.string.loc_approx_note), M.attr.textAppearanceBodySmall, M.attr.colorOnSurfaceVariant).apply { setPadding(0, px(6), 0, 0) })
                hc.addView(MaterialButton(this, null, M.attr.materialButtonTonalStyle).apply {
                    setText(R.string.location_precise)
                    setOnClickListener { LocationPermission.askPrecise(this@DetailActivity) }
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = px(6) })
            }
            LocationPermission.State.NO_FIX ->
                hc.addView(text(getString(R.string.loc_no_fix), M.attr.textAppearanceBodyMedium, androidx.appcompat.R.attr.colorError).apply { setPadding(0, px(8), 0, 0) })
            LocationPermission.State.OK -> {}
        }
        head.addView(hc); content.addView(head)

        // ---- next days
        if (p != null) {
            val days = DetailData.days(p, now, tz, eventSlots = DetailData.watchSlots(p, s, now, tz))
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
        // 1.1.4: the model the data really came from (a failed keyed provider retries Open-Meteo best_match).
        val used = WidgetStore.used(this)
        val model = used?.optString("model")?.ifEmpty { null } ?: dict?.optString("SETTING_OM_MODEL")?.ifEmpty { null } ?: "best_match"
        val updated = p?.let { updatedLabel(it.fetchTime * 1000, now) } ?: "—"
        val src = if (provider.startsWith("openmeteo") || provider == "om") getString(R.string.detail_source_model, updated, providerName(provider), model)
            else getString(R.string.detail_source, updated, providerName(provider))
        // Refresh / Settings live in the toolbar only.
        content.addView(text(src, M.attr.textAppearanceBodySmall, M.attr.colorOnSurfaceVariant).apply { setPadding(px(4), px(16), px(4), px(8)) })
        used?.optString("fallback")?.ifEmpty { null }?.let { fb ->
            val asked = providerName(used.optString("requested"))
            content.addView(text(getString(R.string.detail_fallback, asked, fb), M.attr.textAppearanceBodySmall, androidx.appcompat.R.attr.colorError)
                .apply { setPadding(px(4), 0, px(4), px(8)) })
        }
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

    /** The home-screen widget rendered into the app (WidgetUpdater.build applied here), at the placed widget's shape. */
    private fun widgetMirror(p: WeatherPayload?): View? = runCatching {
        val wDp = (resources.displayMetrics.widthPixels / dp).roundToInt() - 32
        val size = WidgetUpdater.mirrorSize(this, wDp)
        // TalkBack reads the preview once (the widget's own descriptions are in the details below).
        val frame = MirrorFrame(this).apply {
            id = R.id.widget_mirror
            contentDescription = getString(R.string.mirror_description) + ". " +
                WidgetUpdater.chartDescription(this@DetailActivity, p, WidgetStore.settings(this@DetailActivity), System.currentTimeMillis()) +
                (if (p != null) ". " + getString(R.string.mirror_scrub_hint) else "")
            lineColor = color(androidx.appcompat.R.attr.colorPrimary)
            onSelect = { slot -> selectedSlot = slot; showHour(p, slot) }
            describeSlot = { slot -> p?.let { hourSummary(it, slot) } ?: "" }
        }
        frame.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, px(size.hDp)).apply { topMargin = px(4) }
        // 1.1.6: rendered off the UI thread (the frame keeps its size meanwhile); a newer bind() wins.
        val gen = ++mirrorGen
        val app = applicationContext
        mirrorExecutor.execute {
            var cols: MirrorColumns? = null
            val rv = runCatching { WidgetUpdater.build(app, size, columnsOut = { cols = it }) }.onFailure(::logMirror).getOrNull() ?: return@execute
            runOnUiThread {
                if (gen != mirrorGen || isDestroyed) return@runOnUiThread
                // Inflate with the application context, as a launcher does. The activity's AppCompat inflater swaps
                // ImageView for AppCompatImageView, whose setImageBitmap isn't remotable: on a device the apply then
                // threw ActionException whenever the widget sends a plain bitmap (theme Light / Dark, a colour
                // background, Android 11 and older) and the app opened without its widget.
                runCatching {
                    val v = rv.apply(app, frame)
                    v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    frame.removeAllViews(); frame.addView(v)
                    frame.columns = cols
                    val c = cols
                    if (c != null && p != null) {
                        frame.nowColumn = WidgetStore.settings(this).pastHours.coerceIn(0, c.total - 1)
                        // Keep an inspected hour that is still on screen (silently: the details already show it).
                        val sel = selectedSlot?.let { it - c.startSlot }?.takeIf { it in 0 until c.total }
                        frame.select(sel, notify = false)
                        if (sel == null && selectedSlot != null) { selectedSlot = null; showHour(p, null) }
                    }
                }.onFailure(::logMirror)
            }
        }
        frame
    }.onFailure(::logMirror).getOrNull()

    private var mirrorGen = 0

    private fun logMirror(t: Throwable) {
        android.util.Log.w("CarbonWidget", "widget mirror failed: ${t.javaClass.simpleName}: ${t.message?.take(200)}", if (BuildConfig.DEBUG) t else null)
    }

    private fun placeSourceLabel(pl: LocationHelper.Place): String = when (pl.source) {
        LocationHelper.SOURCE_FIXED -> getString(R.string.loc_src_fixed)
        LocationHelper.VILLARS.source -> getString(R.string.loc_src_fallback)
        LocationHelper.SOURCE_SAVED -> pl.fixTime?.let { getString(R.string.loc_src_saved_age, LocationHelper.describeAge(maxOf(0L, System.currentTimeMillis() - it))) }
            ?: getString(R.string.loc_src_saved)
        else -> {
            val acc = pl.accuracyM?.let { LocationHelper.describeAccuracy(it) }
            if (pl.precise) acc?.let { getString(R.string.loc_src_precise, it) } ?: getString(R.string.loc_src_precise_noacc)
            else acc?.let { getString(R.string.loc_src_approx, it) } ?: getString(R.string.loc_src_approx_noacc)
        }
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
        /** Where the widget mirror is rendered; tests run it inline. */
        @androidx.annotation.VisibleForTesting
        internal var mirrorExecutor: java.util.concurrent.Executor = java.util.concurrent.Executors.newSingleThreadExecutor()

        val PROVIDER_LABELS = mapOf("openmeteo" to "Open-Meteo", "om" to "Open-Meteo", "metno" to "MET Norway (yr.no)", "yr" to "MET Norway (yr.no)",
            "geosphere" to "GeoSphere Austria", "meteoblue" to "Meteoblue", "tomorrow" to "Tomorrow.io",
            "owm" to "OpenWeatherMap", "openweathermap" to "OpenWeatherMap", "weatherapi" to "WeatherAPI.com")
    }

    /** One line for TalkBack while stepping through the mirror's hours. */
    private fun hourSummary(p: WeatherPayload, slot: Int): String {
        val h = DetailData.hour(p, slot)
        val time = SimpleDateFormat(if (DateFormat.is24HourFormat(this)) "EEEE HH:mm" else "EEEE h a", Locale.getDefault()).format(Date(h.timeMillis))
        return "$time, ${DetailData.fmtTemp(h.temp, true)}, ${DetailData.conditionName(h.code)}"
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
