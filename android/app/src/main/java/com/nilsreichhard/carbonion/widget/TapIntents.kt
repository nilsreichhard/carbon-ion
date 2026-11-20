package com.nilsreichhard.carbonion.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

/**
 * What the widget's taps open:
 *  - the clock and the alarm: the phone's alarm list (AlarmClock.ACTION_SHOW_ALARMS);
 *    if no app handles it, the alarm's own show intent (alarm only), else the clock app;
 *  - anywhere else: the expanded in-app screen ([DetailActivity]).
 */
object TapIntents {
    private const val REQ_CLOCK = 2
    private const val REQ_ALARM = 3
    private const val REQ_DETAIL = 100
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /** Clock apps tried, in order, when nothing answers ACTION_SHOW_ALARMS. */
    val CLOCK_PACKAGES = listOf("com.google.android.deskclock", "com.android.deskclock",
        "com.sec.android.app.clockpackage", "com.oneplus.deskclock", "com.miui.clock")

    class Taps(val clock: PendingIntent?, val alarm: PendingIntent?, val other: PendingIntent)

    fun showAlarms(ctx: Context): Intent? =
        Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .takeIf { it.resolveActivity(ctx.packageManager) != null }

    fun clockApp(ctx: Context): Intent? = CLOCK_PACKAGES.firstNotNullOfOrNull { ctx.packageManager.getLaunchIntentForPackage(it) }
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun detail(ctx: Context, widgetId: Int): Intent = Intent(ctx, DetailActivity::class.java)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun taps(ctx: Context, widgetId: Int, alarm: NextAlarm.Info?, hasAlarm: Boolean = alarm != null): Taps {
        val show = showAlarms(ctx)
        val app = clockApp(ctx)
        fun pi(i: Intent, req: Int) = PendingIntent.getActivity(ctx, req, i, FLAGS)
        val clock = when {
            show != null -> pi(show, REQ_CLOCK)
            app != null -> pi(app, REQ_CLOCK)
            else -> null
        }
        val alarmPi = when {
            !hasAlarm -> null
            show != null -> pi(show, REQ_ALARM)
            alarm?.showIntent != null -> alarm.showIntent
            app != null -> pi(app, REQ_ALARM)
            else -> null
        }
        return Taps(clock, alarmPi, pi(detail(ctx, widgetId), REQ_DETAIL + widgetId.coerceAtLeast(0)))
    }
}
