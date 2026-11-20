package com.nilsreichhard.carbonion.widget

import android.content.Context
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Style and font options: defaults, persistence, per-font layouts and typefaces. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiOptionsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun defaultsAreModernWithStyleFonts() {
        val o = UiOptions()
        assertEquals(UiOptions.Style.MODERN, o.style)
        assertEquals(FontOption.LIGHT, o.clock())
        assertEquals(FontOption.SYSTEM, o.text())
        val classic = o.copy(style = UiOptions.Style.CLASSIC)
        assertEquals(FontOption.CONDENSED, classic.clock())
        assertEquals(UiOptions(), WidgetStore.ui(ctx)) // nothing saved yet
    }

    @Test
    fun encodeDecodeAndPersist() {
        val o = UiOptions(UiOptions.Style.CLASSIC, FontOption.SERIF, FontOption.OUTFIT, UiOptions.ClockSize.LARGE, scrim = true, wallpaperAccent = true)
        assertEquals(o, UiOptions.decode(o.encode()))
        WidgetStore.saveUi(ctx, o)
        assertEquals(o, WidgetStore.ui(ctx))
        // Unknown keys and values are ignored; partial strings merge into the base.
        assertEquals(o.copy(clockFont = FontOption.MONO), UiOptions.decode("clock=mono,bogus=1,size=huge", o))
    }

    @Test
    fun bundledFontsAreTextOnly() {
        for (f in listOf(FontOption.ROUNDED, FontOption.OUTFIT, FontOption.INTER)) {
            assertTrue(f.bundled); assertFalse(f.clockCapable)
            assertEquals(FontOption.LIGHT, UiOptions(clockFont = f).clock()) // falls back to the style default
            assertEquals(f, UiOptions(textFont = f).text())
        }
        assertTrue(FontOption.entries.filter { it != FontOption.AUTO && !it.bundled }.all { it.clockCapable })
    }

    @Test
    fun everyFontHasLayoutsAndATypeface() {
        val inflater = LayoutInflater.from(ctx)
        val seen = HashSet<Int>()
        for (f in FontOption.entries) {
            if (f == FontOption.AUTO) continue
            val clock = inflater.inflate(f.clockLayout, FrameLayout(ctx), false).findViewById<TextClock>(R.id.clock)
            val text = inflater.inflate(f.textLayout, FrameLayout(ctx), false)
            assertNotNull(clock); assertNotNull(text.findViewById<TextView>(R.id.city)); assertNotNull(text.findViewById<TextClock>(R.id.date))
            assertNotNull(f.typeface(ctx, clock = true)); assertNotNull(f.typeface(ctx, clock = false))
            seen.add(f.clockLayout)
        }
        assertEquals(FontOption.entries.size - 1, seen.size)
        // The bundled fonts really load (OFL files in res/font) and differ from the default.
        val paint = android.graphics.Paint().apply { textSize = 100f }
        val def = paint.apply { typeface = android.graphics.Typeface.DEFAULT }.measureText("Villars 10:08")
        val outfit = paint.apply { typeface = FontOption.OUTFIT.typeface(ctx, clock = false) }.measureText("Villars 10:08")
        assertNotEquals(def, outfit, 0.5f)
    }

    @Test
    fun clockSizeScalesTheClock() {
        val tf = FontOption.LIGHT.typeface(ctx, clock = true)
        val normal = WidgetUpdater.clockRows("00:00", 340, tf, UiOptions.ClockSize.NORMAL.factor)
        val small = WidgetUpdater.clockRows("00:00", 340, tf, UiOptions.ClockSize.SMALL.factor)
        val large = WidgetUpdater.clockRows("00:00", 340, tf, UiOptions.ClockSize.LARGE.factor)
        assertTrue(small < normal && normal < large)
        // Narrow widgets still cap the clock by width.
        assertTrue(WidgetUpdater.clockRows("00:00", 120, tf, UiOptions.ClockSize.LARGE.factor) < large)
    }
}
