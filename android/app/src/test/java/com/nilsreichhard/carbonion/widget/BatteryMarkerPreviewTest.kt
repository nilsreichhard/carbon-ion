package com.nilsreichhard.carbonion.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.TextClock
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * 4.0.0 battery markers 20 / 10 / 5 / 0 % (Nils: no battery saver marker): the whole widget with all four
 * phone-battery markers (Modern + Classic, light) and the settings dropdown, to build/outputs/preview/batt400_*.png.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BatteryMarkerPreviewTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @After fun tearDown() = JvmQuickJs.reset()

    @Test
    fun widgetWithAllFourMarkers() {
        android.provider.Settings.System.putString(ctx.contentResolver, android.provider.Settings.System.TIME_12_24, "24")
        val size = WidgetUpdater.Size(360, 344)
        for (style in UiOptions.Style.values()) for ((tag, tb) in listOf("20_10_5_0" to 0, "10_0" to 7)) {
            WidgetStore.saveUi(ctx, UiOptions(style = style))
            WidgetStore.saveBackground(ctx, WidgetStore.Background())
            WidgetStore.saveWeather(ctx, Previews.payloadJson(), "Ollon",
                JSONObject().put("SETTING_LIGHT_THEME", 1).put("SETTING_TIMELINE_BATTERY", tb).toString())
            val now = WidgetStore.payload(ctx, true)!!.fetchTime * 1000 + 8 * 60_000L
            // 30 %, 1 % per 12 min: 20 % in 2 h, 10 % in 4 h, 5 % in 5 h, 0 % in 6 h.
            val batt = WidgetRenderer.Battery(30, false, now / 1000, 30, 720)
            val view = WidgetUpdater.build(ctx, size, 1, null, useAlarmOverride = true, nowMillis = now,
                batteryOverride = batt, useBatteryOverride = true).apply(ctx, FrameLayout(ctx))
            view.findViewById<TextClock>(R.id.clock).apply { format24Hour = "'14:09'"; format12Hour = "'2:09'" }
            view.findViewById<TextClock>(R.id.date).apply { format24Hour = "'Wednesday, Nov 18'"; format12Hour = format24Hour }
            val d = ctx.resources.displayMetrics.density
            val w = (size.wDp * d).roundToInt(); val h = (size.hDp * d).roundToInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            val pad = (16 * d).roundToInt()
            val out = Previews.wallpaper(w + 2 * pad, h + 2 * pad, false)
            Canvas(out).apply { translate(pad.toFloat(), pad.toFloat()); view.draw(this) }
            Previews.save(out, "batt400_widget_${style.key}_light_$tag")
        }
    }

    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    @Test
    fun settingsDropdown() {
        JvmQuickJs.require()
        JsRuntime.store(ctx).edit().clear().commit()
        val ctl = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val act = ctl.get()
        val content = ((act.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)) as ViewGroup).getChildAt(0)
        val w = (411 * act.resources.displayMetrics.density).toInt()
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        content.layout(0, 0, w, content.measuredHeight)
        val sp = all(content).filterIsInstance<Spinner>().single { s -> (0 until s.adapter.count).any { s.adapter.getItem(it) == "10 / 0 %" } }
        val labels = (0 until sp.adapter.count).map { sp.adapter.getItem(it).toString() }
        assertEquals(listOf("20 / 10 / 5 / 0 %", "10 / 5 / 0 %", "5 / 0 %", "10 / 0 %", "0 %", "None"), labels)
        assertEquals(0, sp.selectedItemPosition)
        val d = act.resources.displayMetrics.density
        // The closed field with its label (page crop) ...
        var y = 0; var v: View = sp
        while (v !== content) { y += v.top; v = v.parent as View }
        val page = Bitmap.createBitmap(w, content.measuredHeight, Bitmap.Config.ARGB_8888)
        Canvas(page).apply { drawColor(Color.WHITE); content.draw(this) }
        val top = (y - 90 * d).roundToInt().coerceAtLeast(0)
        val fieldBottom = (y + sp.height + 60 * d).roundToInt().coerceAtMost(page.height)
        // ... and the open list drawn from the spinner's own dropdown views below it.
        val rows = labels.indices.map { i ->
            sp.adapter.getDropDownView(i, null, sp).apply {
                measure(View.MeasureSpec.makeMeasureSpec(sp.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                layout(0, 0, sp.width, measuredHeight)
            }
        }
        val listH = rows.sumOf { it.measuredHeight }
        val gap = (12 * d).roundToInt()
        val out = Bitmap.createBitmap(w, fieldBottom - top + gap + listH + 2 * gap, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(Color.WHITE)
        c.drawBitmap(Bitmap.createBitmap(page, 0, top, w, fieldBottom - top), 0f, 0f, null)
        val x0 = sp.left + (sp.parent as View).left
        val ly = fieldBottom - top + gap
        c.drawRect(x0 - 1f, ly - 1f, x0 + sp.width + 1f, ly + listH + 1f, Paint().apply { color = 0xFFBDBDBD.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f })
        var ry = ly
        for ((i, r) in rows.withIndex()) {
            c.save(); c.translate(x0.toFloat(), ry.toFloat())
            if (i == 0) c.drawRect(0f, 0f, sp.width.toFloat(), r.measuredHeight.toFloat(), Paint().apply { color = 0x14000000 })
            r.draw(c); c.restore(); ry += r.measuredHeight
        }
        Previews.save(out, "batt400_settings_dropdown")
        ctl.pause().stop().destroy()
    }
}
