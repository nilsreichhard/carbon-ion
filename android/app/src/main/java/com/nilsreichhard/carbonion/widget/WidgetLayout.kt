package com.nilsreichhard.carbonion.widget

/**
 * Size breakpoints. Every layout keeps the watch's row grid (emery rows) and
 * is scaled to the widget height; only which strips are shown and how tall
 * the charts are changes.
 *
 *  - SHORT  (under [SHORT_MAX_DP], 1–2 cells high): top strips (timeline,
 *    clouds, rain, events) + the time block; no meteogram.
 *  - DEFAULT (3 cells high): the emery layout, 73 / 90 / 9 / 56 = 228 rows.
 *  - TALL   ([TALL_MIN_DP] and up, 4+ cells): the same stack with a taller
 *    rain chart and meteogram.
 *
 * Width never squashes the chart: the hour columns keep at least
 * [MIN_COL_ROWS] rows, otherwise the forecast range steps down (48 → 36 →
 * 24 → 18 → 12 h); wide widgets get wider columns.
 */
enum class WidgetLayout(val precipExtra: Int, val timeH: Int, val gap: Int, val tempH: Int, val layoutRes: Int) {
    SHORT(0, CarbonStyle.TIME_BLOCK_H, 6, 0, R.layout.widget_short),
    DEFAULT(0, CarbonStyle.TIME_BLOCK_H, 9, CarbonStyle.TEMP_H, R.layout.widget_carbon),
    TALL(12, CarbonStyle.TIME_BLOCK_H, 9, 86, R.layout.widget_tall),
    /** The expanded app screen's chart: no time block, taller rain and meteogram (not a widget layout). */
    DETAIL(16, 0, 10, 96, R.layout.widget_tall);

    val topH get() = CarbonStyle.GRAPH_LAYERS_H + precipExtra
    val timeY get() = topH
    val tempY get() = topH + timeH + gap
    val totalRows get() = topH + timeH + gap + tempH
    val hasTemp get() = tempH > 0
    val clockCenterY get() = timeY + timeH / 2

    companion object {
        /**
         * Calibrated on Pixel Launcher (Android 15, 1080×2400 @ 420 dpi), which
         * reports ≈ 90 dp per column and ≈ 115 dp per row in portrait
         * (4x3 = 360×344 dp, landscape 625×208 dp): 1-2 rows are SHORT, 3 rows
         * DEFAULT, 4+ rows TALL.
         */
        /** Rows under the date in SHORT (its gap) so descenders clear the widget edge. */
        const val SHORT_BOTTOM_PAD = 6 // = SHORT.gap
        const val SHORT_MAX_DP = 280
        const val TALL_MIN_DP = 400
        const val MIN_COL_ROWS = 5
        private val RANGES = intArrayOf(12, 18, 24, 36, 48)

        fun forHeight(hDp: Int): WidgetLayout = when {
            hDp < SHORT_MAX_DP -> SHORT
            hDp >= TALL_MIN_DP -> TALL
            else -> DEFAULT
        }

        /** Largest forecast range up to [setting] whose columns are ≥ MIN_COL_ROWS wide. */
        fun fitHours(setting: Int, widthRows: Int): Int {
            var i = RANGES.indexOf(setting).let { if (it < 0) 2 else it }
            while (i > 0 && widthRows < (RANGES[i] + RANGES[i] / 4) * MIN_COL_ROWS) i--
            return RANGES[i]
        }
    }
}
