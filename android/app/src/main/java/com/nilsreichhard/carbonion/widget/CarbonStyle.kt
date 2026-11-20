package com.nilsreichhard.carbonion.widget

/**
 * Shared look of the widget: colours, thresholds and spacing, each mirroring
 * the watchface C code (paths relative to the repo root). When the watch look
 * changes, change it here too; nothing else in the widget hard-codes these.
 *
 * Pebble's 64-colour palette uses 2-bit channels (00/55/AA/FF), so every
 * GColorX below is its exact sRGB value.
 */
object CarbonStyle {
    // ---- Pebble palette (pebble.h GColor*) ----
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val LIGHT_GRAY = 0xFFAAAAAA.toInt()        // GColorLightGray
    const val DARK_GRAY = 0xFF555555.toInt()         // GColorDarkGray
    const val RED = 0xFFFF0000.toInt()               // GColorRed
    const val ORANGE = 0xFFFF5500.toInt()            // GColorOrange
    const val CHROME_YELLOW = 0xFFFFAA00.toInt()     // GColorChromeYellow
    const val YELLOW = 0xFFFFFF00.toInt()            // GColorYellow
    const val COBALT_BLUE = 0xFF0055AA.toInt()       // GColorCobaltBlue
    const val VIVID_CERULEAN = 0xFF00AAFF.toInt()    // GColorVividCerulean
    const val PICTON_BLUE = 0xFF55AAFF.toInt()       // GColorPictonBlue
    const val TIFFANY_BLUE = 0xFF00AAAA.toInt()      // GColorTiffanyBlue
    const val ELECTRIC_BLUE = 0xFF55FFFF.toInt()     // GColorElectricBlue
    const val BABY_BLUE_EYES = 0xFFAAAAFF.toInt()    // GColorBabyBlueEyes
    const val LIBERTY = 0xFF5555AA.toInt()           // GColorLiberty
    const val IMPERIAL_PURPLE = 0xFF550055.toInt()   // GColorImperialPurple
    const val PURPLE = 0xFFAA00AA.toInt()            // GColorPurple
    const val ELECTRIC_ULTRAMARINE = 0xFF5500FF.toInt() // GColorElectricUltramarine
    const val CYAN = 0xFF00FFFF.toInt()              // GColorCyan
    const val CADET_BLUE = 0xFF55AAAA.toInt()        // GColorCadetBlue
    const val KELLY_GREEN = 0xFF55AA00.toInt()       // GColorKellyGreen
    const val MEDIUM_AQUAMARINE = 0xFF55FFAA.toInt() // GColorMediumAquamarine
    const val SPRING_BUD = 0xFFAAFF00.toInt()        // GColorSpringBud
    const val ICTERINE = 0xFFFFFF55.toInt()          // GColorIcterine
    const val WINDSOR_TAN = 0xFFAA5500.toInt()       // GColorWindsorTan
    const val ISLAMIC_GREEN = 0xFF00AA00.toInt()     // GColorIslamicGreen
    const val SCREAMIN_GREEN = 0xFF55FF55.toInt()    // GColorScreaminGreen
    const val FOLLY = 0xFFFF0055.toInt()             // GColorFolly
    const val DARK_CANDY_APPLE_RED = 0xFFAA0000.toInt() // GColorDarkCandyAppleRed

    /** Marker for "the background colour": drawn as a transparent hole, so
     *  the widget background (transparent, wallpaper or a chosen colour)
     *  shows through, as the watch's white/black background would. */
    const val BG = 0x00000000

    // ---- Layout, in watch rows (src/c/main.c, emery block; emery is 200x228) ----
    const val WATCH_W = 200
    const val WATCH_H = 228
    const val DAYLIGHT_H = 18       // DAYLIGHT_H
    const val CLOUD_H = 14          // CLOUD_H (Split doubles it, prv_cloud_strip_h_raw)
    const val PRECIP_H = 29         // PRECIP_H
    const val EVENT_H = 12          // EVENT_H
    const val GRAPH_LAYERS_H = DAYLIGHT_H + CLOUD_H + PRECIP_H + EVENT_H  // 73
    const val CLOUD_LIFT = 1        // CLOUD_LIFT (emery)
    const val CLOUD_RAISE = 2       // CLOUD_RAISE
    const val TEMP_H = 56           // TEMP_H (emery)

