package com.nilsreichhard.carbonion.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Phone battery → the watch's timeline battery markers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BatteryTrackerTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun h(vararg s: Pair<Long, Int>) = JSONArray().apply { s.forEach { put(JSONArray().put(it.first).put(it.second)) } }

    @Test fun drainRate() {
        assertEquals(BatteryTracker.DEFAULT_SECS_PER_PCT, BatteryTracker.secsPerPct(h(0L to 80)))
        assertEquals("under an hour", BatteryTracker.DEFAULT_SECS_PER_PCT, BatteryTracker.secsPerPct(h(0L to 80, 1800L to 70)))
        assertEquals("under 3 %", BatteryTracker.DEFAULT_SECS_PER_PCT, BatteryTracker.secsPerPct(h(0L to 80, 7200L to 78)))
        assertEquals(1200L, BatteryTracker.secsPerPct(h(0L to 80, 3600L to 77, 12000L to 70)))
        assertEquals(BatteryTracker.MIN_SECS_PER_PCT, BatteryTracker.secsPerPct(h(0L to 90, 3600L to 40)))
    }

    @Test fun anchorsAtThePercentChangeAndResetsWhenCharging() {
        val a = BatteryTracker.record(ctx, 60, false, 1000)
        val b = BatteryTracker.record(ctx, 60, false, 1600)
        assertEquals(1000L, b.anchorSec)            // flat percentage: markers don't slide
        val c = BatteryTracker.record(ctx, 59, false, 2000)
        assertEquals(2000L, c.anchorSec)
        assertTrue(!a.charging)
        val d = BatteryTracker.record(ctx, 59, true, 2100)
        assertTrue(d.charging)
        assertEquals(BatteryTracker.DEFAULT_SECS_PER_PCT, d.secsPerPct)
    }
}
