package com.nilsreichhard.carbonion.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Runs each [SampleScenarios] response through the bundled watch JS and
 * writes the results to build/outputs/samples/payloads/ for the emulator
 * gallery (DebugSamplesReceiver). Also checks the scenarios produce the
 * events they are meant to show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SampleFixturesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() { JvmQuickJs.require() }
    @After fun tearDown() = JvmQuickJs.reset()

    @Test
    fun scenariosRunThroughTheWatchPipeline() {
        val out = File("build/outputs/samples/payloads").apply { mkdirs() }
        val nowSec = System.getProperty("carbon.sampleNow")?.toLongOrNull() ?: (System.currentTimeMillis() / 1000)
        // Celsius like Nils's watch (the JVM locale would pick Fahrenheit).
        JsRuntime.store(ctx).edit().putString("clay-settings", JSONObject().put("SETTING_TEMP_UNIT", "0").toString()).commit()
        val results = SampleScenarios.names.associateWith { name ->
            JsRuntime.http = JsRuntime.Http { url, h ->
                if (java.net.URI(url).host == "api.open-meteo.com")
                    JsRuntime.HttpResult(200, SampleScenarios.response(name, nowSec), mapOf("content-type" to "application/json"))
                else JvmQuickJs.fixtures.get(url, h)
            }
            JsRuntime(ctx).use { js ->
                val host = JSONObject().put("lat", 46.30).put("lon", 7.05).put("isStatic", false)
                val r = JSONObject(js.call("carbonFetchWeather", host.toString()))
                assertTrue("$name: ${r.toString().take(300)}", r.has("payload"))
                r.put("city", "Villars-sur-Ollon")
                File(out, "$name.json").writeText(r.toString())
                WeatherPayload.fromJson(r.getJSONObject("payload").toString())
            }
        }
        assertTrue("thunder has storm bits", results.getValue("thunder").storm.any { it })
        assertTrue("thunder has wind bits", results.getValue("thunder").wind.any { it })
        assertTrue("fog has fog bits", results.getValue("fog").fog.any { it })
        assertTrue("snow has snow codes", results.getValue("snow").weatherCode.any { it in intArrayOf(71, 73, 75, 85) })
        assertTrue("sunny has radiation", results.getValue("sunny").shortwaveWm2.any { it > 300 })
        assertTrue("celsius", results.getValue("snow").tempHourly.all { it < 5 })
        val tz = java.util.TimeZone.getTimeZone("Europe/Zurich")
        fun kinds(name: String): Set<WidgetRenderer.EventKind> {
            val p = results.getValue(name)
            val s = WidgetSettings.fromDict(JSONObject(File(out, "$name.json").readText()).getJSONObject("settings").toString())
            val w = WidgetRenderer.Window.of(p, s, nowSec * 1000, tz)
            return WidgetRenderer.eventSpans(p, s, w.startSlot, w.hoursRemaining).map { it.kind }.toSet()
        }
        assertTrue("fog spans ${kinds("fog")}", WidgetRenderer.EventKind.FOG in kinds("fog"))
        assertTrue("thunder spans ${kinds("thunder")}", kinds("thunder").containsAll(listOf(WidgetRenderer.EventKind.STORM, WidgetRenderer.EventKind.WIND)))
        assertTrue("snow spans ${kinds("snow")}", WidgetRenderer.EventKind.SNOW in kinds("snow"))
    }
}
