package com.nilsreichhard.carbonion.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/**
 * 1.1.5: "heavier or more cover looks more prominent" for every cloud layer, on every surface.
 * Light palettes: darker than the background; dark palettes: brighter. For each style (Modern,
 * Classic), cloud mode (Total; Split low / mid / high, one layer at a time), theme / background
 * (light, dark, custom cream, custom navy) and surface (widget, the app's mirror; 1.1.6 dropped the
 * separate Forecast card) it renders uniform cover 0…100 % plus heavy (thunder), thick and thin hours,
 * composites over the background, and measures the cloud pixels' luminance contrast toward the
 * palette's ink: the mean (area × strength) and the peak (strongest 2 %) must not decrease
 * with cover, heavy and thick must be at least 100 %, thin at most 100 %.
 * Writes build/outputs/preview/cloud_layers_matrix.png and cloud_layers_matrix.txt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CloudLayerMatrixTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val base = JSONObject(Previews.payloadJson())
    private val now = base.getLong("fetch_time") * 1000 + 5 * 60_000

    enum class Layer(val label: String) { TOTAL("Total"), LOW("Split low"), MID("Split mid"), HIGH("Split high") }
    class Bg(val label: String, val bg: WidgetStore.Background, val themeLight: Boolean)
    class Case(val label: String, val cover: Int, val kind: Int) // kind 0 normal, 1 heavy, 2 thick, 3 thin

    private val bgs = listOf(
        Bg("light", WidgetStore.Background(), true),
        Bg("dark", WidgetStore.Background(), false),
        Bg("cream bg (theme dark)", WidgetStore.Background(WidgetStore.BG_COLOR, 0xF3E9D2, 100), false),
        Bg("navy bg (theme light)", WidgetStore.Background(WidgetStore.BG_COLOR, 0x1A2340, 100), true),
    )
    private val cases = (0..100 step 10).map { Case("$it%", it, 0) } + listOf(Case("heavy", 100, 1), Case("thick", 100, 2), Case("thin", 100, 3))

    private fun payloadJson(layer: Layer, c: Case): String {
        val o = JSONObject(base.toString())
        fun arr(v: Int) = JSONArray().apply { repeat(WeatherPayload.HOURS) { put(v) } }
        o.put("cloud_cover", arr(c.cover))
        o.put("cloud_cover_low", arr(if (layer == Layer.LOW) c.cover else 0))
        o.put("cloud_cover_mid", arr(if (layer == Layer.MID) c.cover else 0))
        o.put("cloud_cover_high", arr(if (layer == Layer.HIGH) c.cover else 0))
        o.put("hourly_weather_code", arr(if (c.kind == 1) 95 else 3))
        o.put("precip_prob", arr(0)); o.put("precip_mm", arr(0)); o.put("shortwave_radiation", arr(0))
        o.put("wind_bits", arr(0)); o.put("storm_bits", arr(0)); o.put("fog_bits", arr(0))
        o.put("cloud_thick", arr(when (c.kind) { 2 -> 0xFF; 3 -> 0x55; else -> 0xAA }))
        return o.toString()
    }

    private fun setUp(style: UiOptions.Style, layer: Layer, b: Bg, c: Case) {
        val dict = JSONObject().put("SETTING_LIGHT_THEME", if (b.themeLight) 1 else 0)
            .put("SETTING_CLOUD_DISPLAY_MODE", if (layer == Layer.TOTAL) 0 else 1).put("SETTING_CLOUD_THICK", 0)
            .put("SETTING_FORECAST_HOURS", 24).put("SETTING_SUNLIGHT_SENSITIVITY", 4)
        ctx.getSharedPreferences(WidgetStore.PREFS, Context.MODE_PRIVATE).edit().clear()
            .putString("payload", payloadJson(layer, c)).putString("city", "").putString("dict", dict.toString())
            .putBoolean("theme_chosen", true).commit()
        WidgetStore.saveBackground(ctx, b.bg)
        WidgetStore.saveUi(ctx, UiOptions(style = style))
    }

    private fun widgetChart(size: WidgetUpdater.Size): Bitmap {
        val v = WidgetUpdater.build(ctx, size, 1, null, useAlarmOverride = true, nowMillis = now).apply(ctx, FrameLayout(ctx))
        return ((v.findViewById<ImageView>(R.id.chart)).drawable as BitmapDrawable).bitmap
    }

    private fun over(px: Int, bg: Int): Int {
        val a = Color.alpha(px) / 255f
        fun ch(s: Int, d: Int) = (s * a + d * (1 - a)).toInt()
        return Color.rgb(ch(Color.red(px), Color.red(bg)), ch(Color.green(px), Color.green(bg)), ch(Color.blue(px), Color.blue(bg)))
    }
    private fun lum(c: Int) = (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)).toFloat()

    class Result(val surface: String, val style: String, val layer: Layer, val bg: String, val palLight: Boolean,
                 val mean: FloatArray, val peak: FloatArray, val crops: List<Bitmap>, val problems: List<String>) {
        val pass get() = problems.isEmpty()
    }

    private fun measure(surface: String, style: String, layer: Layer, b: Bg, palLight: Boolean, backdrop: Int, renders: List<Bitmap>): Result {
        val comp = renders.map { r -> IntArray(r.width * r.height).also { r.getPixels(it, 0, r.width, 0, 0, r.width, r.height) }.map { over(it, backdrop) }.toIntArray() }
        val w = renders[0].width; val h = renders[0].height
        val bgL = lum(backdrop)
        val baseL = comp[0].map { lum(it) }
        val L = comp.map { a -> FloatArray(a.size) { lum(a[it]) } }
        // Cloud pixels: background in the no-cloud render, changed by some cover level.
        val mask = BooleanArray(w * h) { i -> Math.abs(baseL[i] - bgL) < 1.5f && (1..10).any { Math.abs(L[it][i] - baseL[i]) > 1.5f } }
        val idx = mask.indices.filter { mask[it] }
        val sign = if (palLight) -1f else 1f
        val mean = FloatArray(L.size); val peak = FloatArray(L.size)
        for (k in L.indices) {
            val v = idx.map { sign * (L[k][it] - bgL) }.sorted()
            mean[k] = if (v.isEmpty()) 0f else v.average().toFloat()
            val top = v.takeLast(maxOf(1, v.size / 50))
            peak[k] = if (v.isEmpty()) 0f else top.average().toFloat()
        }
        val pr = ArrayList<String>()
        if (idx.isEmpty()) pr.add("no cloud pixels")
        for (k in 1..10) {
            if (mean[k] < mean[k - 1] - 0.3f) pr.add("mean ${cases[k - 1].label}→${cases[k].label} ${"%.1f".format(mean[k - 1])}→${"%.1f".format(mean[k])}")
            if (peak[k] < peak[k - 1] - 1.0f) pr.add("peak ${cases[k - 1].label}→${cases[k].label} ${"%.1f".format(peak[k - 1])}→${"%.1f".format(peak[k])}")
        }
        for (k in L.indices) if (mean[k] < -0.5f) pr.add("${cases[k].label} goes the wrong way (${"%.1f".format(mean[k])})")
        for (k in listOf(11, 12)) {
            if (mean[k] < mean[10] - 0.3f) pr.add("${cases[k].label} mean below 100% (${"%.1f".format(mean[k])} < ${"%.1f".format(mean[10])})")
            if (peak[k] < peak[10] - 1.0f) pr.add("${cases[k].label} peak below 100% (${"%.1f".format(peak[k])} < ${"%.1f".format(peak[10])})")
        }
        if (mean[13] > mean[10] + 0.3f) pr.add("thin mean above 100%")
        // Crops for the sheet: the cloud rows, a slice in the middle.
        val ys = idx.map { it / w }; val y0 = (ys.minOrNull() ?: 0) - 4; val y1 = (ys.maxOrNull() ?: 10) + 4
        val x0 = (w * 0.42).toInt(); val cw = minOf(150, w - x0)
        val crops = comp.map { a ->
            val bm = Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
            Bitmap.createBitmap(bm, x0, y0.coerceAtLeast(0), cw, (y1 - y0).coerceAtMost(h - y0.coerceAtLeast(0)).coerceAtLeast(1))
        }
        return Result(surface, style, layer, b.label, palLight, mean, peak, crops, pr)
    }

    @Test
    fun everyCloudLayerGetsMoreProminentWithCoverOnEverySurface() {
        val results = ArrayList<Result>()
        val widgetSize = WidgetUpdater.Size(360, 300)
        val mirrorSize = WidgetUpdater.mirrorSize(379, 0.9f)
        for (style in UiOptions.Style.entries) for (layer in Layer.entries) for (b in bgs) {
            val modern = style == UiOptions.Style.MODERN
            val palLight = WidgetColors.effectiveLight(b.bg, b.themeLight, modern)
            val backdrop = if (b.bg.mode == WidgetStore.BG_COLOR) WidgetColors.blended(b.bg, b.themeLight) else if (b.themeLight) Color.WHITE else Color.BLACK
            val wid = ArrayList<Bitmap>(); val mir = ArrayList<Bitmap>()
            for (c in cases) {
                setUp(style, layer, b, c)
                wid.add(widgetChart(widgetSize)); mir.add(widgetChart(mirrorSize))
            }
            results.add(measure("widget", style.name.lowercase().replaceFirstChar { it.uppercase() }, layer, b, palLight, backdrop, wid))
            results.add(measure("mirror", style.name.lowercase().replaceFirstChar { it.uppercase() }, layer, b, palLight, backdrop, mir))
        }
        val txt = StringBuilder()
        for (r in results) {
            txt.append(String.format(Locale.US, "%-4s %-8s %-8s %-10s %-22s pal=%-5s mean=[%s] peak=[%s] %s\n", if (r.pass) "PASS" else "FAIL",
                r.surface, r.style, r.layer.label, r.bg, if (r.palLight) "light" else "dark",
                r.mean.joinToString(",") { "%.0f".format(Locale.US, it) }, r.peak.joinToString(",") { "%.0f".format(Locale.US, it) },
                r.problems.joinToString("; ")))
        }
        File(Previews.outDir, "cloud_layers_matrix.txt").writeText(txt.toString())
        sheet(results.filter { it.surface != "mirror" && !(it.surface == "forecast" && it.style == "Classic") })
        val failed = results.filter { !it.pass }
        assertTrue("${failed.size}/${results.size} combinations fail:\n" + failed.joinToString("\n") {
            "${it.surface} ${it.style} ${it.layer.label} ${it.bg}: ${it.problems.joinToString("; ")}" }, failed.isEmpty())
    }

    private fun sheet(rows: List<Result>) {
        val labelW = 560; val cellW = 150; val cellH = 64; val head = 50; val passW = 110
        val out = Bitmap.createBitmap(labelW + cases.size * (cellW + 6) + passW, head + rows.size * (cellH + 22), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(120, 120, 120))
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Color.WHITE }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f; color = Color.WHITE }
        cases.forEachIndexed { i, cs -> c.drawText(cs.label, labelW + i * (cellW + 6) + 8f, 34f, tp) }
        c.drawText("mean contrast toward ink under each cell", 10f, 34f, small)
        rows.forEachIndexed { r, res ->
            val y = head + r * (cellH + 22)
            val surf = if (res.surface == "forecast") "Forecast card" else "Widget + mirror"
            c.drawText("$surf · ${if (res.surface == "forecast") "Modern" else res.style}", 10f, y + 24f, tp)
            c.drawText("${res.layer.label} · ${res.bg}", 10f, y + 52f, tp)
            res.crops.forEachIndexed { i, cr ->
                val x = labelW + i * (cellW + 6)
                c.drawBitmap(cr, Rect(0, 0, cr.width, cr.height), Rect(x, y, x + cellW, y + cellH), null)
                c.drawText("%.0f / %.0f".format(Locale.US, res.mean[i], res.peak[i]), x + 4f, y + cellH + 17f, small)
            }
            val pp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f; color = if (res.pass) Color.rgb(140, 255, 140) else Color.rgb(255, 120, 120); isFakeBoldText = true }
            c.drawText(if (res.pass) "PASS" else "FAIL", labelW + cases.size * (cellW + 6) + 10f, y + 42f, pp)
        }
        Previews.save(out, "cloud_layers_matrix")
    }
}
