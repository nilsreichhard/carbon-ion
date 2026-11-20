package com.nilsreichhard.carbonion.widget

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.provider.AlarmClock
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** The next alarm on the date row ("Thursday, Nov 19 · [icon] 07:00") and what the widget's taps open. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlarmAndTapsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun seed() {
        val o = JSONObject(Previews.payloadJson())
        WidgetStore.saveWeather(ctx, o.toString(), "Villars-sur-Ollon", JSONObject().toString())
    }

    private fun at(day: Int, h: Int, m: Int): Long = Calendar.getInstance(TimeZone.getDefault()).apply {
        set(2026, Calendar.NOVEMBER, day, h, m, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    @Test
    fun alarmTextShowsTheWeekdayOnlyBeyond24h() {
        val now = at(19, 21, 0)                 // Thu 21:00
        assertEquals("05:55", NextAlarm.format(at(20, 5, 55), now, true, 0, Locale.US))
        assertEquals("5:55", NextAlarm.format(at(20, 5, 55), now, true, 2, Locale.US))
        assertEquals("5:55 AM", NextAlarm.format(at(20, 5, 55), now, false, 0, Locale.US))
        assertEquals("20:59", NextAlarm.format(at(20, 20, 59), now, true, 0, Locale.US))    // 23 h 59 min away
        assertEquals("Fri 21:01", NextAlarm.format(at(20, 21, 1), now, true, 0, Locale.US))
        assertEquals("Sat 7:00 AM", NextAlarm.format(at(21, 7, 0), now, false, 0, Locale.US))
    }

    @Test
    fun dateRowShortensTheDateThenShrinksThenHidesTheAlarm() {
        val tf = Typeface.DEFAULT; val px = 40f
        val long = "Wednesday, Nov 18"; val short = "Wed, Nov 18"; val a = "Sat 07:00"
        val p = AlarmBadge.paint(tf, px)
        val longW = p.measureText(long) + AlarmBadge.alarmWidth(a, tf, px)
        val shortW = p.measureText(short) + AlarmBadge.alarmWidth(a, tf, px)
        assertEquals(AlarmBadge.Plan(false, 1f, true), AlarmBadge.plan(long, short, a, tf, px, longW + 1))
        assertEquals(AlarmBadge.Plan(true, 1f, true), AlarmBadge.plan(long, short, a, tf, px, shortW + 1))
        val shrunk = AlarmBadge.plan(long, short, a, tf, px, shortW * 0.95f)
        assertTrue(shrunk.shortDate && shrunk.showAlarm && shrunk.scale < 1f && shrunk.scale >= AlarmBadge.SHRINK.last())
        assertEquals("last resort: hide", AlarmBadge.Plan(false, 1f, false), AlarmBadge.plan(long, short, a, tf, px, shortW * 0.6f))
        assertEquals(AlarmBadge.Plan(false, 1f, false), AlarmBadge.plan(long, short, null, tf, px, 10_000f))
        assertEquals("EEE, MMM d", AlarmBadge.shortPattern("EEEE, MMM d"))
        assertEquals("EEE d MMM", AlarmBadge.shortPattern("EEEE d MMMM"))
    }

    @Test
    fun iconInkIsTheDateCapHeightCentredOnTheCaps() {
        for (tf in listOf(Typeface.create("sans-serif-light", Typeface.NORMAL), Typeface.SERIF, Typeface.create("sans-serif-condensed", Typeface.NORMAL))) {
            val px = 42f
            val bmp = AlarmBadge.iconBitmap(tf, px, 0xFF000000.toInt(), 0)
            val p = AlarmBadge.paint(tf, px); val fm = p.fontMetricsInt
            assertEquals("a text line tall", fm.descent - fm.ascent, bmp.height)
            val rows = (0 until bmp.height).filter { y -> (0 until bmp.width).any { x -> (bmp.getPixel(x, y) ushr 24) > 100 } }
            val cap = AlarmBadge.capHeight(p)
            assertEquals("ink ≈ cap height ($tf)", cap, (rows.last() - rows.first() + 1).toFloat(), 2.5f)
            assertEquals("centred on the caps ($tf)", -fm.ascent - cap / 2f, (rows.first() + rows.last() + 1) / 2f, 1.5f)
        }
    }

    private fun widget(alarm: String?, wDp: Int = 360, hDp: Int = 344): View =
        WidgetUpdater.build(ctx, WidgetUpdater.Size(wDp, hDp), 7, alarm, useAlarmOverride = true).apply(ctx, FrameLayout(ctx)).also {
            val w = (wDp * ctx.resources.displayMetrics.density).toInt(); val h = (hDp * ctx.resources.displayMetrics.density).toInt()
            it.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            it.layout(0, 0, w, h)
        }

    @Test
    fun alarmFollowsTheDateAsOneCentredRowAndTheClockIsFullSize() {
        for (style in listOf("modern", "classic")) {
            WidgetStore.saveUi(ctx, UiOptions.decode("style=$style"))
            val with = widget("Sat 07:00"); val without = widget(null)
            val date = with.findViewById<TextView>(R.id.date)
            val part = with.findViewById<View>(R.id.date_alarm)
            assertEquals("$style alarm shown", View.VISIBLE, part.visibility)
            val time = with.findViewById<TextView>(R.id.alarm_time)
            val sep = with.findViewById<TextView>(R.id.alarm_sep)
            assertEquals("Sat 07:00", time.text.toString())
            assertEquals(AlarmBadge.SEPARATOR, sep.text.toString())
            assertEquals("same size as the date", date.textSize, time.textSize, 0.01f)
            assertEquals("same colour as the date", date.currentTextColor, time.currentTextColor)
            assertEquals("same font as the date", date.typeface, time.typeface)
            val d = posIn(date, with); val pa = posIn(part, with)
            assertTrue("after the date", pa[0] >= d[0] + date.width)
            assertEquals("on the date's line", d[1].toFloat(), pa[1].toFloat(), 1f)
            val icon = with.findViewById<View>(R.id.alarm_icon)
            assertEquals("icon a text line tall", time.height.toFloat(), icon.height.toFloat(), 1f)
            val row = with.findViewById<View>(R.id.date_row); val r = posIn(row, with)
            assertEquals("row centred", with.width / 2f, r[0] + row.width / 2f, 2f)
            assertTrue("time not truncated", time.width >= time.paint.measureText("Sat 07:00") - 1)
            // The clock no longer makes room for the alarm: same size and centred either way.
            val cw = with.findViewById<TextView>(R.id.clock); val cn = without.findViewById<TextView>(R.id.clock)
            assertEquals(cn.textSize, cw.textSize, 0.01f)
            assertEquals("clock centred", with.width / 2f, posIn(cw, with)[0] + cw.width / 2f, 2f)
            // No alarm: the date alone, centred.
            assertEquals(View.GONE, without.findViewById<View>(R.id.date_alarm).visibility)
            val rn = without.findViewById<View>(R.id.date_row)
            assertEquals("date alone centred", without.width / 2f, posIn(rn, without)[0] + rn.width / 2f, 2f)
        }
    }

    @Test
    fun dateRowFitsWithoutTruncatingTheAlarmAtEverySize() {
        for ((w, h) in listOf(360 to 224, 360 to 344, 360 to 464, 250 to 344, 172 to 224))
            for (style in listOf("modern", "classic")) for (a in listOf("05:55", "Wed 00:00", "Sat 7:00 AM")) {
                WidgetStore.saveUi(ctx, UiOptions.decode("style=$style"))
                val v = widget(a, w, h)
                val shown = v.findViewById<View>(R.id.date_alarm).visibility == View.VISIBLE
                if (w == 360) assertTrue("$style ${w}x$h '$a' shown", shown)
                if (shown) {
                    val time = v.findViewById<TextView>(R.id.alarm_time)
                    assertTrue("$style ${w}x$h '$a' not truncated", time.width >= time.paint.measureText(a) - 1)
                    val date = v.findViewById<TextView>(R.id.date)
                    assertTrue("$style ${w}x$h '$a' date not truncated", date.width >= date.paint.measureText(date.text.toString()) - 1)
                }
            }
    }

    private fun resolveAlarms() {
        val cn = ComponentName("com.google.android.deskclock", "com.android.deskclock.DeskClock")
        shadowOf(ctx.packageManager).addActivityIfNotPresent(cn)
        shadowOf(ctx.packageManager).addIntentFilterForActivity(cn, IntentFilter(AlarmClock.ACTION_SHOW_ALARMS).apply { addCategory(Intent.CATEGORY_DEFAULT) })
    }

    @Test
    fun clockAndAlarmOpenTheAlarmsElsewhereTheExpandedScreen() {
        resolveAlarms()
        val t = TapIntents.taps(ctx, 7, NextAlarm.Info(System.currentTimeMillis() + 3_600_000L, null))
        assertEquals(AlarmClock.ACTION_SHOW_ALARMS, shadowOf(t.clock!!).savedIntent.action)
        assertEquals(AlarmClock.ACTION_SHOW_ALARMS, shadowOf(t.alarm!!).savedIntent.action)
        assertTrue(shadowOf(t.clock).isActivity)
        assertEquals(DetailActivity::class.java.name, shadowOf(t.other).savedIntent.component!!.className)
        // Wired on the views: the clock, the alarm part of the date row, the city row and the background.
        WidgetStore.saveUi(ctx, UiOptions.decode("style=modern"))
        val v = widget("05:55")
        assertTrue(v.findViewById<View>(R.id.clock).hasOnClickListeners())
        assertTrue("alarm part opens the alarms", v.findViewById<View>(R.id.date_alarm).hasOnClickListeners())
        assertTrue("the date falls through to the background", !v.findViewById<View>(R.id.date).hasOnClickListeners())
        assertTrue(!v.findViewById<View>(R.id.date_row).hasOnClickListeners())
        assertTrue(v.findViewById<View>(R.id.city_row).hasOnClickListeners())
        assertTrue(v.findViewById<View>(android.R.id.background).hasOnClickListeners())
    }

    @Test
    fun withoutAnAlarmsAppTheAlarmUsesItsOwnShowIntent() {
        val own = PendingIntent.getActivity(ctx, 9, Intent("com.example.ALARM"), PendingIntent.FLAG_IMMUTABLE)
        val t = TapIntents.taps(ctx, 7, NextAlarm.Info(System.currentTimeMillis() + 3_600_000L, own))
        assertSame(own, t.alarm)
        assertNull("no clock app on this device", t.clock)
        assertNotNull(t.other)
        assertNull(TapIntents.taps(ctx, 7, null).alarm)
    }

    /** Position of [v] inside [root] (views aren't attached to a window in these tests). */
    private fun posIn(v: View, root: View): IntArray {
        var x = 0; var y = 0; var c: View = v
        while (c !== root) { x += c.left; y += c.top; c = c.parent as View }
        return intArrayOf(x, y)
    }
}
