package com.nilsreichhard.carbonion.widget

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Foreground for a solid widget background. With a colour background the text, labels and
 * ticks follow the background's luminance instead of the Theme setting (meant for the
 * wallpaper), so dark text never lands on navy or light text on cream. Transparent and
 * mostly see-through backgrounds keep the Theme behaviour.
 */
object WidgetColors {
    /** Wallpaper assumed behind a see-through colour: the one the Theme setting was chosen for. */
    private const val WALL_LIGHT = 0xFFFFFFFF.toInt()
    private const val WALL_DARK = 0xFF000000.toInt()

    /** [bg] at its opacity over the wallpaper the theme expects (white for Light, black for Dark). */
    fun blended(bg: WidgetStore.Background, themeLight: Boolean): Int {
        val a = bg.opacity.coerceIn(0, 100) / 100f
        val w = if (themeLight) WALL_LIGHT else WALL_DARK
        fun ch(sh: Int) = ((bg.color shr sh and 255) * a + (w shr sh and 255) * (1 - a)).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** WCAG 2 relative luminance of an RGB colour (alpha ignored). */
    fun luminance(c: Int): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(c shr 16 and 255) + 0.7152 * ch(c shr 8 and 255) + 0.0722 * ch(c and 255)
    }

    /** WCAG 2 contrast ratio, 1..21. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Main text colour of each theme (clock, city, date, current temperature). */
    private fun primaryInk(light: Boolean, modern: Boolean): Int =
        if (modern) ModernRenderer.Palette(light, null).ink else CarbonStyle.Palette(light).ink

    /**
     * Light (dark ink) or dark (light ink) palette for [bg]: the theme's own choice when the
     * background is transparent, else whichever ink contrasts more with the colour blended by
     * its opacity over the wallpaper the theme expects.
     */
    fun effectiveLight(bg: WidgetStore.Background, themeLight: Boolean, modern: Boolean = true): Boolean {
        if (bg.mode != WidgetStore.BG_COLOR) return themeLight
        val c = blended(bg, themeLight)
        val dark = contrast(primaryInk(true, modern), c)   // dark ink (light theme)
        val light = contrast(primaryInk(false, modern), c) // light ink (dark theme)
        return if (dark == light) themeLight else dark > light
    }
}
