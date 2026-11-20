package com.nilsreichhard.carbonion.widget

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Debug builds only: loads a sample scenario for screenshots.
 *
 *   adb push villars.json /data/local/tmp/ && adb shell run-as <pkg> sh -c "mkdir -p files/samples; cp /data/local/tmp/villars.json files/samples/"
 *   adb shell am broadcast -n <pkg>/.DebugSamplesReceiver -a <pkg>.DEBUG_LOAD \
 *       --es scenario villars --es set SETTING_LIGHT_THEME=0,SETTING_RAIN_HEIGHT=1 --es bg '#1E3A5F' --ei opacity 80
 *   adb shell am broadcast ... -a <pkg>.DEBUG_WALLPAPER --es file wall_light.png
 *
 * "set" (KEY=VALUE,…) merges into the stored settings dict ("reset" true starts from the
 * scenario's dict); "bg" is "transparent" or #RRGGBB; "ui" sets the look ([UiOptions.encode]);
 * "alarm" overrides the next alarm (none | real | in:MIN | at:HH:MM[+days]).
 */
class DebugSamplesReceiver : BroadcastReceiver() {
    companion object {
        /** A plain file name in files/samples: no path separators, no "..". */
        fun safeName(name: String?): String? = name?.takeIf { it.isNotEmpty() && '/' !in it && '\\' !in it && ".." !in it && it.trim() == it }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val dir = File(ctx.filesDir, "samples")
        try { handle(ctx, intent, dir) } catch (e: Exception) { Log.e("CarbonDebug", "failed", e) }
    }

    private fun handle(ctx: Context, intent: Intent, dir: File) {
        when (intent.action?.substringAfterLast('.')) {
            "DEBUG_LOAD" -> {
                val prefs = ctx.getSharedPreferences("carbon_widget", Context.MODE_PRIVATE)
                intent.getStringExtra("scenario")?.let { raw ->
                    val name = safeName(raw) ?: throw IllegalArgumentException("bad scenario name")
                    val o = JSONObject(File(dir, "$name.json").readText())
                    // "age" (minutes): pretend the forecast was fetched that long ago (stale marker shots).
                    if (intent.hasExtra("age")) o.getJSONObject("payload").put("fetch_time", System.currentTimeMillis() / 1000 - intent.getIntExtra("age", 0) * 60L)
                    val keep = prefs.getString("dict", null)
                    WidgetStore.saveWeather(ctx, o.getJSONObject("payload").toString(), o.optString("city"),
                        if (intent.getBooleanExtra("reset", false) || keep == null) o.getJSONObject("settings").toString() else keep)
                    // Where the sample's coordinates "came from": "fallback" shows the no-location state.
                    WidgetStore.savePlaceSource(ctx, intent.getStringExtra("place") ?: "sample")
                }
                intent.getStringExtra("set")?.let { set ->
                    val d = JSONObject(prefs.getString("dict", null) ?: "{}")
                    // KEY=VALUE,KEY=VALUE (adb shell eats JSON quotes); numbers stay numbers.
                    set.split(',').filter { '=' in it }.forEach { kv ->
                        val (k, v) = kv.split('=', limit = 2)
                        d.put(k.trim(), v.trim().toIntOrNull() ?: v.trim())
                    }
                    WidgetStore.saveDict(ctx, d.toString())
                }
                // ui: "reset" or style=…,clock=…,text=…,size=…,scrim=0|1,accent=watch|wallpaper (merged).
                intent.getStringExtra("ui")?.let { u ->
                    val base = if (u.startsWith("reset")) UiOptions() else WidgetStore.ui(ctx)
                    WidgetStore.saveUi(ctx, UiOptions.decode(u.removePrefix("reset").trimStart(','), base))
                }
                intent.getStringExtra("city")?.let { prefs.edit().putString("city", it).apply() }
                // alarm: "none", "real", "in:<minutes>", or "at:HH:MM[+days]" (next such time, plus days).
                intent.getStringExtra("alarm")?.let { a ->
                    val at = when {
                        a == "none" -> NextAlarm.DEBUG_NONE
                        a == "real" -> 0L
                        a.startsWith("in:") -> System.currentTimeMillis() + a.removePrefix("in:").toLong() * 60_000L
                        a.startsWith("at:") -> {
                            val (hm, plus) = a.removePrefix("at:").split('+').let { it[0] to (it.getOrNull(1)?.toInt() ?: 0) }
                            val (hh, mm) = hm.split(':').map { it.toInt() }
                            val c = java.util.Calendar.getInstance().apply {
                                set(java.util.Calendar.HOUR_OF_DAY, hh); set(java.util.Calendar.MINUTE, mm); set(java.util.Calendar.SECOND, 0)
                                if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_MONTH, 1)
                                add(java.util.Calendar.DAY_OF_MONTH, plus)
                            }
                            c.timeInMillis
                        }
                        else -> 0L
                    }
                    prefs.edit().putLong(NextAlarm.DEBUG_PREF, at).apply()
                }
                intent.getStringExtra("bg")?.let { bg ->
                    val b = if (bg == "transparent") WidgetStore.Background()
                    else WidgetStore.Background(WidgetStore.BG_COLOR, bg.removePrefix("#").toInt(16), intent.getIntExtra("opacity", 100))
                    WidgetStore.saveBackground(ctx, b)
                }
                WidgetUpdater.updateAll(ctx)
                Log.i("CarbonDebug", "loaded ${intent.extras?.keySet()}")
            }
            "DEBUG_WALLPAPER" -> {
                val f = File(dir, safeName(intent.getStringExtra("file")) ?: return)
                WallpaperManager.getInstance(ctx).setBitmap(BitmapFactory.decodeFile(f.absolutePath), null, true,
                    WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                Log.i("CarbonDebug", "wallpaper $f")
            }
        }
    }
}
