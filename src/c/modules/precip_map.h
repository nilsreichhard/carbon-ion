/**
 * Rain bar classification (2.9.6): pure functions, no Pebble headers, so the
 * host test (tools/test_precip_map.c) compiles them directly.
 *
 * Type comes from the hour's WMO 4677 code; intensity is lightness within the
 * type's hue. Thunderstorms have no colour of their own (the thunder event
 * marks them): 95/97/98 are rain, 96/99 (with hail) are hail.
 *
 * @license GPL-3.0-or-later
 */
#pragma once
#include <stdint.h>
#include <stdbool.h>

enum { PRECIP_KIND_RAIN = 0, PRECIP_KIND_SNOW = 1, PRECIP_KIND_MIX = 2, PRECIP_KIND_HAIL = 3 };

/* Packed mm/h: 0.1 mm steps, 255 = 25.5 mm/h or more. */
#define PRECIP_MM_STEP_TENTHS 1
#define PRECIP_RAIN_HEAVY_TENTHS 25 /* >= 2.5 mm/h = moderate/heavy rain (WMO/AMS) */
#define PRECIP_SNOW_HEAVY_TENTHS 10 /* >= 1.0 mm/h water equivalent = moderate/heavy snow */

/* Kind of precipitation for a WMO code. Non-precipitation codes (clear,
 * cloud, fog, past-hour 20-29) count as rain: a rain chance there is rain. */
static inline uint8_t precip_kind(uint8_t c) {
	if ((c >= 56 && c <= 57) || (c >= 66 && c <= 69) || c == 79 || (c >= 83 && c <= 84) ||
	    (c >= 93 && c <= 94) || c == 23 || c == 24 || c == 26)
		return PRECIP_KIND_MIX;
	if ((c >= 70 && c <= 78) || (c >= 85 && c <= 86) || c == 22 || (c >= 36 && c <= 39))
		return PRECIP_KIND_SNOW;
	if ((c >= 87 && c <= 90) || c == 96 || c == 99 || c == 27)
		return PRECIP_KIND_HAIL;
	return PRECIP_KIND_RAIN;
}

/* Heavy shade from the code alone (no amount): moderate and heavy count as
 * heavy (63/65, 81/82, 73/75, 86, 92, 97); light, drizzle and 95 are light. */
static inline bool precip_code_heavy(uint8_t c) {
	switch (c) {
	case 62: case 63: case 64: case 65: case 81: case 82: case 92: case 97:
	case 72: case 73: case 74: case 75: case 86:
		return true;
	default:
		return false;
	}
}

/* Heavy shade: from the amount when the provider sent one, else the code. */
static inline bool precip_is_heavy(uint8_t code, bool have_mm, uint8_t mm_tenths) {
	uint8_t k = precip_kind(code);
	if (k == PRECIP_KIND_MIX || k == PRECIP_KIND_HAIL)
		return false; /* single shade */
	if (have_mm)
		return mm_tenths >= (k == PRECIP_KIND_SNOW ? PRECIP_SNOW_HEAVY_TENTHS : PRECIP_RAIN_HEAVY_TENTHS);
	return precip_code_heavy(code);
}

/* Amount mode bar height in px for layer height h: sqrt scale, full at
 * 8 mm/h (0.1 → 11 %, 0.5 → 25 %, 1 → 35 %, 2.5 → 56 %, 5 → 79 %). Any
 * measurable amount gets at least 1 px. */
#define PRECIP_AMOUNT_FULL_TENTHS 80
static inline int precip_amount_px(uint8_t mm_tenths, int h) {
	if (mm_tenths == 0 || h <= 0)
		return 0;
	if (mm_tenths >= PRECIP_AMOUNT_FULL_TENTHS)
		return h;
	/* integer sqrt of (mm/full) * h^2 */
	uint32_t target = (uint32_t)mm_tenths * (uint32_t)h * (uint32_t)h / PRECIP_AMOUNT_FULL_TENTHS;
	uint32_t r = 0;
	while ((r + 1) * (r + 1) <= target)
		r++;
	return r < 1 ? 1 : (int)r;
}

