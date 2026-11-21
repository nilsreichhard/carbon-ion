package com.nilsreichhard.carbonion.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.LayerDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 1.1.2: dropdowns and text inputs look like fields in light and dark. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FieldStyleTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
    @After fun tearDown() = JvmQuickJs.reset()

    private fun style(night: Boolean): FieldStyle {
        RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
        return FieldStyle.forTheme(ContextThemeWrapper(app, R.style.Theme_Carbon_Settings))
    }

    @Test
    fun contrastAndDistinctSurfacesInLightAndDark() {
        for (night in listOf(false, true)) {
            val s = style(night)
            val n = if (night) "dark" else "light"
            assertTrue("$n text on field ${FieldStyle.contrast(s.text, s.fill)}", FieldStyle.contrast(s.text, s.fill) >= 4.5)
            assertTrue("$n text on popup", FieldStyle.contrast(s.text, s.popup) >= 4.5)
            assertTrue("$n text on selected", FieldStyle.contrast(s.text, s.selected) >= 4.5)
            assertTrue("$n hint on field", FieldStyle.contrast(s.hint, s.fill) >= 4.5)
            assertTrue("$n accent outline/chevron visible on page", FieldStyle.contrast(s.accent, s.page) >= 3.0)
            assertTrue("$n field differs from page", FieldStyle.contrast(s.fill, s.page) > 1.05)
            assertTrue("$n popup differs from page", FieldStyle.contrast(s.popup, s.page) > 1.1)
            assertTrue("$n popup outline", FieldStyle.contrast(s.popupOutline, s.popup) >= 2.0)
            assertTrue("$n selected row differs from popup", FieldStyle.contrast(s.selected, s.popup) > 1.1)
        }
    }

    @Test
    fun everyDropdownAndInputOnTheSettingsPageIsAField() {
        JvmQuickJs.require()
        for (night in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
            app.getSharedPreferences(WidgetStore.PREFS, Context.MODE_PRIVATE).edit().clear()
                .putString("dict", "{\"SETTING_LIGHT_THEME\":2}").commit()
            val ctl = Robolectric.buildActivity(SettingsActivity::class.java).setup()
            val act = ctl.get()
            val content = (act.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup).getChildAt(0)
            val views = all(content)
            val spinners = views.filterIsInstance<Spinner>()
            val inputs = views.filterIsInstance<EditText>()
            assertTrue(spinners.size > 10)
            assertTrue(inputs.isNotEmpty())
            val minPx = 48 * act.resources.displayMetrics.density
            for (sp in spinners) {
                assertEquals(FieldStyle.TAG, sp.tag)
                assertTrue(sp.background is LayerDrawable)
                assertTrue("touch height", sp.minimumHeight >= minPx)
                val closed = sp.adapter.getView(sp.selectedItemPosition.coerceAtLeast(0), null, sp) as TextView
                val drop = sp.adapter.getDropDownView(sp.selectedItemPosition.coerceAtLeast(0), null, sp) as TextView
                assertTrue("list row height", drop.minHeight >= minPx)
                assertTrue("selected row checked", drop.isSelected && drop.compoundDrawablesRelative[2] != null)
                assertEquals(closed.currentTextColor, drop.currentTextColor)
            }
            for (et in inputs) { assertEquals(FieldStyle.TAG, et.tag); assertTrue(et.minimumHeight >= minPx) }
            val w = (400 * act.resources.displayMetrics.density).toInt()
            content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            content.layout(0, 0, w, content.measuredHeight)
            val b = Bitmap.createBitmap(w, content.measuredHeight, Bitmap.Config.ARGB_8888)
            Canvas(b).also { it.drawColor(FieldStyle.forTheme(act).page); content.draw(it) }
            Previews.save(b, "settings-fields-${if (night) "dark" else "light"}")
            ctl.pause().stop().destroy()
        }
    }
}
