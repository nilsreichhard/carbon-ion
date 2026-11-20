package com.nilsreichhard.carbonion.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * High / current / low temperature labels at the left of the chart, stacked
 * optically: by their visible glyph ink (rasterised and scanned, since
 * Paint.getTextBounds pads by up to ~2 px depending on the glyphs), not their
 * text boxes, so the two gaps are equal and the current reading sits exactly
 * midway between the others. All three are right-aligned on the ink of their
 * digits at [right]. Shared by the Classic and Modern renderers.
 */
object TempLabels {
    class Placed(val text: String, val x: Float, val baseline: Float, val inkTop: Float, val inkBottom: Float, val inkRight: Float)

    /** Coverage counted as visible ink (matches what reads as the glyph edge). */
    const val INK_ALPHA = 96

    private val cache = object : LinkedHashMap<String, RectF>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, RectF>?) = size > 64
    }

    /** Visible ink of [s] relative to its drawing origin (left-aligned, baseline at y = 0). */
    fun ink(s: String, p: Paint): RectF {
        val key = "$s|${p.typeface?.hashCode()}|${p.textSize}|${p.isFakeBoldText}"
        synchronized(cache) { cache[key]?.let { return RectF(it) } }
        val pad = ceil(p.textSize * 0.5f).toInt() + 2
        val w = ceil(p.measureText(s)).toInt() + 2 * pad
        val h = ceil(p.textSize * 2f).toInt() + 2 * pad
        val base = pad + p.textSize * 1.4f
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val q = Paint(p).apply { color = Color.BLACK; textAlign = Paint.Align.LEFT; clearShadowLayer() }
        Canvas(bmp).drawText(s, pad.toFloat(), base, q)
        var l = w; var r = -1; var t = h; var b = -1
        val px = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(px, 0, w, 0, y, w, 1)
            for (x in 0 until w) if ((px[x] ushr 24) >= INK_ALPHA) {
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
            }
        }
        bmp.recycle()
        val out = if (r < 0) RectF(0f, 0f, 0f, 0f) else RectF(l - pad.toFloat(), t - base, r + 1f - pad, b + 1f - base)
        synchronized(cache) { cache[key] = RectF(out) }
        return out
    }

    /** Ink padding under the chart's top edge and above the hour ticks, as a fraction of the chart height. */
    const val PAD_FRAC = 0.04f

    /** Ink padding for a chart [lh] tall: [PAD_FRAC] of it, but at least [minPad]. */
    fun pad(lh: Float, minPad: Float): Float = kotlin.math.max(lh * PAD_FRAC, minPad)

    /** Widest ink of [texts] (right-aligned at a common edge, so this is the label column's width). */
    fun columnWidth(texts: List<String>, paints: List<Paint>): Float = texts.indices.maxOf { ink(texts[it], paints[it]).width() }

    /**
     * Bottom of the label band for a chart [lh] tall: the top of the tallest hour tick
     * ([tickXs], [tickLens]) under the label column [left]..[right], so the low label sits
     * just above the ticks it would otherwise touch and uses the rest of the height.
     */
    fun bandBottom(lh: Float, tickXs: List<Float>, tickLens: List<Float>, left: Float, right: Float): Float =
        lh - (tickXs.indices.filter { tickXs[it] in left..right }.maxOfOrNull { tickLens[it] } ?: 0f)

    /**
     * Spreads the labels over the band [top, bottom]: high's ink starts [pad]
     * below [top], low's ink ends [pad] above [bottom], and current sits in
     * between with both ink gaps equal (so it is exactly centred). If the band
     * is too short for [minGap] gaps the stack is centred in it instead.
     *
     * @param texts high, current, low (digits only; a trailing ° is drawn by the caller after the digits)
     * @param snap whole-pixel positions (Classic, pixel-faithful); an odd spare pixel goes to the top padding
     */
    fun place(texts: List<String>, paints: List<Paint>, right: Float, top: Float, bottom: Float,
              pad: Float, minGap: Float, snap: Boolean = false): List<Placed> {
        val b = texts.indices.map { ink(texts[it], paints[it]) }
        val inkH = b.sumOf { it.height().toDouble() }.toFloat()
        val avail = bottom - top - 2 * pad
        val spare = avail - inkH
        var gap = spare / 2f
        var y = top + pad
        if (gap < minGap) { gap = minGap; y = top + (bottom - top - inkH - 2 * gap) / 2f }
        if (snap) {
            val g = floor(gap); y = floor(y + 0.5f) + if (gap >= minGap) floor((gap - g) * 2f + 0.001f) else 0f; gap = g
        }
        return texts.indices.map { i ->
            val r = b[i]
            val x = if (snap) (right - r.right).roundToInt().toFloat() else right - r.right
            val p = Placed(texts[i], x, y - r.top, y, y + r.height(), x + r.right)
            y += r.height() + gap
            p
        }
    }
}
