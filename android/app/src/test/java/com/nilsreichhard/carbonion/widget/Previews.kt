package com.nilsreichhard.carbonion.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import java.io.File

/** Shared helpers for the JVM preview tests (PNG output, sample wallpapers). */
object Previews {
    val outDir: File get() = File("build/outputs/preview").apply { mkdirs() }

    fun save(b: Bitmap, name: String): File = File(outDir, "$name.png").also { f -> f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }

    fun payloadJson(): String {
        val override = System.getProperty("carbon.payload")
        return if (!override.isNullOrEmpty()) File(override).readText()
        else javaClass.classLoader!!.getResource("villars_payload.json")!!.readText()
    }

    /** Pixel-ish sample wallpapers: dark (navy → violet, glow) and light. */
    fun wallpaper(w: Int, h: Int, dark: Boolean): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = if (dark) LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF0B1026.toInt(), 0xFF3A1F5C.toInt(), Shader.TileMode.CLAMP)
        else LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFFE9EEF6.toInt(), 0xFFF7E8D8.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(w * 0.75f, h * 0.3f, w * 0.45f,
            if (dark) 0x553F7FBF else 0x55FFFFFF, 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        return b
    }

    /** Nearest-neighbour zoom of a region (to inspect pixels). */
    fun zoom(src: Bitmap, x: Int, y: Int, w: Int, h: Int, k: Int): Bitmap =
        Bitmap.createScaledBitmap(Bitmap.createBitmap(src, x, y, w, h), w * k, h * k, false)
}