    // ---- Time block (src/c/ui/time_layer.h, emery): y = 73 (TIME_BLOCK_TOP_GAP 0) ----
    const val TL_SMALL_H = 22       // city / date box (FONT_KEY_GOTHIC_18)
    const val TL_TIME_H = 62        // FONT_KEY_LECO_60_NUMBERS_AM_PM box
    const val TL_TIME_PAD = 14      // empty rows above the LECO digits
    const val TIME_BLOCK_Y = GRAPH_LAYERS_H
    const val TIME_BLOCK_H = (TL_SMALL_H - TL_TIME_PAD - 1) + TL_TIME_H + (TL_SMALL_H - 1) // 90
    /** Text sizes in rows for Android fonts (cap height ≈ 0.71 em) so the ink
     *  matches the watch: Gothic 18 caps ≈ 11.5 rows, LECO 60 digits ≈ 44 rows. */
    const val CITY_TEXT_ROWS = 16f
    const val DATE_TEXT_ROWS = 16f
    const val CLOCK_TEXT_ROWS = 62f
    /** Digit ink centre on the watch: time layer y 80 + pad 14 + ~22. */
    const val CLOCK_CENTER_Y = TIME_BLOCK_Y + TIME_BLOCK_H / 2
    /** Side margin of the date row (date + next alarm) in rows. */
    const val DATE_EDGE_ROWS = 4f

    /** Pixel density of the bitmap relative to the watch grid. */
    const val RENDER_SCALE = 3f

    // ---- Hour window (src/c/ui/graph_common.h) ----
    /** Payload slot of the fetch hour (src/pkjs/weather/provider.js PAST). */
    const val PAYLOAD_NOW_SLOT = 12

    // ---- Clouds (src/c/ui/cloud_layer.c prv_get_thresholds): clear, small, medium ----
    val CLOUD_THRESHOLDS = arrayOf(
        intArrayOf(5, 25, 55),    // Very sensitive
        intArrayOf(15, 40, 70),   // Sensitive (default)
        intArrayOf(25, 50, 75),   // Balanced
        intArrayOf(40, 65, 85),   // Insensitive
    )                             // 4 = Off

    // ---- Sun rays (cloud_layer.c prv_get_sun_thresholds): min intensity, full W/m² ----
    val SUN_THRESHOLDS = arrayOf(
        intArrayOf(8, 500), intArrayOf(20, 800), intArrayOf(35, 950), intArrayOf(70, 1100),
    )                             // 4 = Off (watch default)

    // ---- Rain (src/c/ui/precip_layer.c prv_precip_min_prob) ----
    val PRECIP_MIN_PROB = intArrayOf(5, 15, 30, 50, 101)
    const val RAIN_HEAVY_TENTHS = 25   // PRECIP_RAIN_HEAVY_TENTHS
    const val SNOW_HEAVY_TENTHS = 10   // PRECIP_SNOW_HEAVY_TENTHS
    const val AMOUNT_FULL_TENTHS = 80  // PRECIP_AMOUNT_FULL_TENTHS

    // ---- Temperature meteogram (src/c/ui/temp_layer.c) ----
    const val TEMP_PAD = 4
    const val TEMP_LABEL_W = 32        // prv_draw_labels label_x
    const val TEMP_FONT_SM = 16f       // FONT_KEY_GOTHIC_18 (sm_h 16)
    const val TEMP_FONT_MD = 22f       // FONT_KEY_GOTHIC_24_BOLD (md_h 22)

    // ---- Daylight track (src/c/ui/daylight_layer.c prv_draw_track) ----
    const val TRACK_HALF = 1
    const val MARKER_R = 3          // prv_draw_col_marker r (emery)
    const val BM_W = 8              // battery marker body
    const val BM_H = 6

