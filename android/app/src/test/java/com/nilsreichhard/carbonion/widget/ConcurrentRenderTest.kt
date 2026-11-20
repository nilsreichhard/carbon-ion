package com.nilsreichhard.carbonion.widget

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Collections
import java.util.TimeZone
import java.util.concurrent.CountDownLatch

/**
 * 1.0.1 refresh glitch: the widget (worker thread) and the app's chart (main thread) rendered at the
 * same time with one shared scratch Paint, so curves got filled with a straight closing edge and fills
 * took other shaders' colours. Renders from several threads at once must match the serial render.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConcurrentRenderTest {
    private val tz = TimeZone.getTimeZone("Europe/Zurich")
    private val p = WeatherPayload.fromJson(Previews.payloadJson())
    private val now = p.fetchTime * 1000 + 5 * 60_000

    private fun hammer(threads: Int, rounds: Int, render: (Boolean) -> Bitmap): List<String> {
        val ref = mapOf(true to render(true), false to render(false))
        val bad = Collections.synchronizedList(ArrayList<String>())
        val start = CountDownLatch(1)
        val ts = (0 until threads).map { t ->
            Thread {
                start.await()
                repeat(rounds) { r ->
                    val light = (t + r) % 2 == 0
                    val b = render(light)
                    if (!b.sameAs(ref[light])) bad.add("thread $t round $r light=$light")
                }
            }.apply { start() }
        }
        start.countDown()
        ts.forEach { it.join(120_000) }
        return bad
    }

    @Test
    fun modernDetailChartIsThreadSafe() {
        val spec = ModernRenderer.Spec(720, 560, 2f, WidgetLayout.DETAIL, textShadow = false)
        val s = WidgetSettings(forecastHours = 48, rainHeight = 2)
        val bad = hammer(4, 8) { light -> ModernRenderer.render(p, s.copy(lightTheme = light), spec, now, tz) }
        assertTrue("concurrent renders differ from the serial one: $bad", bad.isEmpty())
    }

    @Test
    fun classicIsThreadSafe() {
        val spec = WidgetRenderer.Spec.forWidget(360, 344, 2f, WidgetLayout.DEFAULT, "00:00",
            CarbonStyle.CLOCK_TEXT_ROWS, null, WidgetRenderer.CLOCK_TYPEFACE)
        val bad = hammer(4, 8) { light -> WidgetRenderer.render(p, WidgetSettings(lightTheme = light), spec, now) }
        assertTrue("concurrent renders differ from the serial one: $bad", bad.isEmpty())
    }

    @Test
    fun eventIconsAreSmaller() {
        assertEquals(8.8f, ModernRenderer.EVENT_ICON_ROWS, 0.01f)
    }
}
