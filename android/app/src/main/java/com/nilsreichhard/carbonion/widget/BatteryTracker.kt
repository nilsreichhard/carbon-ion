package com.nilsreichhard.carbonion.widget

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.json.JSONArray

/**
 * The phone's battery as the watch's timeline battery markers
 * (daylight_layer.c prv_draw_battery): 20 / 10 / 4 / 0 % shown where the
 * charge is expected to reach them. Same pipeline as the watch, rescaled for
 * a phone: samples on each refresh, measured drain from the first to the
 * last drop (≥ 1 h and ≥ 3 %), else the default of ~24 h per full charge.
 * Hidden while charging or full. The ETA anchors at the last percent change
 * so markers don't slide on a flat percentage.
 */
object BatteryTracker {
    private const val PREFS = "carbon_battery"
    const val DEFAULT_SECS_PER_PCT = 864L   // 24 h full (watch: 17280, ~20 days)
    const val MIN_SECS_PER_PCT = 216L       // 6 h full
    const val MAX_SECS_PER_PCT = 4320L      // 5 days full
    private const val MIN_SPAN_SEC = 3600L
    private const val MIN_DROP = 3
    private const val WINDOW_SEC = 2 * 86400L

    fun read(ctx: Context, nowSec: Long = System.currentTimeMillis() / 1000): WidgetRenderer.Battery? {
        val i: Intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        val pct = level * 100 / scale
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val charging = plugged || status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return record(ctx, pct, charging, nowSec)
    }

    /** Adds a sample and returns the marker state (pure apart from the prefs). */
    fun record(ctx: Context, pct: Int, charging: Boolean, nowSec: Long): WidgetRenderer.Battery {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var hist = runCatching { JSONArray(prefs.getString("hist", "[]")) }.getOrDefault(JSONArray())
        var anchorSec = prefs.getLong("anchor_sec", 0L)
        var anchorPct = prefs.getInt("anchor_pct", -1)
        if (charging) {
            hist = JSONArray(); anchorSec = 0L; anchorPct = -1
        } else {
            if (pct != anchorPct) { anchorSec = nowSec; anchorPct = pct; hist.put(JSONArray().put(nowSec).put(pct)) }
            val keep = JSONArray()
            for (k in 0 until hist.length()) hist.getJSONArray(k).let { if (nowSec - it.getLong(0) <= WINDOW_SEC) keep.put(it) }
            hist = keep
        }
        prefs.edit().putString("hist", hist.toString()).putLong("anchor_sec", anchorSec).putInt("anchor_pct", anchorPct).apply()
        return WidgetRenderer.Battery(pct, charging, anchorSec, if (anchorPct < 0) pct else anchorPct, secsPerPct(hist))
    }

    fun secsPerPct(hist: JSONArray): Long {
        if (hist.length() < 2) return DEFAULT_SECS_PER_PCT
        val a = hist.getJSONArray(0); val b = hist.getJSONArray(hist.length() - 1)
        val dSec = b.getLong(0) - a.getLong(0); val dPct = a.getInt(1) - b.getInt(1)
        if (dSec < MIN_SPAN_SEC || dPct < MIN_DROP) return DEFAULT_SECS_PER_PCT
        return (dSec / dPct).coerceIn(MIN_SECS_PER_PCT, MAX_SECS_PER_PCT)
    }
}