/* Probability shading in Amount mode: 2 solid, 1 half (checker), 0 quarter. */
static inline uint8_t precip_prob_shade(uint8_t prob) {
	return prob >= 70 ? 2 : (prob >= 40 ? 1 : 0);
}

/* ---- Clouds (2.9.6) -------------------------------------------------------
 * Clouds show only size (cover %) and whether the hour is "heavy".
 * Heavy = a thunderstorm code (95-99, incl. hail 96/99) OR heavy rain/snow:
 * >= 2.5 mm/h rain or >= 1.0 mm/h snow (water equivalent) when the provider
 * sent amounts, else the heavy/moderate codes (63/65, 81/82, 92, 97,
 * 73/75, 86). Mixed/freezing and hail-shower codes alone are not heavy. */
static inline bool cloud_hour_heavy(uint8_t code, bool have_mm, uint8_t mm_tenths) {
	if (code >= 95 && code <= 99)
		return true;
	return precip_is_heavy(code, have_mm, mm_tenths);
}

#define CLOUD_LAYER_LOW 0x01
#define CLOUD_LAYER_MID 0x02
#define CLOUD_LAYER_HIGH 0x04

/* Split mode: which drawn layers get the heavy colour. Providers give only
 * per-layer cover % (no cloud type), so: a thunderstorm is a deep convective
 * cloud (cumulonimbus) -> every drawn layer; heavy rain/snow falls from the
 * lowest deck -> the low layer, or mid if there is no low cloud. A heavy hour
 * with no low/mid cloud marks nothing (high cloud does not rain heavily). */
static inline uint8_t cloud_split_heavy_mask(uint8_t code, bool heavy, uint8_t drawn) {
	if (!heavy)
		return 0;
	if (code >= 95 && code <= 99)
		return drawn;
	if (drawn & CLOUD_LAYER_LOW)
		return CLOUD_LAYER_LOW;
	if (drawn & CLOUD_LAYER_MID)
		return CLOUD_LAYER_MID;
	return 0;
}

/* ---- Cloud thickness (3.0.0) ----------------------------------------------
 * The phone sends one level per hour for the whole sky: 0 no data, 1 light,
 * 2 normal, 3 thick (clear-sky ratio by day, rain/layers/cloud base else).
 * Total draws it as is (0 -> normal). Split: light thins every drawn layer;
 * thick goes to the lowest drawn layer only (the deck that blocks the sun /
 * rains), the others stay normal. Heavy always wins (checked by the caller). */
enum { CLOUD_THICK_NONE = 0, CLOUD_THICK_LIGHT = 1, CLOUD_THICK_NORMAL = 2, CLOUD_THICK_THICK = 3 };

static inline uint8_t cloud_thick_for_layer(uint8_t level, uint8_t layer_bit, uint8_t drawn) {
	if (level == CLOUD_THICK_NONE || level > CLOUD_THICK_THICK)
		return CLOUD_THICK_NORMAL;
	if (!layer_bit || level != CLOUD_THICK_THICK)
		return level;
	uint8_t lowest = (drawn & CLOUD_LAYER_LOW) ? CLOUD_LAYER_LOW
	               : (drawn & CLOUD_LAYER_MID) ? CLOUD_LAYER_MID
	               : (drawn & CLOUD_LAYER_HIGH) ? CLOUD_LAYER_HIGH : 0;
	return layer_bit == lowest ? CLOUD_THICK_THICK : CLOUD_THICK_NORMAL;
}

/* Hour k's level from the 18-byte thick_bits array (2 bits per hour). */
static inline uint8_t cloud_thick_unpack(const uint8_t *bits, int k) {
	return (uint8_t)((bits[k >> 2] >> ((k & 3) * 2)) & 3);
}
