package com.nilsreichhard.carbonion.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.format.DateFormat
import android.util.Log
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.util.TimeZone
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds the RemoteViews per widget and size. The layout breakpoint
 * ([WidgetLayout]) comes from the height, the chart bitmap is rendered at
 * the widget's own aspect and density, and the text sizes come from the
 * row height (capped so the clock fits narrow widgets). On Android 12+ the
 * launcher gets one RemoteViews per reported size (portrait / landscape).
 */
object WidgetUpdater {
    private const val TAG = "CarbonWidget"

    data class Size(val wDp: Int, val hDp: Int)

    fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, CarbonWidgetProvider::class.java))
        ids.forEach { update(ctx, mgr, it) }
        if (ids.isNotEmpty()) scheduleMidnight(ctx)
    }

    private fun midnightIntent(ctx: Context) = PendingIntent.getBroadcast(ctx, 1,
        Intent(ctx, CarbonWidgetProvider::class.java).setAction(CarbonWidgetProvider.ACTION_MIDNIGHT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun cancelMidnight(ctx: Context) { ctx.getSystemService(AlarmManager::class.java)?.cancel(midnightIntent(ctx)) }

    /** Inexact, Doze-tolerant redraw a few seconds after the next local midnight. */
    fun scheduleMidnight(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val next = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 5)
        }.timeInMillis
        am.setAndAllowWhileIdle(AlarmManager.RTC, next, midnightIntent(ctx))
    }

    fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
        mgr.updateAppWidget(id, forOptions(ctx, mgr, id))
    }

    /**
     * Size-mapped RemoteViews on 12+ (max two sizes, for bitmap memory), else portrait size.
     * Follow-system theme carries a light and a dark bitmap per size ([dualTheme]), so it keeps the
     * sizes whose bitmaps fit the launcher's budget ([fitSizes]).
     */
    fun forOptions(ctx: Context, mgr: AppWidgetManager, id: Int): RemoteViews {
        val o = mgr.getAppWidgetOptions(id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val perSize = if (dualTheme(ctx, WidgetStore.settings(ctx))) 2 else 1
            val sizes = fitSizes(sizesFrom(o).take(2), perSize, ctx.resources.displayMetrics)
            if (BuildConfig.DEBUG) Log.d(TAG, "widget $id sizes $sizes")
            if (sizes.size > 1) return RemoteViews(sizes.associateWith { build(ctx, Size(it.width.roundToInt(), it.height.roundToInt()), id) })
            if (sizes.size == 1) return build(ctx, Size(sizes[0].width.roundToInt(), sizes[0].height.roundToInt()), id)
        }
        return build(ctx, sizeOf(mgr, id), id)
    }

    /** The launcher's reported sizes (Android 12+). BundleCompat: the typed getter is API 33, not 31. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    fun sizesFrom(o: android.os.Bundle): List<SizeF> =
        androidx.core.os.BundleCompat.getParcelableArrayList(o, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)?.distinct().orEmpty()

    /** Bitmap px per widget image: the Modern renderer caps at ~1.2 Mpx (ModernRenderer.Spec.forWidget). */
    private const val MAX_BITMAP_PX = 1_200_000f

    /**
     * The first sizes whose chart bitmaps ([perSize] each: two for Follow system) stay within 80 % of the
     * launcher's RemoteViews bitmap limit (1.5 × screen × 4 bytes); always at least one.
     */
    fun fitSizes(sizes: List<SizeF>, perSize: Int, dm: android.util.DisplayMetrics): List<SizeF> {
        val budget = dm.widthPixels.toFloat() * dm.heightPixels * 4f * 1.5f * 0.8f
        var used = 0f
        val out = ArrayList<SizeF>()
        for (sz in sizes) {
            val bytes = min(sz.width * sz.height * dm.density * dm.density, MAX_BITMAP_PX) * 4f * perSize
            if (out.isNotEmpty() && used + bytes > budget) break
            used += bytes; out.add(sz)
        }
        return out
    }

    /**
     * Follow system on Android 12+: the RemoteViews carry both themes (setIcon / setColorInt with a night
     * variant), so the launcher switches them the moment dark mode changes, even with the app not running.
     */
    fun dualTheme(ctx: Context, s: WidgetSettings): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && s.themeMode == WidgetSettings.THEME_SYSTEM

    /** strftime → ICU; the short-weekday option follows the locale's order ("Mon, Jan 15", "Mon 15 Jan", "Mo., 15. Jan."). */
    fun localDatePattern(strftime: String, locale: java.util.Locale = java.util.Locale.getDefault()): String =
        if (strftime == SHORT_WEEKDAY_FORMAT) DateFormat.getBestDateTimePattern(locale, "EEEMMMd")
        else WidgetSettings.datePattern(strftime)

    /** Android-only date format option (widget-entry.js ANDROID_OPTIONS). */
    const val SHORT_WEEKDAY_FORMAT = "%a, %b %d"

    /** Portrait size: min width × max height (AppWidgetManager options). */
    fun sizeOf(mgr: AppWidgetManager, id: Int): Size {
        val o = mgr.getAppWidgetOptions(id)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        return if (w > 0 && h > 0) Size(w, h) else Size(360, 344)
    }

    /**
     * Clock size in rows: the watch's 62, or less when the widget is narrow.
     */
    fun clockRows(sample: String, widthRows: Int,
                  typeface: Typeface = WidgetRenderer.CLOCK_TYPEFACE, sizeFactor: Float = 1f): Float {
        val p = Paint().apply { this.typeface = typeface; textSize = 100f }
        val em = p.measureText(sample) / 100f
        // Wide fonts (Light, Mono, Serif) get the same digit height cap as the watch's condensed digits
        // via the em width; the size option scales within 0.7 of the width.
        return min(CarbonStyle.CLOCK_TEXT_ROWS * sizeFactor, widthRows * (if (sizeFactor > 1f) 0.7f else 0.62f) / em)
    }

    /**
     * Widest rendering of the date [pattern] today or tomorrow: the widget is rebuilt at
     * midnight, but that alarm can be late, so the row must hold both dates.
     */
    fun widestDate(pattern: String, now: Long, tf: Typeface, px: Float): String {
        val p = AlarmBadge.paint(tf, px)
        return listOf(now, now + 86_400_000L).map { DateFormat.format(pattern, it).toString() }.maxBy { p.measureText(it) }
    }

    fun ensureIconFont(ctx: Context) {
        if (WidgetRenderer.iconTypeface == null)
            WidgetRenderer.iconTypeface = runCatching { Typeface.createFromAsset(ctx.assets, "IcoMoon-Regular.ttf") }.getOrNull()
    }

    fun build(ctx: Context, size: Size, widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID,
              alarmOverride: String? = null, useAlarmOverride: Boolean = false,
              nowMillis: Long = System.currentTimeMillis(), batteryOverride: WidgetRenderer.Battery? = null,
              useBatteryOverride: Boolean = false): RemoteViews {
        ensureIconFont(ctx)
        val ui = WidgetStore.ui(ctx)
        val modern = ui.style == UiOptions.Style.MODERN
        val bg = WidgetStore.background(ctx)
        val s = WidgetStore.settings(ctx)
        val clockFont = ui.clock()
        val textFont = ui.text()
        val clockTf = clockFont.typeface(ctx, clock = true)
        val textTf = textFont.typeface(ctx, clock = false)
        val layout = WidgetLayout.forHeight(size.hDp)
        val density = ctx.resources.displayMetrics.density
        val is24h = DateFormat.is24HourFormat(ctx)
        val clockPattern = WidgetSettings.clockPattern(is24h, s.leadingZero)
        val sample = clockPattern.replace(Regex("[Hh]+"), "00").replace("mm", "00")
        val rowDp = size.hDp / layout.totalRows.toFloat()
        val widthRows = (size.wDp / rowDp).roundToInt()
        val alarmInfo = if (useAlarmOverride) null else NextAlarm.info(ctx)
        val alarm = if (useAlarmOverride) alarmOverride
            else alarmInfo?.let { NextAlarm.format(it.triggerMillis, nowMillis, is24h, s.leadingZero) }
        val clockRows = clockRows(sample, widthRows, clockTf, ui.clockSize.factor)
        val battery = if (useBatteryOverride) batteryOverride else BatteryTracker.read(ctx)
        val payload = WidgetStore.payload(ctx, s.celsius)

        val v = RemoteViews(ctx.packageName, layout.layoutRes)
        v.setContentDescription(R.id.chart, chartDescription(ctx, payload))
        v.setInt(android.R.id.background, "setBackgroundColor", bg.argb)

        // City row + date (text font) and the live clock (clock font): one small layout per font,
        // since RemoteViews can't change a TextView's typeface.
        // Bundled text fonts are drawn into the bitmap; the RemoteViews text stays as a transparent tap target.
        val bitmapText = textFont.bundled
        val textRv = RemoteViews(ctx.packageName, if (bitmapText) R.layout.text_system else textFont.textLayout)
        val clockRv = RemoteViews(ctx.packageName, clockFont.clockLayout)
        val city = staleCity(ctx, WidgetStore.city(ctx), payload, nowMillis)
        textRv.setTextViewText(R.id.city, city)
        val textRows = min(CarbonStyle.CITY_TEXT_ROWS * (if (modern) 0.92f else 1f), clockRows * 0.4f)
        textRv.setTextViewTextSize(R.id.city, TypedValue.COMPLEX_UNIT_DIP, textRows * rowDp)
        clockRv.setTextViewTextSize(R.id.clock, TypedValue.COMPLEX_UNIT_DIP, clockRows * rowDp)
        clockRv.setCharSequence(R.id.clock, "setFormat24Hour", clockPattern)
        clockRv.setCharSequence(R.id.clock, "setFormat12Hour", clockPattern)
        textRv.setViewVisibility(R.id.city_row, if (city.isEmpty()) View.INVISIBLE else View.VISIBLE)

        // Date row: the date, then the next alarm "· [icon] 07:00" (weekday added beyond 24 h) in the
        // date's font, size and colour, centred as one row under the clock. Too wide: the short date,
        // then slightly smaller text; the alarm time is never truncated, the alarm hides only as a last resort.
        val datePattern = localDatePattern(s.dateFormat)
        val shortPattern = AlarmBadge.shortPattern(datePattern)
        val datePx = textRows * rowDp * density
        val plan = AlarmBadge.plan(widestDate(datePattern, nowMillis, textTf, datePx), widestDate(shortPattern, nowMillis, textTf, datePx),
            alarm, textTf, datePx, size.wDp * density - 2f * CarbonStyle.DATE_EDGE_ROWS * rowDp * density)
        val rowPattern = if (plan.shortDate) shortPattern else datePattern
        val rowTextDp = textRows * rowDp * plan.scale
        textRv.setCharSequence(R.id.date, "setFormat24Hour", rowPattern)
        textRv.setCharSequence(R.id.date, "setFormat12Hour", rowPattern)
        textRv.setTextViewTextSize(R.id.date, TypedValue.COMPLEX_UNIT_DIP, rowTextDp)
        if (plan.showAlarm && alarm != null) {
            textRv.setTextViewText(R.id.alarm_sep, AlarmBadge.SEPARATOR)
            textRv.setTextViewText(R.id.alarm_time, alarm)
            for (tv in intArrayOf(R.id.alarm_sep, R.id.alarm_time)) textRv.setTextViewTextSize(tv, TypedValue.COMPLEX_UNIT_DIP, rowTextDp)
            textRv.setContentDescription(R.id.date_alarm, ctx.getString(R.string.alarm_description_at, alarm))
            textRv.setViewVisibility(R.id.date_alarm, View.VISIBLE)
        } else textRv.setViewVisibility(R.id.date_alarm, View.GONE)

        // Condition icon right of the city: IcoMoon 18 with the watch's 5-row gap.
        val iconPx = (18 * (if (modern) 0.92f else 1f) * rowDp * density).roundToInt().coerceAtLeast(8)
        val gapPx = (5 * rowDp * density).roundToInt()
        val showAlarm = plan.showAlarm && alarm != null

        /** Everything that depends on light/dark: chart (with bitmap text), ink, condition and alarm icons. */
        class Look(val chart: Bitmap, val ink: Int, val cond: Bitmap?, val alarmIcon: Bitmap?)
        fun look(themeLight: Boolean): Look {
            // A solid colour background picks the palette by its luminance (WidgetColors); transparent keeps the theme.
            val sv = s.copy(lightTheme = WidgetColors.effectiveLight(bg, themeLight, modern))
            val accent = accentColor(ctx, ui, sv.lightTheme)
            val ink = if (modern) ModernRenderer.Palette(sv.lightTheme, accent).ink else CarbonStyle.Palette(sv.lightTheme).ink
            val chart = if (modern) {
                val spec = ModernRenderer.Spec.forWidget(size.wDp, size.hDp, density, layout, sample, clockRows, battery,
                    clockTf, textTf, ui.scrim && bg.mode == WidgetStore.BG_TRANSPARENT, accent, cornerRadiusPx(ctx),
                    textShadow = bg.mode == WidgetStore.BG_TRANSPARENT)
                ModernRenderer.render(payload, sv, spec, nowMillis)
            } else {
                val spec = WidgetRenderer.Spec.forWidget(size.wDp, size.hDp, density, layout, sample, clockRows, battery, clockTf)
                WidgetRenderer.render(payload, sv, spec, nowMillis)
            }
            val icon = WidgetRenderer.conditionIcon(payload, nowMillis, TimeZone.getDefault(), ink, iconPx)
            var cond: Bitmap? = null
            if (bitmapText) {
                BitmapText.draw(chart, layout, BitmapText.Item(city, DateFormat.format(rowPattern, nowMillis).toString(),
                    textRows, textTf, ink, icon, 18 * 0.92f, bg.mode == WidgetStore.BG_TRANSPARENT,
                    alarm = alarm.takeIf { plan.showAlarm }, dateScale = plan.scale))
            } else if (icon != null) {
                cond = Bitmap.createBitmap(iconPx + gapPx, iconPx, Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(cond).drawBitmap(icon, gapPx.toFloat(), 0f, null)
            }
            // Same shadow as the date's (text_*.xml).
            val alarmIcon = if (showAlarm) AlarmBadge.iconBitmap(textTf, datePx * plan.scale, ink, 0x40000000) else null
            return Look(chart, ink, cond, alarmIcon)
        }

        val inkViews = listOf(textRv to R.id.city, textRv to R.id.date, clockRv to R.id.clock, textRv to R.id.alarm_sep, textRv to R.id.alarm_time)
        val dayLight = WidgetColors.effectiveLight(bg, true, modern)
        val nightLight = WidgetColors.effectiveLight(bg, false, modern)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dualTheme(ctx, s) && dayLight != nightLight) {
            // Follow system: both looks in one RemoteViews; the launcher picks by its night mode.
            val day = look(true); val night = look(false)
            fun icon(b: Bitmap) = android.graphics.drawable.Icon.createWithBitmap(b)
            v.setIcon(R.id.chart, "setImageIcon", icon(day.chart), icon(night.chart))
            for ((rv, id) in inkViews) rv.setColorInt(id, "setTextColor", day.ink, night.ink)
            if (day.cond != null && night.cond != null) textRv.setIcon(R.id.cond, "setImageIcon", icon(day.cond), icon(night.cond))
            if (day.alarmIcon != null && night.alarmIcon != null) textRv.setIcon(R.id.alarm_icon, "setImageIcon", icon(day.alarmIcon), icon(night.alarmIcon))
            textRv.setViewVisibility(R.id.cond, if (!bitmapText && day.cond != null) View.VISIBLE else View.GONE)
        } else {
            val one = look(if (dualTheme(ctx, s)) dayLight else s.lightTheme)
            v.setImageViewBitmap(R.id.chart, one.chart)
            for ((rv, id) in inkViews) rv.setTextColor(id, one.ink)
            one.cond?.let { textRv.setImageViewBitmap(R.id.cond, it) }
            one.alarmIcon?.let { textRv.setImageViewBitmap(R.id.alarm_icon, it) }
            textRv.setViewVisibility(R.id.cond, if (!bitmapText && one.cond != null) View.VISIBLE else View.GONE)
        }
        if (bitmapText) {
            // Invisible views keep their size, so the city row is still a tap target.
            textRv.setViewVisibility(R.id.city, View.INVISIBLE)
            textRv.setViewVisibility(R.id.date, View.INVISIBLE)
            if (plan.showAlarm) textRv.setViewVisibility(R.id.date_alarm, View.INVISIBLE)
        }

        // Taps: the clock and the alarm open the phone's alarms; anywhere else the expanded app screen.
        // The alarm part of the date row is its own click target; the date itself falls through to the
        // background. Bundled fonts draw the row into the bitmap, where the invisible views don't line up
        // with the drawn text, so there the whole date row opens the alarms.
        val taps = TapIntents.taps(ctx, widgetId, alarmInfo, hasAlarm = plan.showAlarm)
        taps.clock?.let { clockRv.setOnClickPendingIntent(R.id.clock, it) }
        taps.alarm?.let { textRv.setOnClickPendingIntent(if (bitmapText) R.id.date_row else R.id.date_alarm, it) }
        textRv.setOnClickPendingIntent(R.id.city_row, taps.other)
        v.setOnClickPendingIntent(android.R.id.background, taps.other)
        v.removeAllViews(R.id.time_block)
        v.addView(R.id.time_block, textRv)
        v.addView(R.id.time_block, clockRv)
        return v
    }

    /** Data older than this is marked on the city row ("· 5 h ago"): fetches failed or were offline. */
    const val STALE_HOURS = 2

    /**
     * The city, plus how old the forecast is once it's [STALE_HOURS] or more, or "no location"
     * when the phone's location wasn't available and the default place was used.
     */
    fun staleCity(ctx: Context, city: String, p: WeatherPayload?, nowMillis: Long,
                  noLocation: Boolean = WidgetStore.placeSource(ctx) == LocationHelper.VILLARS.source): String {
        val marks = ArrayList<String>()
        if (noLocation) marks.add(ctx.getString(R.string.loc_unavailable_short))
        if (p != null && p.fetchTime > 0L) {
            val h = ((nowMillis / 1000 - p.fetchTime) / 3600).toInt()
            if (h >= STALE_HOURS) marks.add(if (h >= 48) ctx.getString(R.string.stale_days, h / 24) else ctx.getString(R.string.stale_hours, h))
        }
        return marks.fold(city) { acc, m -> if (acc.isEmpty()) m else ctx.getString(R.string.stale_city, acc, m) }
    }

    /** TalkBack summary of the chart image: now, high and low. */
    fun chartDescription(ctx: Context, p: WeatherPayload?): String {
        if (p == null) return ctx.getString(R.string.chart_description_empty)
        fun t(v: Double?) = v?.let { "${it.roundToInt()}°" } ?: "—"
        return ctx.getString(R.string.chart_description, t(p.currentTemp), t(p.highTemp), t(p.lowTemp))
    }

    /** Material You accent (Android 12+) when chosen: darker tone on light, lighter on dark. */
    fun accentColor(ctx: Context, ui: UiOptions, light: Boolean): Int? {
        if (!ui.wallpaperAccent || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return ctx.getColor(if (light) android.R.color.system_accent1_600 else android.R.color.system_accent1_200)
    }

    /** The launcher's widget corner radius (Android 12+), else 16 dp. */
    fun cornerRadiusPx(ctx: Context): Float {
        val r = ctx.resources
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            runCatching { r.getDimension(android.R.dimen.system_app_widget_background_radius) }.getOrDefault(16f * r.displayMetrics.density)
        else 16f * r.displayMetrics.density
    }
}
