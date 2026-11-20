package com.nilsreichhard.carbonion.widget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Where to fetch for, in the watch's order (src/pkjs/index.js): Clay
 * "Fixed coordinates" first, else the phone's location, else the last known
 * one, else Villars-sur-Ollon.
 */
object LocationHelper {
    data class Place(val lat: Double, val lon: Double, val isStatic: Boolean, val source: String)

    val VILLARS = Place(46.30, 7.05, true, "fallback")

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Last known coarse location from the platform (no Play services). */
    fun lastKnown(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return try {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
                .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) || it == LocationManager.PASSIVE_PROVIDER }
                .mapNotNull { lm.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) { null }
    }

    /** A fresh fix (Android 11+), waiting at most [timeoutMs]; else last known. */
    fun current(ctx: Context, timeoutMs: Long = 8000): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            var result: Location? = null
            val latch = CountDownLatch(1)
            try {
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, CancellationSignal(), ctx.mainExecutor) { result = it; latch.countDown() }
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: SecurityException) { /* permission revoked meanwhile */
            } catch (e: Exception) { /* provider off */ }
            if (result != null) return result
        }
        return lastKnown(ctx)
    }

    /**
     * A fresh coarse fix while the app is in the foreground (no background-location permission:
     * the periodic fetch then reuses this saved fix). Refetches when the phone moved more than
     * [MOVED_M] or the last fetch had to use the default place.
     */
    const val MOVED_M = 2_000f

    fun refreshInForeground(ctx: Context) {
        if (!hasPermission(ctx)) return
        Thread {
            val loc = current(ctx) ?: return@Thread
            val prev = WidgetStore.phoneLocation(ctx)
            WidgetStore.savePhoneLocation(ctx, loc.latitude, loc.longitude)
            val moved = prev == null || FloatArray(1).also { Location.distanceBetween(prev.first, prev.second, loc.latitude, loc.longitude, it) }[0] > MOVED_M
            if (moved || WidgetStore.placeSource(ctx) == VILLARS.source) WeatherWorker.runNow(ctx)
        }.start()
    }

    /** Resolve the place to fetch for. [staticJson] is carbonLocation()'s result. */
    fun resolve(ctx: Context, staticJson: String?, fresh: Boolean): Place {
        if (!staticJson.isNullOrEmpty() && staticJson != "null") {
            val o = org.json.JSONObject(staticJson)
            return Place(o.getDouble("lat"), o.getDouble("lon"), true, "fixed")
        }
        val loc = if (fresh) current(ctx) else lastKnown(ctx)
        if (loc != null) {
            WidgetStore.savePhoneLocation(ctx, loc.latitude, loc.longitude)
            return Place(loc.latitude, loc.longitude, false, "phone")
        }
        WidgetStore.phoneLocation(ctx)?.let { return Place(it.first, it.second, false, "phone (saved)") }
        return VILLARS
    }
}
