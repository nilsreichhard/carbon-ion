package com.nilsreichhard.carbonion.widget

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
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
        return Result.success()
    }

    companion object {
        private const val NAME = "carbon-redraw"
        const val MINUTES = 30L

        fun schedule(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RedrawWorker>(MINUTES, TimeUnit.MINUTES).build())
        }

        fun cancel(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(NAME)
    }
}
