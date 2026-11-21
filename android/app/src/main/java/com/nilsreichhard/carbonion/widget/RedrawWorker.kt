package com.nilsreichhard.carbonion.widget

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf
import java.util.Calendar
import java.util.TimeZone
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Redraws the widgets from the last good data without needing a network, so the now line,
 * the hour window, the battery marker and the stale marker keep moving while offline
 * ([WeatherWorker] only runs with a connection).
 */
class RedrawWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        WidgetUpdater.updateAll(applicationContext)
        // 1.1.6: the hourly one re-queues itself for the next top of the hour (no exact alarms).
        if (inputData.getBoolean(KEY_HOURLY, false)) scheduleTopOfHour(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    companion object {
        private const val NAME = "carbon-redraw"
        private const val HOURLY = "carbon-redraw-hour"
        private const val KEY_HOURLY = "hourly"
        const val MINUTES = 30L
        /** Just after the hour, so the watch-style window has moved on when it runs. */
        const val AFTER_HOUR_MS = 20_000L

        fun schedule(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RedrawWorker>(MINUTES, TimeUnit.MINUTES).build())
            scheduleTopOfHour(ctx, ExistingWorkPolicy.KEEP)
        }

        /** Delay to just after the next top of the hour (local time). */
        fun delayToNextHour(nowMillis: Long, tz: TimeZone = TimeZone.getDefault()): Long {
            val c = Calendar.getInstance(tz).apply { timeInMillis = nowMillis; set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.HOUR_OF_DAY, 1) }
            return c.timeInMillis - nowMillis + AFTER_HOUR_MS
        }

        internal fun scheduleTopOfHour(ctx: Context, policy: ExistingWorkPolicy) {
            val req = OneTimeWorkRequestBuilder<RedrawWorker>()
                .setInitialDelay(delayToNextHour(System.currentTimeMillis()), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_HOURLY to true))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(HOURLY, policy, req)
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(NAME)
            WorkManager.getInstance(ctx).cancelUniqueWork(HOURLY)
        }
    }
}
