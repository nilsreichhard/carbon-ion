package com.nilsreichhard.carbonion.widget

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import org.json.JSONObject
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * QuickJS running the watchface's own PebbleKit JS modules (bundled into the
 * asset carbon-weather.js by tools/bundle-pkjs.js), with the host functions
 * documented in tools/widget-entry.js. localStorage is a SharedPreferences
 * file, so Clay settings, API keys and caches use the watch's keys.
 * HTTP is synchronous: never use this on the main thread for fetches.
 */
class JsRuntime(context: Context) : Closeable {

    fun interface Http { fun get(url: String, headers: Map<String, String>): HttpResult }
    data class HttpResult(val status: Int, val body: String = "", val headers: Map<String, String> = emptyMap(), val error: String? = null)

    companion object {
        private const val TAG = "CarbonJs"
        const val ASSET = "carbon-weather.js"
        const val STORE_PREFS = "carbon_js_store"
        /** Loads the native QuickJS library; JVM tests swap in a desktop build. */
        @Volatile var nativeLoader: () -> Unit = { QuickJSLoader.init() }
        /** HTTP backend; JVM tests replace it with recorded responses. */
        @Volatile var http: Http = Http { url, headers -> urlConnectionGet(url, headers) }
        /** Bundle source override (JVM tests read it from the build dir). */
        @Volatile var scriptOverride: String? = null
        @Volatile private var loaded = false

        fun store(ctx: Context): SharedPreferences = ctx.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE)

        /** Largest response body read (the biggest provider answer is ~200 kB). */
        const val MAX_BODY_BYTES = 4 * 1024 * 1024
        /** QuickJS heap cap: the bundle and a forecast need a few MB. */
        const val MEMORY_LIMIT = 64 * 1024 * 1024

        /** Only HTTPS leaves the device (the network security config also blocks cleartext). */
        fun allowedUrl(url: String): Boolean = runCatching { URL(url).protocol == "https" && URL(url).host.isNotEmpty() }.getOrDefault(false)

        fun urlConnectionGet(url: String, headers: Map<String, String>): HttpResult = try {
            require(allowedUrl(url)) { "only https URLs are fetched" }
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true // HttpURLConnection never follows https → http
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val status = conn.responseCode
            val body = (if (status >= 400) conn.errorStream else conn.inputStream)?.use { readCapped(it) } ?: ""
            val hs = conn.headerFields.filterKeys { it != null }.mapNotNull { (k, v) -> v.firstOrNull()?.let { k!!.lowercase() to it } }.toMap()
            conn.disconnect()
            HttpResult(status, body, hs)
        } catch (e: Exception) {
            HttpResult(0, error = "${e.javaClass.simpleName}: ${e.message}")
        }

        /** Reads at most [MAX_BODY_BYTES] (UTF-8); a longer body is an error, not a truncated document. */
        fun readCapped(input: java.io.InputStream): String {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf); if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BODY_BYTES) throw java.io.IOException("response larger than $MAX_BODY_BYTES bytes")
            }
            return out.toString("UTF-8")
        }

        /** JS log lines can hold request URLs (API keys, coordinates): debug builds only. */
        var logEnabled = BuildConfig.DEBUG
    }

    private val js: QuickJSContext

    init {
        synchronized(JsRuntime::class.java) { if (!loaded) { nativeLoader(); loaded = true } }
        val store = store(context)
        val script = scriptOverride ?: context.assets.open(ASSET).bufferedReader().use { it.readText() }
        js = QuickJSContext.create()
        js.setMemoryLimit(MEMORY_LIMIT)
        val g = js.globalObject
        g.setProperty("__carbonHttpGet", JSCallFunction { args ->
            val url = args.getOrNull(0) as? String
            val hs = runCatching { JSONObject((args.getOrNull(1) as? String) ?: "{}") }.getOrDefault(JSONObject())
            val r = if (url == null) HttpResult(0, error = "no url")
                else http.get(url, hs.keys().asSequence().associateWith { hs.optString(it) })
            JSONObject().put("status", r.status).put("body", r.body).put("headers", JSONObject(r.headers))
                .apply { if (r.error != null) put("error", r.error) }.toString()
        })
        g.setProperty("__carbonStoreGet", JSCallFunction { args -> (args.getOrNull(0) as? String)?.let { store.getString(it, null) } })
        g.setProperty("__carbonStoreSet", JSCallFunction { args ->
            val k = args.getOrNull(0) as? String
            val v = args.getOrNull(1)
            if (k != null) store.edit().apply { if (v == null) remove(k) else putString(k, v.toString()) }.commit()
            null
        })
        g.setProperty("__carbonLog", JSCallFunction { args -> if (logEnabled) Log.i(TAG, "${args.getOrNull(0)} ${args.getOrNull(1)}"); null })
        g.setProperty("__carbonLocale", JSCallFunction { Locale.getDefault().toLanguageTag() })
        js.evaluate(script, ASSET)
    }

    /** Calls a global JS function with string arguments; returns its string result. */
    fun call(fn: String, vararg args: String): String {
        val g = js.globalObject
        args.forEachIndexed { i, a -> g.setProperty("__arg$i", a) }
        val list = args.indices.joinToString(",") { "__arg$it" }
        return js.evaluate("$fn($list)") as? String ?: throw IllegalStateException("$fn returned no string")
    }

    override fun close() = js.destroy()
}
