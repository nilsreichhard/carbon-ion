package com.nilsreichhard.carbonion.widget

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/** 1.1.6: the app's widget mirror is the forecast chart: touch / drag selects an hour, details below. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MirrorInspectTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private val controllers = ArrayList<org.robolectric.android.controller.ActivityController<DetailActivity>>()

    /** AppCompat keeps live activities in a static list; a leftover one would be recreated in the next test. */
    @org.junit.After fun tearDown() { controllers.forEach { runCatching { it.pause().stop().destroy() } } }

    @Before fun setUp() {
        ctx.getSharedPreferences(WidgetStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        DetailActivity.mirrorExecutor = java.util.concurrent.Executor { it.run() }
    }

    // ---------------------------------------------------------------- x -> column mapping

    @Test
    fun evenColumnsRespectTheInsets() {
        // 300 px wide, 30 px inset each side, 24 columns of 10 px.
        val c = MirrorColumns.even(30f, 10f, 24, 300f, startSlot = 6)
        assertEquals(24, c.total)
        assertNull("left inset", c.columnAt(29.9f / 300))
        assertEquals(0, c.columnAt(30f / 300))
        assertEquals(0, c.columnAt(39.9f / 300))
        assertEquals(1, c.columnAt(40f / 300))
        assertEquals(23, c.columnAt(269.9f / 300))
        assertNull("right inset", c.columnAt(270f / 300))
        assertNull(c.columnAt(-0.1f)); assertNull(c.columnAt(1.2f))
        // Dragging past the ends sticks to the first / last hour.
        assertEquals(0, c.columnAt(0f, clamp = true)); assertEquals(23, c.columnAt(1f, clamp = true))
        assertEquals(6 + 23, c.slotOf(23))
        assertEquals(35f / 300, c.centerFrac(0), 1e-6f)
    }

    @Test
    fun classicColumnsFollowWholeRows() {
        // 320 rows, 18 columns: edges at i * 320 / 18 (0, 17, 35, 53, ...), as WidgetRenderer.colX.
        val c = MirrorColumns.rows(320, 18, startSlot = 9)
        assertEquals(0, c.columnAt(0f))
        assertEquals(0, c.columnAt(16.99f / 320))
        assertEquals(1, c.columnAt(17f / 320))
        assertEquals(2, c.columnAt(35f / 320))
        assertEquals(17, c.columnAt(319.9f / 320))
        assertNull(c.columnAt(1f))
        assertEquals(17, c.columnAt(1f, clamp = true))
    }

    private fun freshPayload(): String = JSONObject(Previews.payloadJson()).put("fetch_time", System.currentTimeMillis() / 1000 - 600).toString()

    @Test
    fun buildReportsTheRenderedColumnsForBothStyles() {
        val p = WeatherPayload.fromJson(freshPayload())
        for (style in UiOptions.Style.entries) for (w in listOf(379, 200)) {
            WidgetStore.saveWeather(ctx, freshPayload(), "Ollon", "{}")
            WidgetStore.saveUi(ctx, UiOptions(style = style))
            var cols: MirrorColumns? = null
            val now = System.currentTimeMillis()
            WidgetUpdater.build(ctx, WidgetUpdater.mirrorSize(w, 0.9f), columnsOut = { cols = it }, nowMillis = now)
            val c = assertNotNullAnd(cols, "$style $w")
            val s = WidgetStore.settings(ctx)
            // The columns are the window the widget draws (narrow widgets fit fewer hours).
            assertTrue("$style $w: ${c.total}", c.total <= s.totalHours && c.total >= 12)
            val fitted = s.copy(forecastHours = c.total - s.pastHours)
            assertEquals(WidgetRenderer.Window.of(p, fitted, now, TimeZone.getDefault()).startSlot, c.startSlot)
            assertTrue("edges increase", c.edges.toList().zipWithNext().all { (a, b) -> b > a })
            if (style == UiOptions.Style.MODERN) assertTrue("Modern has side insets", c.edges.first() > 0f && c.edges.last() < 1f)
            else { assertEquals(0f, c.edges.first()); assertEquals(1f, c.edges.last()) }
        }
        WidgetStore.saveWeather(ctx, "null", "Ollon", "{}")
        var none: MirrorColumns? = MirrorColumns.even(0f, 1f, 1, 1f, 0)
        ctx.getSharedPreferences(WidgetStore.PREFS, Context.MODE_PRIVATE).edit().remove("payload").commit()
        WidgetUpdater.build(ctx, WidgetUpdater.mirrorSize(379, 0.9f), columnsOut = { none = it })
        assertNull("no data, no columns", none)
    }

    private fun <T> assertNotNullAnd(v: T?, msg: String): T { assertNotNull(msg, v); return v!! }

    // ---------------------------------------------------------------- the activity

    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    private fun touch(v: View, action: Int, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(t, t, action, x, y, 0)
        v.dispatchTouchEvent(e); e.recycle()
    }

    private fun open(light: Boolean): Pair<DetailActivity, MirrorFrame> {
        ctx.getSharedPreferences(WidgetStore.PREFS, Context.MODE_PRIVATE).edit()
            .putString("payload", freshPayload()).putString("city", "Villars-sur-Ollon")
            .putString("dict", JSONObject().put("SETTING_LIGHT_THEME", if (light) 1 else 0).toString()).commit()
        // Apply the theme's night mode first (switching it recreates the activity, which Robolectric's TextClock dislikes).
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(WidgetStore.nightMode(WidgetStore.settings(ctx)))
        val ctl = Robolectric.buildActivity(DetailActivity::class.java).setup()
        controllers += ctl
        val act = ctl.get()
        val f = act.findViewById<MirrorFrame>(R.id.widget_mirror)
        assertNotNull(f)
        return act to f
    }

    private fun texts(act: DetailActivity) = all(act.findViewById(android.R.id.content)).filterIsInstance<TextView>().map { it.text.toString() }

    @Test
    fun touchAndDragOnTheMirrorShowTheHourBelow() {
        val (act, f) = open(light = true)
        val c = f.columns!!
        assertNull(f.selected)
        assertTrue(texts(act).any { it.startsWith("Touch or drag") })
        assertTrue("no separate Forecast card", texts(act).none { it == "Forecast" })
        // Timeline row (top of the mirror), column 3.
        touch(f, MotionEvent.ACTION_DOWN, c.centerFrac(3) * f.width, 4f)
        assertEquals(3, f.selected)
        val h = DetailData.hour(WidgetStore.payload(ctx)!!, c.slotOf(3))
        assertTrue(texts(act).toString(), texts(act).any { it.contains(DetailData.conditionName(h.code)) })
        assertTrue(texts(act).any { it.contains(DetailData.fmtTemp(h.temp, true)) })
        // Drag past the right edge: the last hour; release keeps it.
        touch(f, MotionEvent.ACTION_MOVE, f.width + 50f, 4f)
        touch(f, MotionEvent.ACTION_UP, f.width + 50f, 4f)
        assertEquals(c.total - 1, f.selected)
        // The clock block doesn't scrub.
        val tb = f.findViewById<View>(R.id.time_block)
        var top = tb.top; var v: View = tb
        while (v.parent !== f) { v = v.parent as View; top += v.top }
        touch(f, MotionEvent.ACTION_DOWN, c.centerFrac(1) * f.width, top + tb.height / 2f)
        assertEquals(c.total - 1, f.selected)
        // A tap in the side inset clears (Modern).
        touch(f, MotionEvent.ACTION_DOWN, 1f, f.height - 4f)
        assertNull(f.selected)
        assertTrue(texts(act).any { it.startsWith("Touch or drag") })
    }

    @Test
    fun talkBackStepsThroughTheHours() {
        val (_, f) = open(light = false)
        val info = AccessibilityNodeInfo.obtain()
        f.onInitializeAccessibilityNodeInfo(info)
        assertTrue(info.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD))
        assertEquals(f.columns!!.total - 1f, info.rangeInfo.max)
        assertTrue(f.contentDescription.toString().contains("Swipe up or down"))
        f.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
        val start = f.selected!!
        assertEquals(WidgetStore.settings(ctx).pastHours, start)
        f.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
        assertEquals(start + 1, f.selected)
        f.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null)
        assertEquals(start, f.selected)
        assertTrue(f.stateDescription.toString().contains("°"))
    }

    /** Screens for v15: idle and inspecting, light and dark. */
    private fun screenshots(light: Boolean) {
        val (act, f) = open(light)
        fun shot(name: String) {
            val root = act.window.decorView
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.width, root.height)
            val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(b))
            Previews.save(b, name)
        }
        shot("app_mirror_idle_${if (light) "light" else "dark"}")
        val c = f.columns!!
        touch(f, MotionEvent.ACTION_DOWN, c.centerFrac(c.total * 2 / 3) * f.width, f.height - 20f)
        touch(f, MotionEvent.ACTION_UP, c.centerFrac(c.total * 2 / 3) * f.width, f.height - 20f)
        shot("app_mirror_inspect_${if (light) "light" else "dark"}")
    }

    @Test fun screenshotsLight() = screenshots(true)

    @Test fun screenshotsDark() = screenshots(false)
}
