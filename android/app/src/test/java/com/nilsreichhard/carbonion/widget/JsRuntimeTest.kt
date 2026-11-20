package com.nilsreichhard.carbonion.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The bundled watch JS in QuickJS on the JVM, against recorded responses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE) // same sandbox as SettingsScreenTest: one JNI load per JVM
class JsRuntimeTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() { assumeTrue("desktop QuickJS library missing", JvmQuickJs.install()) }
    @After fun tearDown() = JvmQuickJs.reset()

    @Test
    fun fetchesThroughTheWatchPipeline() {
        JsRuntime(ctx).use { js ->
            assertEquals("null", js.call("carbonLocation").ifEmpty { "null" })
            val host = JSONObject().put("lat", 46.30).put("lon", 7.05).put("isStatic", false)
            val out = JSONObject(js.call("carbonFetchWeather", host.toString()))
            assertTrue(out.toString().take(300), out.has("payload"))
            assertEquals("Ollon", out.getString("city"))
            val p = WeatherPayload.fromJson(out.getJSONObject("payload").toString())
            assertEquals("openmeteo", p.provider.lowercase())
            assertEquals(72, p.tempHourly.size)
            val s = WidgetSettings.fromDict(out.getJSONObject("settings").toString())
            assertTrue("defaults are the light theme like Clay", s.lightTheme)
        }
    }

    @Test
    fun settingsPageMirrorsClay() {
        JsRuntime(ctx).use { js ->
            val page = JSONArray(js.call("carbonSettingsInit", "{}"))
            val keys = (0 until page.length()).mapNotNull { page.getJSONObject(it).optString("key").ifEmpty { null } }
            assertTrue("SETTING_CLOUD_DISPLAY_MODE" in keys)
            assertTrue("SETTING_RAIN_HEIGHT" in keys)
            assertTrue("SETTING_WRIST_FLICK" !in keys)
            // Provider without cloud thickness data greys the option (custom.js).
            fun item(arr: JSONArray, k: String) = (0 until arr.length()).map { arr.getJSONObject(it) }.first { it.optString("key") == k }
            val providers = item(page, "SETTING_WEATHER_PROVIDER").getJSONArray("options").let { o -> (0 until o.length()).map { o.getJSONObject(it).getString("value") } }
            val greyed = providers.associateWith { pv ->
                item(JSONArray(js.call("carbonSettingsChange", "SETTING_WEATHER_PROVIDER", JSONObject.quote(pv))), "SETTING_CLOUD_THICK").optBoolean("disabled")
            }
            assertTrue("open-meteo has thickness", greyed["openmeteo"] == false)
            assertTrue("some provider greys thickness: $greyed", greyed.values.any { it })
            js.call("carbonSettingsChange", "SETTING_WEATHER_PROVIDER", JSONObject.quote("openmeteo"))
            val dict = JSONObject(js.call("carbonSettingsSave"))
            assertTrue(dict.has("SETTING_LIGHT_THEME"))
        }
    }
}
