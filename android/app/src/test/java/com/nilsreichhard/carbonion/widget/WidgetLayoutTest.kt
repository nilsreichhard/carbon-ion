package com.nilsreichhard.carbonion.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Breakpoints and range fitting (WidgetLayout). */
class WidgetLayoutTest {
    @Test fun heightsPickTheBreakpoint() {
        // Pixel Launcher portrait: 115 dp per row (4x3 = 360x344 dp); landscape 4x3 = 625x208.
        assertEquals(WidgetLayout.SHORT, WidgetLayout.forHeight(115))
        assertEquals(WidgetLayout.SHORT, WidgetLayout.forHeight(229))
        assertEquals(WidgetLayout.SHORT, WidgetLayout.forHeight(208))
        assertEquals(WidgetLayout.DEFAULT, WidgetLayout.forHeight(280))
        assertEquals(WidgetLayout.DEFAULT, WidgetLayout.forHeight(344))
        assertEquals(WidgetLayout.TALL, WidgetLayout.forHeight(400))
        assertEquals(WidgetLayout.TALL, WidgetLayout.forHeight(459))
    }

    @Test fun defaultIsTheEmeryGrid() {
        val d = WidgetLayout.DEFAULT
        assertEquals(73, d.timeY)
        assertEquals(172, d.tempY)
        assertEquals(228, d.totalRows)
        assertEquals(169, WidgetLayout.SHORT.totalRows)
        assertTrue(!WidgetLayout.SHORT.hasTemp)
        assertTrue(WidgetLayout.TALL.totalRows > d.totalRows && WidgetLayout.TALL.tempH > d.tempH)
    }

    @Test fun narrowWidthsShortenTheRange() {
        assertEquals(24, WidgetLayout.fitHours(24, 320))   // 4 cells: the watch's 24 h
        assertEquals(48, WidgetLayout.fitHours(48, 400))
        assertEquals(36, WidgetLayout.fitHours(48, 250))
        assertEquals(18, WidgetLayout.fitHours(24, 140))   // 2 cells
        assertEquals(12, WidgetLayout.fitHours(24, 60))
        assertEquals(12, WidgetLayout.fitHours(12, 1000))  // never above the setting
        for (w in 40..800 step 7) for (r in intArrayOf(12, 18, 24, 36, 48)) {
            val h = WidgetLayout.fitHours(r, w)
            assertTrue(h <= r)
            assertTrue("$r@$w -> $h", h == 12 || w >= (h + h / 4) * WidgetLayout.MIN_COL_ROWS)
        }
    }
}
