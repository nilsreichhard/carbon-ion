package com.nilsreichhard.carbonion.widget

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** The home-screen widget. Taps: clock / alarm = the phone's alarms, elsewhere = [DetailActivity] (TapIntents). */
class CarbonWidgetProvider : AppWidgetProvider() {

    companion object {
        /** Redraw after midnight (the date drawn with bundled text fonts, the daylight markers). */
        const val ACTION_MIDNIGHT = "com.nilsreichhard.carbonion.widget.MIDNIGHT"
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { WidgetUpdater.update(ctx, mgr, it) } // last good data right away
        WidgetUpdater.scheduleMidnight(ctx)
        WeatherWorker.schedule(ctx)
        WeatherWorker.runNow(ctx)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        WidgetUpdater.update(ctx, mgr, id) // re-render at the new aspect
    }

    override fun onEnabled(ctx: Context) {
        WeatherWorker.schedule(ctx)
    }

    override fun onDisabled(ctx: Context) {
        // Last widget removed: stop fetching, redrawing and the midnight alarm.
        WeatherWorker.cancel(ctx)
        WidgetUpdater.cancelMidnight(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action) {
            AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED, ACTION_MIDNIGHT,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_LOCALE_CHANGED -> WidgetUpdater.updateAll(ctx)
            // After a reboot or an app update: redraw from the last good data and re-arm the midnight redraw.
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> { WidgetUpdater.updateAll(ctx); WeatherWorker.schedule(ctx) }
        }
    }
}
