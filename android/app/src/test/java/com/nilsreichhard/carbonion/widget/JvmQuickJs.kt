package com.nilsreichhard.carbonion.widget

import java.io.File

/**
 * Desktop build of the same quickjs-wrapper (tag 3.2.3) JNI library, so the
 * bundled watch JS runs in JVM tests. Build it once (see README):
 * ~/Library/Caches/carbon-quickjs/libquickjs-java-wrapper.dylib, or point
 * -Dcarbon.quickjs=/path/to/lib at another build.
 */
object JvmQuickJs {
    private val lib: File get() = File(System.getProperty("carbon.quickjs").takeUnless { it.isNullOrEmpty() }
        ?: (System.getProperty("user.home")!! + "/Library/Caches/carbon-quickjs/libquickjs-java-wrapper.dylib"))

    /** Recorded HTTP responses by host (src/test/resources/fixtures). */
    val fixtures = JsRuntime.Http { url, _ ->
        val host = java.net.URI(url).host
        val past = Regex("past_hours=12&forecast_hours=1\\b").containsMatchIn(url)
        val res = javaClass.classLoader!!.getResource("fixtures/$host${if (past) ".past" else ""}.json")
            ?: javaClass.classLoader!!.getResource("fixtures/$host.json")
        if (res == null) JsRuntime.HttpResult(404, "{}") else JsRuntime.HttpResult(200, res.readText(), mapOf("content-type" to "application/json"))
    }

    fun install(): Boolean {
        if (!lib.isFile) return false
        val src = lib
        // Each Robolectric sandbox has its own classloader and the JVM binds a
        // native library to one loader, so every sandbox loads its own copy.
        JsRuntime.nativeLoader = {
            val copy = File.createTempFile("quickjs-", ".dylib").apply { deleteOnExit() }
            src.copyTo(copy, overwrite = true)
            System.load(copy.absolutePath)
        }
        JsRuntime.http = fixtures
        return true
    }

    fun reset() {
        JsRuntime.http = JsRuntime.Http { u, h -> JsRuntime.urlConnectionGet(u, h) }
    }
}
