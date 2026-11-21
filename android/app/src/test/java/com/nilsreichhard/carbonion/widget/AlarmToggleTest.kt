package com.nilsreichhard.carbonion.widget

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 1.1.3: "Show next alarm" (widget + app) and "Send next alarm to watch", next to the date format. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlarmToggleTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @After fun tearDown() = JvmQuickJs.reset()

    @Test
    fun settingsDefaultsAndParsing() {
        assertTrue(WidgetSettings().showAlarm); assertTrue(WidgetSettings().alarmToWatch)
        assertTrue(WidgetSettings.fromDict("{}").showAlarm)
        assertFalse(WidgetSettings.fromDict("{\"SETTING_PHONE_ALARM\":0}").showAlarm)
        assertFalse(WidgetSettings.fromDict("{\"SETTING_ANDROID_ALARM_WATCH\":0}").alarmToWatch)
        assertTrue(WidgetSettings.fromDict("{\"SETTING_ANDROID_ALARM_WATCH\":0}").showAlarm)
    }

    @Test
    fun widgetHidesTheAlarmWhenOff() {
        WidgetStore.saveWeather(ctx, Previews.payloadJson(), "Villars-sur-Ollon", JSONObject().toString())
        fun shown(dict: String): Boolean {
            WidgetStore.saveDict(ctx, dict)
            val v = WidgetUpdater.build(ctx, WidgetUpdater.Size(360, 344), 7, "07:00", useAlarmOverride = true).apply(ctx, FrameLayout(ctx))
            return v.findViewById<View>(R.id.date_alarm).visibility == View.VISIBLE
        }
        assertTrue(shown("{}"))
        assertTrue(shown("{\"SETTING_PHONE_ALARM\":1,\"SETTING_ANDROID_ALARM_WATCH\":0}"))
        assertFalse(shown("{\"SETTING_PHONE_ALARM\":0}"))
    }

    @Test
    fun settingsPageShowsBothTogglesAfterTheDateFormat() {
        JvmQuickJs.require()
        JsRuntime.store(ctx).edit().clear().commit()
        JsRuntime(ctx).use { js ->
            val items = JSONArray(js.call("carbonSettingsInit", "{}")).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            val keys = items.map { it.optString("key") }
            val at = keys.indexOf("SETTING_DATE_FORMAT")
            assertEquals("SETTING_PHONE_ALARM", keys[at + 1])
            assertEquals("SETTING_ANDROID_ALARM_WATCH", keys[at + 2])
            assertEquals(1, keys.count { it == "SETTING_PHONE_ALARM" })
            val show = items[at + 1]; val send = items[at + 2]
            assertEquals("Show next alarm", show.getString("label")); assertEquals("toggle", show.getString("type"))
            assertEquals("Send next alarm to watch", send.getString("label"))
            assertTrue(show.getBoolean("value")); assertTrue(send.getBoolean("value"))
            assertFalse(show.getBoolean("hidden")); assertFalse(send.getBoolean("hidden"))
            val d = JSONObject(js.call("carbonSettingsDict"))
            assertEquals(1, d.getInt("SETTING_PHONE_ALARM")); assertEquals(1, d.getInt("SETTING_ANDROID_ALARM_WATCH"))
            js.call("carbonSettingsChange", "SETTING_PHONE_ALARM", "false")
            js.call("carbonSettingsChange", "SETTING_ANDROID_ALARM_WATCH", "false")
            val saved = JSONObject(js.call("carbonSettingsSave", "{}"))
            assertEquals(0, saved.getInt("SETTING_PHONE_ALARM")); assertEquals(0, saved.getInt("SETTING_ANDROID_ALARM_WATCH"))
            val s = WidgetSettings.fromDict(saved.toString())
            assertFalse(s.showAlarm); assertFalse(s.alarmToWatch)
        }
    }
}
