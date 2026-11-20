package com.nilsreichhard.carbonion.widget

import android.content.Context
import android.util.DisplayMetrics
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** 1.0.1: Follow-system theme, short-weekday date format, inline Allow location, toolbar-only actions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeDateLocationTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun prefs() = ctx.getSharedPreferences(WidgetStore.PREFS, Context.MODE_PRIVATE)
    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    @After fun tearDown() = JvmQuickJs.reset()

    @Test
    fun themeModeFromDict() {
        assertEquals(WidgetSettings.THEME_SYSTEM, WidgetSettings.fromDict(null).themeMode)
        assertEquals(WidgetSettings.THEME_SYSTEM, WidgetSettings.fromDict("{}").themeMode)
        assertEquals(WidgetSettings.THEME_DARK, WidgetSettings.fromDict("{\"SETTING_LIGHT_THEME\":0}").themeMode)
        assertFalse(WidgetSettings.fromDict("{\"SETTING_LIGHT_THEME\":0}").lightTheme)
        assertEquals(WidgetSettings.THEME_LIGHT, WidgetSettings.fromDict("{\"SETTING_LIGHT_THEME\":1}").themeMode)
        assertEquals(WidgetSettings.THEME_SYSTEM, WidgetSettings.fromDict("{\"SETTING_LIGHT_THEME\":2}").themeMode)
    }

    @Test
    fun systemThemeFollowsPhoneAndOldLightDefaultMigrates() {
        prefs().edit().clear().putString("dict", "{\"SETTING_LIGHT_THEME\":2}").commit()
        RuntimeEnvironment.setQualifiers("+notnight")
        assertTrue(WidgetStore.settings(ctx).lightTheme)
        RuntimeEnvironment.setQualifiers("+night")
        assertTrue(WidgetStore.systemDark())
        assertFalse(WidgetStore.settings(ctx).lightTheme)
        // 1.0.0's seeded Light counts as Follow system until the user saves settings.
        prefs().edit().putString("dict", "{\"SETTING_LIGHT_THEME\":1}").commit()
        assertEquals(WidgetSettings.THEME_SYSTEM, WidgetStore.settings(ctx).themeMode)
        WidgetStore.markThemeChosen(ctx)
        assertEquals(WidgetSettings.THEME_LIGHT, WidgetStore.settings(ctx).themeMode)
        assertTrue(WidgetStore.settings(ctx).lightTheme)
        prefs().edit().putString("dict", "{\"SETTING_LIGHT_THEME\":0}").commit()
        assertFalse(WidgetStore.settings(ctx).lightTheme)
        assertEquals(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, WidgetStore.nightMode(WidgetSettings(themeMode = 2)))
    }

    @Test
    fun followSystemRendersBothLooksOnTwelvePlus() {
        prefs().edit().clear().putString("dict", "{\"SETTING_LIGHT_THEME\":2}").putString("payload", Previews.payloadJson()).commit()
        assertTrue(WidgetUpdater.dualTheme(ctx, WidgetStore.settings(ctx)))
        val rv = WidgetUpdater.build(ctx, WidgetUpdater.Size(360, 344))
        val v = rv.apply(ctx, android.widget.FrameLayout(ctx))
        val chart = v.findViewById<android.widget.ImageView>(R.id.chart)
        assertTrue("chart image set", chart.drawable != null)
        prefs().edit().putString("dict", "{\"SETTING_LIGHT_THEME\":0}").commit()
        WidgetStore.markThemeChosen(ctx)
        assertFalse(WidgetUpdater.dualTheme(ctx, WidgetStore.settings(ctx)))
    }

    @Test
    fun sizeBudgetKeepsOneSizeForTwoLooks() {
        val dm = DisplayMetrics().apply { widthPixels = 1080; heightPixels = 2400; density = 2.625f }
        val sizes = listOf(SizeF(390f, 380f), SizeF(780f, 190f))
        assertEquals(2, WidgetUpdater.fitSizes(sizes, 1, dm).size)
        assertEquals(1, WidgetUpdater.fitSizes(sizes, 2, dm).size)
        assertEquals(1, WidgetUpdater.fitSizes(sizes.take(1), 2, DisplayMetrics().apply { widthPixels = 100; heightPixels = 100; density = 1f }).size)
    }

    @Test
    fun shortWeekdayDateFollowsLocale() {
        assertEquals("EEE, MMM d", WidgetUpdater.localDatePattern(WidgetUpdater.SHORT_WEEKDAY_FORMAT, Locale.US))
        assertEquals("EEE, d MMM", WidgetUpdater.localDatePattern(WidgetUpdater.SHORT_WEEKDAY_FORMAT, Locale.UK)) // day before month
        assertTrue(WidgetUpdater.localDatePattern(WidgetUpdater.SHORT_WEEKDAY_FORMAT, Locale.GERMANY).contains("EEE"))
        assertFalse(WidgetUpdater.localDatePattern(WidgetUpdater.SHORT_WEEKDAY_FORMAT, Locale.US).contains("EEEE"))
        // The watch's formats are unchanged.
        assertEquals("EEEE, MMM d", WidgetUpdater.localDatePattern("%A, %b %d", Locale.US))
    }

    @Test
    fun settingsPageHasSystemThemeShortDateAndNoBottomSave() {
        assumeTrue("desktop QuickJS library missing", JvmQuickJs.install())
        prefs().edit().clear().commit()
        val ctl = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val act = ctl.get()
        val views = all(act.findViewById<ViewGroup>(android.R.id.content))
        val spinners = views.filterIsInstance<Spinner>()
        val labels = spinners.map { sp -> (0 until (sp.adapter?.count ?: 0)).map { sp.adapter.getItem(it).toString() } }
        val theme = spinners[labels.indexOfFirst { it.contains("Follow system (auto)") }]
        assertEquals("Follow system (auto)", theme.selectedItem.toString())
        val dates = labels.first { l -> l.any { it.matches(Regex("^\\w{3,}day, .*")) } }
        assertTrue("short weekday option: $dates", dates.any { it.matches(Regex("^[A-Z][a-z]{2}, [A-Z][a-z]{2} \\d{1,2}$")) })
        assertTrue("no Save button at the bottom", views.filterIsInstance<Button>().none { it.isShown && it.text.toString().equals("Save", true) })
        val menu = androidx.appcompat.view.menu.MenuBuilder(act).also { act.onCreateOptionsMenu(it) }
        assertTrue("Save in the action bar", (0 until menu.size()).any { menu.getItem(it).title.toString() == "Save" })
        ctl.pause().stop().destroy()
    }

    @Test
    fun detailShowsInlineAllowAndNoBottomButtons() {
        prefs().edit().clear().putString("payload", Previews.payloadJson()).putString("place_source", LocationHelper.VILLARS.source).commit()
        val ctl = Robolectric.buildActivity(DetailActivity::class.java).setup()
        val act = ctl.get()
        val views = all(act.findViewById<ViewGroup>(android.R.id.content))
        val texts = views.filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
        assertTrue(texts.any { it.startsWith("Location unavailable") })
        assertTrue("inline Allow location", views.filterIsInstance<Button>().any { it.isShown && it.text.toString().equals("Allow location", true) })
        assertTrue("no Refresh/Settings buttons in the content",
            views.filterIsInstance<Button>().none { it.isShown && (it.text.toString().equals("Refresh", true) || it.text.toString().equals("Settings", true)) })
        ctl.pause().stop().destroy()
    }
}