    // ---- Event row (src/c/ui/event_layer.c, emery) ----
    const val EVENT_ICON_LIFT = 1
    /** Event flags (settings.h EVENT_HIDE_* / EVENT_THUNDER_STRONG / snow level). */
    const val EVENT_HIDE_THUNDER = 0x01
    const val EVENT_HIDE_TORNADO = 0x02
    const val EVENT_HIDE_SNOW = 0x04
    const val EVENT_THUNDER_STRONG = 0x08
    /** Bit masks, row y / bit x (event_layer.c s_mask_*; snow and tornado
     *  are emery's rendered IcoMoon glyphs as the gabbro masks store them). */
    val MASK_BOLT = intArrayOf(0x0e0, 0x070, 0x070, 0x038, 0x03c, 0x1fe, 0x0fe, 0x07f, 0x030, 0x038, 0x018, 0x00c, 0x004)
    val MASK_WIND = intArrayOf(0x780, 0x800, 0x800, 0x7ff, 0x000, 0x000, 0x1ff, 0x200, 0x200, 0x1c0)
    val MASK_SNOW = intArrayOf(0x045, 0x0d6, 0x1d7, 0x038, 0x0ee, 0x038, 0x1d7, 0x0d6, 0x0c5)
    val MASK_TORNADO = intArrayOf(0x07f, 0x000, 0x03e, 0x000, 0x00f, 0x00f, 0x000, 0x00c, 0x018)
    val FOG_WAVE = intArrayOf(0, -1, -1, 0, 1, 1, 0, -1, -1, 0, 1)

    /** Per-theme colours ("light" = SETTING_LIGHT_THEME 1). */
    class Palette(val light: Boolean) {
        val ink = if (light) BLACK else WHITE
        // cloud_layer.c prv_cloud_color / prv_cloud_style
        val cloudNormal = if (light) LIGHT_GRAY else WHITE
        val cloudHeavy = if (light) DARK_GRAY else LIGHT_GRAY
        val cloudLightDither = BG                    // White on light, Black on dark = background
        val cloudThickBase = LIGHT_GRAY
        val cloudThickDither = DARK_GRAY
        val sunRay = if (light) CHROME_YELLOW else YELLOW  // prv_draw_sun_rays
        // precip_layer.c prv_bar_color
        val rainHeavy = if (light) COBALT_BLUE else PICTON_BLUE
        val rainLight = if (light) VIVID_CERULEAN else COBALT_BLUE
        val snowHeavy = if (light) DARK_GRAY else WHITE
        val snowLight = LIGHT_GRAY
        val mix = if (light) TIFFANY_BLUE else ELECTRIC_BLUE
        val hail = BABY_BLUE_EYES
        val hailOutline: Int? = if (light) LIBERTY else null
        val chanceLine = if (light) ORANGE else CHROME_YELLOW  // prv_draw_chance_line
        // temp_layer.c prv_draw_ticks / prv_draw_needle
        val tick = DARK_GRAY
        val tickMajor = ink
        val needle = RED
        // daylight_layer.c prv_draw_track
        val trackNight = if (light) BLACK else DARK_GRAY
        val trackDay = if (light) LIGHT_GRAY else WHITE
        val trackBracket = ink
        // daylight_layer.c prv_draw_col_marker (noon / moon): fill bg, outline fg
        val markerFg = ink
        // daylight_layer.c prv_draw_battery (3.1.2 light-theme colours)
        val batt20 = if (light) CHROME_YELLOW else YELLOW
        val batt10 = if (light) WINDSOR_TAN else ORANGE
        val batt4 = RED
        val batt0 = if (light) DARK_CANDY_APPLE_RED else FOLLY
        // event_layer.c prv_span_color / prv_fog_second
        val evStorm = if (light) WINDSOR_TAN else YELLOW
        val evSnow = if (light) COBALT_BLUE else CYAN
        val evWind = if (light) ISLAMIC_GREEN else SCREAMIN_GREEN
        val evTornado = ORANGE
        val evFog = DARK_GRAY
        val evFog2 = if (light) BLACK else LIGHT_GRAY
        val evMissing = RED

        /** temp_layer.c COLOR_LIGHT_THEME / COLOR_DARK_THEME, thresholds in °F. */
        fun tempColor(tf: Int): Int = if (light) when {
            tf <= 10 -> IMPERIAL_PURPLE
            tf <= 32 -> COBALT_BLUE
            tf <= 45 -> TIFFANY_BLUE
            tf <= 59 -> MEDIUM_AQUAMARINE
            tf <= 76 -> SPRING_BUD
            tf <= 84 -> ICTERINE
            tf <= 96 -> ORANGE
            else -> RED
        } else when {
            tf <= 10 -> PURPLE
            tf <= 32 -> ELECTRIC_ULTRAMARINE
            tf <= 45 -> CYAN
            tf <= 59 -> CADET_BLUE
            tf <= 76 -> KELLY_GREEN
            tf <= 84 -> CHROME_YELLOW
            tf <= 96 -> ORANGE
            else -> RED
        }
    }
}
