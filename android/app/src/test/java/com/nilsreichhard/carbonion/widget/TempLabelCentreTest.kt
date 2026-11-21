package com.nilsreichhard.carbonion.widget

import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * 1.1.6 (Nils 13:02): the high / current / low labels left of the temperature chart share one vertical
 * centre line instead of a staircase of left edges, keep the full-height even spread, fit long labels
 * (-12°, bold -10°, three digits) without clipping, and stay clear of the now line. 1.1.7 (Nils 14:51):
 * the column sits right next to the now line (a fixed gap), capped at 30 % of the chart, left-anchored and
 * shrunk when the now line is close to the left. 1.1.8 (Nils 15:09): the gap is ~8 dp (6-10 dp, scaled with
 * the chart width) in Modern and 7 rows in Classic. Modern and Classic; the app's mirror uses the same renderers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TempLabelCentreTest {
    private val samples = listOf(Triple(6, 5, 1), Triple(-3, -12, -15), Triple(31, 25, 18), Triple(-2, -10, -12), Triple(104, 101, 99))

    private fun check(name: String, pl: List<TempLabels.Placed>, xNow: Float, lh: Float) {
        val cs = pl.map { it.inkCenterX }
        assertTrue("$name centres $cs", cs.max() - cs.min() <= 1f)
        assertTrue("$name not clipped left ${pl.map { it.inkLeft }}", pl.all { it.inkLeft >= 0f })
        assertTrue("$name clear of the now line ${pl.map { it.inkRight }} < $xNow", pl.all { it.inkRight < xNow - 1f })
        // Full-height even spread kept: top and bottom padding, equal gaps.
        assertTrue("$name top ${pl[0].inkTop}", pl[0].inkTop in 0.5f..lh * 0.12f)
        val g1 = pl[1].inkTop - pl[0].inkBottom; val g2 = pl[2].inkTop - pl[1].inkBottom
        assertTrue("$name gaps $g1 $g2", Math.abs(g1 - g2) <= 1.01f && g1 > 0f)
        assertTrue("$name order", pl[0].inkBottom <= pl[1].inkTop && pl[1].inkBottom <= pl[2].inkTop)
    }

    /** Now-line positions (rows) on a 200-row chart: near the left edge, the usual 20 %, and far right (capped). */
    private val graphW = 200f

    @Test
    fun modernLabelsShareOneCentreNextToTheNowLine() {
        val left = 4f
        val cap = left + TempLabels.MAX_RIGHT_FRAC * graphW
        for (tf in listOf(Typeface.DEFAULT, Typeface.create("sans-serif-condensed", Typeface.NORMAL), Typeface.SERIF))
            for (lh in listOf(56f, 64f, 90f)) for ((hi, cur, lo) in samples) {
                val threeDigits = hi >= 100
                for (xNow in listOf(28f, 40f, 52f, 66f, 90f, 150f)) {
                    if (xNow < 40f && threeDigits) continue   // too narrow even at the 0.45 shrink floor
                    val name = "modern $tf lh=$lh xNow=$xNow $hi/$cur/$lo"
                    val pl = ModernRenderer.tempLabelLayout(tf, lh, left, xNow, 1f, listOf("$hi°", "$cur°", "$lo°"), emptyList(), emptyList(), graphW)
                    check(name, pl, xNow, lh)
                    val maxRight = ModernRenderer.labelFit(lh, tf, listOf("$hi°", "$cur°", "$lo°"), left, xNow, 1f, graphW).first
                    // 1.1.8: the gap from the now line's edge is 6-10 dp (here 200 dp of chart -> the 6 dp minimum);
                    // only where the labels can't fit even shrunk (now line ~28 dp in) does it narrow to 3 dp.
                    val gap = xNow - 0.8f - maxRight
                    if (xNow >= 40f) assertTrue("$name gap $gap", gap in 6f..10f) else assertTrue("$name gap $gap", gap == 3f || gap in 6f..10f)
                    val right = pl.maxOf { it.inkRight }
                    // No overlap: every label's ink ends at least the gap left of the now line (or the cap).
                    assertTrue("$name overlap $right > $maxRight", right <= minOf(maxRight, cap) + 0.75f)
                    // Close to the now line: the widest label ends at the gap (unless capped or left-anchored).
                    val anchoredLeft = pl.minOf { it.inkLeft } <= left + 2f + 0.75f
                    if (!anchoredLeft) assertTrue("$name gap ${minOf(maxRight, cap) - right}", right >= minOf(maxRight, cap) - 1f)
                    if (xNow >= 90f) assertTrue("$name capped $right <= $cap", right <= cap + 0.75f)
                }
            }
    }

    @Test
    fun modernGapScalesWithTheChartWidth() {
        // 3 px per dp: a 4x3 chart (~340 dp) gets 8 dp, a 2-cell (~150 dp) the 6 dp minimum, a very wide one at most 10 dp.
        assertEquals(8f * 3f, ModernRenderer.labelGap(3f, 340f * 3f), 0.01f)
        assertEquals(6f * 3f, ModernRenderer.labelGap(3f, 150f * 3f), 0.01f)
        assertEquals(10f * 3f, ModernRenderer.labelGap(3f, 700f * 3f), 0.01f)
        assertEquals(7f, WidgetRenderer.NOW_LABEL_GAP)
    }

    @Test
    fun classicLabelsShareOneCentreNextToTheNowLine() {
        val cap = 2f + TempLabels.MAX_RIGHT_FRAC * graphW
        for (lh in listOf(50, 62)) for ((hi, cur, lo) in samples) for (xNow in listOf(40f, 54f, 70f, 90f, 150f)) {
            val name = "classic lh=$lh xNow=$xNow $hi/$cur/$lo"
            val pl = WidgetRenderer.tempLabelLayout(lh, xNow, listOf("$hi", "$cur", "$lo"), emptyList(), emptyList(), graphW)
            check(name, pl, xNow, lh.toFloat())
            assertTrue("whole pixels", pl.all { it.x == Math.round(it.x).toFloat() })
            val maxRight = xNow - 1f - WidgetRenderer.NOW_LABEL_GAP
            val right = pl.maxOf { it.inkRight }
            assertTrue("$name overlap $right > $maxRight", right <= minOf(maxRight, cap) + 1f)
            assertTrue("$name gap ${minOf(maxRight, cap) - right}", right >= minOf(maxRight, cap) - 1.5f)
        }
    }

    /** Preview crops of the rendered chart for the three sample sets (build/outputs/preview/labels_*). */
    @Test
    fun renderedLabelsPreview() {
        val tz = TimeZone.getTimeZone("Europe/Zurich")
        for ((hi, cur, lo) in samples.take(3)) {
            val o = JSONObject(Previews.payloadJson())
            val t = JSONArray().apply { for (i in 0 until WeatherPayload.HOURS) put(lo + (hi - lo) * ((i * 7) % 10) / 9.0) }
            o.put("temp_hourly", t).put("apparent_temp_hourly", t).put("current_temp", cur.toDouble())
            val p = WeatherPayload.fromJson(o)
            val now = p.fetchTime * 1000 + 5 * 60_000
            for (light in listOf(true, false)) {
                val b = ModernRenderer.render(p, WidgetSettings(lightTheme = light), ModernRenderer.Spec(1080, 1032, 3f), now, tz)
                Previews.save(android.graphics.Bitmap.createBitmap(b, 0, b.height * 70 / 100, 380, b.height * 30 / 100),
                    "labels_modern_${if (light) "light" else "dark"}_${hi}_${cur}_$lo")
                val c = WidgetRenderer.render(p, WidgetSettings(lightTheme = light), WidgetRenderer.Spec(), now, tz)
                Previews.save(android.graphics.Bitmap.createBitmap(c, 0, c.height * 70 / 100, c.width * 35 / 100, c.height * 30 / 100),
                    "labels_classic_${if (light) "light" else "dark"}_${hi}_${cur}_$lo")
            }
        }
    }
}
