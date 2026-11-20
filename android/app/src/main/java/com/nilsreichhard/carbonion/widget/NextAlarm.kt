package com.nilsreichhard.carbonion.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ApplicationInfo
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The phone's next alarm (AlarmManager.getNextAlarmClock): "05:55" when it
 * rings within 24 h, "Sat 07:00" when it is further away.
 */
object NextAlarm {
    const val DAY_MS = 24 * 3_600_000L
    /** Debug builds only: overrides the alarm for screenshots (DebugSamplesReceiver). */
    const val DEBUG_PREF = "debug_alarm_at"
    const val DEBUG_NONE = -1L

    class Info(val triggerMillis: Long, val showIntent: PendingIntent?)

    fun info(ctx: Context): Info? {
        if (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            val o = ctx.getSharedPreferences(WidgetStore.PREFS, Context.MODE_PRIVATE).getLong(DEBUG_PREF, 0L)
            if (o == DEBUG_NONE) return null
            if (o > 0L) return Info(o, null)
        }
        val c = ctx.getSystemService(AlarmManager::class.java)?.nextAlarmClock ?: return null
        return Info(c.triggerTime, c.showIntent)
    }

    fun text(ctx: Context, leadingZero: Int, nowMillis: Long = System.currentTimeMillis()): String? =
        info(ctx)?.let { format(it.triggerMillis, nowMillis, DateFormat.is24HourFormat(ctx), leadingZero) }

    /** Time only within 24 h, else weekday + time; 12/24 h and the leading-zero setting as on the clock. */
    fun format(triggerMillis: Long, nowMillis: Long, is24h: Boolean, leadingZero: Int, locale: Locale = Locale.getDefault()): String {
        val time = WidgetSettings.clockPattern(is24h, leadingZero) + if (is24h) "" else " a"
        val pattern = if (triggerMillis - nowMillis > DAY_MS) "EEE $time" else time
        return SimpleDateFormat(pattern, locale).format(Date(triggerMillis))
    }
}
