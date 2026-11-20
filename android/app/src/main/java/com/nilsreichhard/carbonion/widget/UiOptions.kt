package com.nilsreichhard.carbonion.widget

import android.content.Context
import android.graphics.Typeface
import android.os.Build

/**
 * Widget-only look options (not part of the watch's Clay settings): the
 * visual style, the clock and text fonts, the clock size, a legibility
 * scrim, and the accent colour source.
 */
data class UiOptions(
    val style: Style = Style.MODERN,
    /** [FontOption.AUTO] follows the style (Modern: Light clock, System text; Classic: Condensed bold). */
    val clockFont: FontOption = FontOption.AUTO,
    val textFont: FontOption = FontOption.AUTO,
    val clockSize: ClockSize = ClockSize.NORMAL,
    /** Soft scrim behind the time block on transparent backgrounds (text always has a light shadow). */
    val scrim: Boolean = false,
    /** Accent (now line, daylight) from the wallpaper (Material You, Android 12+). */
    val wallpaperAccent: Boolean = false,
) {
    enum class Style(val key: String) { MODERN("modern"), CLASSIC("classic") }

    enum class ClockSize(val key: String, val factor: Float) { SMALL("small", 0.82f), NORMAL("normal", 1f), LARGE("large", 1.14f) }

    /** Bundled fonts can't reach the live clock (see [FontOption]), so they fall back to the style default there. */
    fun clock(): FontOption = (if (clockFont.bundled) FontOption.AUTO else clockFont).resolve(style, clock = true)
    fun text(): FontOption = textFont.resolve(style, clock = false)

    /** Compact form for prefs and the debug receiver: "style=modern,clock=light,…". */
    fun encode(): String = "style=${style.key},clock=${clockFont.key},text=${textFont.key},size=${clockSize.key}," +
        "scrim=${if (scrim) 1 else 0},accent=${if (wallpaperAccent) "wallpaper" else "watch"}"

    companion object {
        fun decode(s: String?, base: UiOptions = UiOptions()): UiOptions {
            if (s.isNullOrBlank()) return base
            var o = base
            for (kv in s.split(',')) {
                val (k, v) = kv.split('=', limit = 2).let { if (it.size == 2) it[0].trim() to it[1].trim() else return@let null } ?: continue
                o = when (k) {
                    "style" -> Style.entries.firstOrNull { it.key == v }?.let { o.copy(style = it) } ?: o
                    "clock" -> FontOption.of(v)?.let { o.copy(clockFont = it) } ?: o
                    "text" -> FontOption.of(v)?.let { o.copy(textFont = it) } ?: o
                    "size" -> ClockSize.entries.firstOrNull { it.key == v }?.let { o.copy(clockSize = it) } ?: o
                    "scrim" -> o.copy(scrim = v == "1" || v == "true")
                    "accent" -> o.copy(wallpaperAccent = v == "wallpaper")
                    else -> o
                }
            }
            return o
        }
    }
}

/**
 * A font for the live clock (TextClock) or the other text. RemoteViews can't
 * call setTypeface, so every platform family has its own small layout with a
 * fixed android:fontFamily (res/layout/clock_*.xml and text_*.xml) that
 * WidgetUpdater adds into the time block; the launcher inflates it, so the
 * clock stays a live TextClock.
 *
 * Bundled fonts (res/font, SIL OFL 1.1, licences in assets/licenses) are
 * ignored by the launcher when referenced as android:fontFamily="@font/…"
 * inside RemoteViews (verified on Pixel Launcher, API 35: it falls back to
 * Roboto), so they are offered for the other text only and drawn into the
 * chart bitmap (city, date, temperatures, alarm), refreshed at midnight.
 */
enum class FontOption(
    val key: String,
    val label: String,
    /** Platform family (Typeface.create) or null for a bundled font. */
    val family: String?,
    val bold: Boolean,
    val clockFontRes: Int,
    val textFontRes: Int,
    val clockLayout: Int,
    val textLayout: Int,
) {
    AUTO("auto", "Style default", null, false, 0, 0, 0, 0),
    SYSTEM("system", "System (Roboto)", "sans-serif", false, 0, 0, R.layout.clock_system, R.layout.text_system),
    THIN("thin", "Thin", "sans-serif-thin", false, 0, 0, R.layout.clock_thin, R.layout.text_thin),
    LIGHT("light", "Light", "sans-serif-light", false, 0, 0, R.layout.clock_light, R.layout.text_light),
    MEDIUM("medium", "Medium", "sans-serif-medium", false, 0, 0, R.layout.clock_medium, R.layout.text_medium),
    CONDENSED("condensed", "Condensed bold (watch)", "sans-serif-condensed", true, 0, 0, R.layout.clock_condensed, R.layout.text_condensed),
    CONDENSED_LIGHT("condensed_light", "Condensed light", "sans-serif-condensed-light", false, 0, 0, R.layout.clock_condensed_light, R.layout.text_condensed_light),
    SERIF("serif", "Serif", "serif", false, 0, 0, R.layout.clock_serif, R.layout.text_serif),
    MONO("mono", "Monospace", "monospace", false, 0, 0, R.layout.clock_mono, R.layout.text_mono),
    ROUNDED("rounded", "Rounded (Varela Round, text)", null, false, R.font.varela_round, R.font.varela_round, R.layout.clock_rounded, R.layout.text_rounded),
    OUTFIT("outfit", "Outfit (text)", null, false, R.font.outfit_light, R.font.outfit_regular, R.layout.clock_outfit, R.layout.text_outfit),
    INTER("inter", "Inter (text)", null, false, R.font.inter_regular, R.font.inter_regular, R.layout.clock_inter, R.layout.text_inter);

    val bundled get() = family == null && this != AUTO
    /** Usable on the live clock (platform families only). */
    val clockCapable get() = !bundled

    fun resolve(style: UiOptions.Style, clock: Boolean): FontOption = if (this != AUTO) this else when (style) {
        UiOptions.Style.MODERN -> if (clock) LIGHT else SYSTEM
        UiOptions.Style.CLASSIC -> if (clock) CONDENSED else SYSTEM
    }

    /** Typeface for measuring and for text drawn into the bitmap; the condensed text option is regular weight. */
    fun typeface(ctx: Context, clock: Boolean): Typeface {
        val res = if (clock) clockFontRes else textFontRes
        if (res != 0) return runCatching { ctx.resources.getFont(res) }.getOrNull() ?: Typeface.DEFAULT
        val fam = family ?: "sans-serif"
        val b = bold && (clock || this != CONDENSED)
        return Typeface.create(fam, if (b) Typeface.BOLD else Typeface.NORMAL)
    }

    companion object {
        fun of(key: String): FontOption? = entries.firstOrNull { it.key == key }
        /** Wallpaper colours need Android 12 (API 31). */
        val dynamicColorAvailable get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
}
