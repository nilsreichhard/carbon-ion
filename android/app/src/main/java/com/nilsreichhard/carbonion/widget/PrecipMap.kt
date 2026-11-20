package com.nilsreichhard.carbonion.widget

/** Port of src/c/modules/precip_map.h (pure functions, same rules). */
object PrecipMap {
    const val KIND_RAIN = 0
    const val KIND_SNOW = 1
    const val KIND_MIX = 2
    const val KIND_HAIL = 3

    fun kind(c: Int): Int = when {
        c in 56..57 || c in 66..69 || c == 79 || c in 83..84 || c in 93..94 || c == 23 || c == 24 || c == 26 -> KIND_MIX
        c in 70..78 || c in 85..86 || c == 22 || c in 36..39 -> KIND_SNOW
        c in 87..90 || c == 96 || c == 99 || c == 27 -> KIND_HAIL
        else -> KIND_RAIN
    }

    private val HEAVY_CODES = setOf(62, 63, 64, 65, 81, 82, 92, 97, 72, 73, 74, 75, 86)

    fun isHeavy(code: Int, haveMm: Boolean, mmTenths: Int): Boolean {
        val k = kind(code)
        if (k == KIND_MIX || k == KIND_HAIL) return false
        if (haveMm) return mmTenths >= (if (k == KIND_SNOW) CarbonStyle.SNOW_HEAVY_TENTHS else CarbonStyle.RAIN_HEAVY_TENTHS)
        return code in HEAVY_CODES
    }

    /** cloud_hour_heavy */
    fun cloudHeavy(code: Int, haveMm: Boolean, mmTenths: Int): Boolean =
        code in 95..99 || isHeavy(code, haveMm, mmTenths)
}
