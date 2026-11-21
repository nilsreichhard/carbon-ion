package com.nilsreichhard.carbonion.widget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Where to fetch for, in the watch's order (src/pkjs/index.js): Clay
 * "Fixed coordinates" first, else the phone's location, else the last known
 * one, else Villars-sur-Ollon.
 */
object LocationHelper {
    /** [accuracyM]: the fix's accuracy radius; [precise]: from a precise (fine) fix, not Android's approximate one. */
    data class Place(val lat: Double, val lon: Double, val isStatic: Boolean, val source: String,
                     val accuracyM: Float? = null, val precise: Boolean = false, val fixTime: Long? = null)

    const val SOURCE_FIXED = "fixed"
    const val SOURCE_PHONE = "phone"
    const val SOURCE_SAVED = "phone (saved)"

    val VILLARS = Place(46.30, 7.05, true, "fallback")

    fun hasPermission(ctx: Context) = hasPrecise(ctx) ||
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * 1.1.4: precise location granted. With approximate only, Android moves the fix onto a ~2 km grid
     * (LocationFudger), which on a mountainside is hundreds of metres of altitude away (Villars: 1255 m
     * vs 1383 m one cell up, about 2 °C colder and gustier) while the watch uses the Pebble app's precise fix.
     */
    fun hasPrecise(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * 1.1.4: a phone fix older than this is not used as if it were current. The watch asks the Pebble
     * app for a fix on every fetch (index.js, maximumAge 5 min); Android's background fetch can't always
     * get one (no background-location permission), so an older fix is used only as a marked "saved" place.
     */
    const val MAX_FIX_AGE_MS = 30 * 60_000L

    fun isRecent(ageMs: Long) = ageMs in 0..MAX_FIX_AGE_MS

    /** Age of a platform fix in ms (monotonic clock, so a changed wall clock doesn't matter). */
    fun ageMs(loc: Location): Long = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L

    /** "5 min", "3 h", "2 d". */
    fun describeAge(ms: Long): String = when {
        ms < 3_600_000L -> "${(ms / 60_000L).coerceAtLeast(1)} min"
        ms < 48 * 3_600_000L -> "${ms / 3_600_000L} h"
        else -> "${ms / 86_400_000L} d"
    }

    /** "12 m", "850 m", "1.6 km". */
    fun describeAccuracy(m: Float): String = when {
        m < 1000f -> "${(Math.round(m / 5f) * 5).coerceAtLeast(5)} m"
        else -> String.format(java.util.Locale.US, "%.1f km", m / 1000f)
    }

    /** Provider for a fresh fix: fused (or GPS) when precise is allowed, else the network provider. */
    // FUSED_PROVIDER is the inlined string "fused", used only when sdk >= S.
    @android.annotation.SuppressLint("InlinedApi")
    fun freshProvider(precise: Boolean, sdk: Int, available: Collection<String>): String = when {
        precise && sdk >= Build.VERSION_CODES.S && LocationManager.FUSED_PROVIDER in available -> LocationManager.FUSED_PROVIDER
        precise && LocationManager.GPS_PROVIDER in available -> LocationManager.GPS_PROVIDER
        else -> LocationManager.NETWORK_PROVIDER
    }

    /** Last known coarse location from the platform (no Play services). */
    fun lastKnown(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return try {
            listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
                .filter { it in runCatching { lm.allProviders }.getOrDefault(emptyList<String>()) }
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
            // Written on the main thread, read here: atomic. On timeout the request is cancelled.
            val result = java.util.concurrent.atomic.AtomicReference<Location?>(null)
            val latch = CountDownLatch(1)
            val cancel = CancellationSignal()
            try {
                val provider = freshProvider(hasPrecise(ctx), Build.VERSION.SDK_INT, lm.allProviders)
                lm.getCurrentLocation(provider, cancel, ctx.mainExecutor) { result.set(it); latch.countDown() }
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) cancel.cancel()
            } catch (e: SecurityException) { /* permission revoked meanwhile */
            } catch (e: Exception) { /* provider off */ }
            result.get()?.let { return it }
        }
        return lastKnown(ctx)
    }

    /**
     * A fresh coarse fix while the app is in the foreground (no background-location permission:
     * the periodic fetch then reuses this saved fix). Refetches when the phone moved more than
     * [MOVED_M] or the last fetch had to use the default place.
     */
    const val MOVED_M = 2_000f

    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    fun refreshInForeground(ctx: Context, force: Boolean = false) {
        if (!hasPermission(ctx)) return
        // One at a time (onStart can come again while a fix is pending).
        if (!refreshing.compareAndSet(false, true)) return
        Thread {
          try {
            // Just granted: refresh even without a fresh fix (the fetch falls back to the last known one).
            val loc = current(ctx)?.takeIf { isRecent(ageMs(it)) } ?: run { if (force) WeatherWorker.runNow(ctx); return@Thread }
            val prev = WidgetStore.phoneLocation(ctx)
            val precise = hasPrecise(ctx)
            WidgetStore.savePhoneLocation(ctx, loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else null, precise,
                System.currentTimeMillis() - ageMs(loc))
            // Precise just allowed while the last fetch used an approximate fix: refetch for the real place.
            val used = WidgetStore.placeUsed(ctx)
            val sharper = precise && used?.let { !it.precise && !it.isStatic } == true
            // The last fetch used a saved (old) fix: refetch now that a current one is here.
            val wasSaved = used?.source == SOURCE_SAVED
            val moved = prev == null || FloatArray(1).also { Location.distanceBetween(prev.first, prev.second, loc.latitude, loc.longitude, it) }[0] > MOVED_M
            if (force || moved || sharper || wasSaved || WidgetStore.placeSource(ctx) == VILLARS.source) WeatherWorker.runNow(ctx)
          } finally { refreshing.set(false) }
        }.start()
    }

    /** Resolve the place to fetch for. [staticJson] is carbonLocation()'s result. */
    fun resolve(ctx: Context, staticJson: String?, fresh: Boolean, timeoutMs: Long = if (fresh) 8000 else 5000): Place {
        if (!staticJson.isNullOrEmpty() && staticJson != "null") {
            val o = org.json.JSONObject(staticJson)
            return Place(o.getDouble("lat"), o.getDouble("lon"), true, SOURCE_FIXED, precise = true)
        }
        // 1.1.4: always ask for a fresh fix (the watch does on every fetch); the background worker gets a
        // shorter wait. A last-known fix counts only while recent; otherwise the newest saved fix is used,
        // marked "saved" with its age on the widget and in the app instead of passing for the current place.
        val loc = current(ctx, timeoutMs)
        val precise = hasPrecise(ctx)
        if (loc != null && isRecent(ageMs(loc))) {
            val acc = if (loc.hasAccuracy()) loc.accuracy else null
            val t = System.currentTimeMillis() - ageMs(loc)
            WidgetStore.savePhoneLocation(ctx, loc.latitude, loc.longitude, acc, precise, t)
            return Place(loc.latitude, loc.longitude, false, SOURCE_PHONE, acc, precise, t)
        }
        val saved = WidgetStore.phoneFix(ctx)
        val oldT = loc?.let { System.currentTimeMillis() - ageMs(it) }
        if (loc != null && oldT != null && (saved?.fixTime ?: 0L) < oldT)
            return Place(loc.latitude, loc.longitude, false, SOURCE_SAVED, if (loc.hasAccuracy()) loc.accuracy else null, precise, oldT)
        saved?.let { return it.copy(source = SOURCE_SAVED) }
        return VILLARS
    }
}
