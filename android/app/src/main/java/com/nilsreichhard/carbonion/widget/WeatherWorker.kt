package com.nilsreichhard.carbonion.widget

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Fetches weather through the bundled JS, stores it, redraws all widgets. */
class WeatherWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result = try {
        // 1.1.0: also a periodic resync of the phone alarm to the watch face (no network needed).
        runCatching { PhoneAlarmSync.sendBlocking(applicationContext) }
        fetchNow(applicationContext, freshLocation = false)
        WidgetUpdater.updateAll(applicationContext)
        Result.success()
    } catch (e: Exception) {
        // The message can carry provider URLs (API keys, coordinates): full detail in debug builds only.
        if (BuildConfig.DEBUG) Log.w("CarbonWidget", "weather fetch failed", e) else Log.w("CarbonWidget", "weather fetch failed: ${e.javaClass.simpleName}")
        WidgetUpdater.updateAll(applicationContext) // keep showing the last good data
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }

    companion object {
        private const val PERIODIC = "carbon-weather-periodic"
        private const val NOW = "carbon-weather-now"
        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        /** Failed fetches retry after 1, 2, 4 … minutes (WorkManager doubles it). */
        private const val BACKOFF_MINUTES = 1L

        /** One fetch: location → JS (watch pipeline) → store. Blocking. */
        fun fetchNow(ctx: Context, freshLocation: Boolean) {
            JsRuntime(ctx).use { js ->
                val place = LocationHelper.resolve(ctx, js.call("carbonLocation"), freshLocation)
                val host = JSONObject().put("lat", place.lat).put("lon", place.lon).put("isStatic", place.isStatic)
                val out = JSONObject(js.call("carbonFetchWeather", host.toString()))
                if (out.has("error")) throw IllegalStateException("weather: " + out.getString("error"))
                WidgetStore.saveWeather(ctx, out.getJSONObject("payload").toString(), out.optString("city", ""),
                    out.getJSONObject("settings").toString())
                WidgetStore.savePlaceSource(ctx, place.source)
            }
        }

        /** Refresh as often as the watch's "Refresh" setting (min 15, WorkManager's floor). */
        fun schedule(ctx: Context, replace: Boolean = false) {
            val minutes = runCatching {
                org.json.JSONObject(JsRuntime.store(ctx).getString("clay-settings", "{}") ?: "{}")
                    .optString("SETTING_FETCH_INTERVAL", "15").toLong()
            }.getOrDefault(15L).coerceIn(15L, 60L)
            val req = PeriodicWorkRequestBuilder<WeatherWorker>(minutes, TimeUnit.MINUTES).setConstraints(net)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC,
                if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP, req)
            RedrawWorker.schedule(ctx)
        }

        fun runNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<WeatherWorker>().setConstraints(net)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(ctx).cancelUniqueWork(NOW)
            RedrawWorker.cancel(ctx)
        }
    }
}
