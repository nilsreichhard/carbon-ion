/**
 * Cloud layer
 *
 * @author    Nils Reich <https://github.com/nilsreichhard/carbon-ion>
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

#include "glyph.h"
#include "arc.h"
#include "cloud_layer.h"
#include "../modules/settings.h"
#include "../modules/precip_map.h"
#include "graph_common.h"
#include <stddef.h>
#include <stdlib.h>

struct CloudLayer {
	Layer *layer;
	uint8_t cover[MAX_GRAPH_HOURS];
	uint8_t cover_low[MAX_GRAPH_HOURS];
	uint8_t cover_mid[MAX_GRAPH_HOURS];
	uint8_t cover_high[MAX_GRAPH_HOURS];
	uint8_t hourly_code[MAX_GRAPH_HOURS];
	uint8_t shortwave[MAX_GRAPH_HOURS]; // packed W/m² / 4 (0–255)
	uint8_t thick[MAX_GRAPH_HOURS];     // 3.0.0: CLOUD_THICK_* (0 = none)
	uint8_t mm[MAX_GRAPH_HOURS];        // 0.1 mm/h (2.9.6)
	bool have_mm;
	uint8_t current_hour;
};

static void prv_get_thresholds(CloudSensitivity sens, int *clear_th,
                               int *small_th, int *med_th, bool *off) {
	*off = false;
	switch (sens) {
	case CLOUD_SENS_VERY:
		*clear_th = 5;
		*small_th = 25;
		*med_th = 55;
		break;
	case CLOUD_SENS_BALANCED:
		*clear_th = 25;
		*small_th = 50;
		*med_th = 75;
		break;
	case CLOUD_SENS_INSENSITIVE:
		*clear_th = 40;
		*small_th = 65;
		*med_th = 85;
		break;
	case CLOUD_SENS_OFF:
		*off = true;
		*clear_th = 255;
		*small_th = 255;
		*med_th = 255;
		break;
	case CLOUD_SENS_SENSITIVE:
	default:
		*clear_th = 15;
		*small_th = 40;
		*med_th = 70;
		break;
	}
}

static void prv_get_sun_thresholds(SunlightSensitivity sens, int *min_intensity,
                                   int *full_wm2, bool *off) {
	*off = false;
	switch (sens) {
	case SUN_SENS_VERY:
		*min_intensity = 8;
		*full_wm2 = 500;
		break;
	case SUN_SENS_BALANCED:
		*min_intensity = 35;
		*full_wm2 = 950;
		break;
	case SUN_SENS_INSENSITIVE:
		*min_intensity = 70;
		*full_wm2 = 1100;
		break;
	case SUN_SENS_OFF:
		*off = true;
		*min_intensity = 256;
		*full_wm2 = 800;
		break;
	case SUN_SENS_SENSITIVE:
	default:
		*min_intensity = 20;
		*full_wm2 = 800;
		break;
	}
}


/* 2.8.4: lobes and rays go through a glyph transform so gabbro can draw
 * them at native size after the arc warp (identity on rect). */
#if defined(PBL_ROUND)
static bool s_mapped;
static GPoint s_org;
#endif

static GlyphXf prv_xf(int x, int y) {
#if defined(PBL_ROUND)
	if (s_mapped)
		return arc_glyph_xf_top(s_org.x + x, s_org.y + y);
#endif
	return gx_identity(x, y);
}

static void prv_draw_cloud_lobes(GContext *ctx, int cx, int cy, int r) {
	if (r <= 0)
		return;
	GlyphXf t = prv_xf(cx, cy);
	graphics_fill_circle(ctx, gx_pt(&t, 0, 0), r);
	graphics_fill_circle(ctx, gx_pt(&t, -r, r / 2), r * 2 / 3);
	graphics_fill_circle(ctx, gx_pt(&t, r, r / 2), r * 2 / 3);
}

static void prv_draw_cloud(GContext *ctx, int cx, int cy, int r) {
	prv_draw_cloud_lobes(ctx, cx, cy, r);
}


static int prv_radius_for_cover(uint8_t cover, int clear_th, int small_th,
                                int med_th) {
	if (cover < clear_th)
		return 0;
	if (cover < small_th)
		return 2;
	if (cover < med_th)
		return 3;
	return 4;
}

/* 3.0.0 palette. Base colour per level, plus an optional second colour laid
 * over it as a 1 px checkerboard (light = see-through, thick = darker):
 *   light theme: light LightGray+White, normal LightGray,
 *                thick LightGray+DarkGray, heavy DarkGray;
 *   dark theme:  light White+Black, normal White,
 *                thick LightGray+DarkGray, heavy LightGray.
 * Dark thick has no white pixel and a dark checker, so it reads apart from
 * both normal (White) and heavy (solid LightGray). */
static GColor prv_cloud_color(bool heavy, bool is_light) {
#if defined(PBL_COLOR)
	if (heavy)
		return is_light ? GColorDarkGray : GColorLightGray;
	return is_light ? GColorLightGray : GColorWhite;
#else
	(void)heavy;
	(void)is_light;
	return GColorWhite;
#endif
}

/* Returns true when a checker colour is laid over the base. */
static bool prv_cloud_style(uint8_t level, bool heavy, bool is_light, GColor *base, GColor *dither) {
	*base = prv_cloud_color(heavy, is_light);
#if defined(PBL_COLOR)
	if (heavy)
		return false;
	if (level == CLOUD_THICK_LIGHT) {
		*dither = is_light ? GColorWhite : GColorBlack;
		return true;
	}
	if (level == CLOUD_THICK_THICK) {
		*base = GColorLightGray;
		*dither = GColorDarkGray;
		return true;
	}
#else
	(void)level;
#endif
	(void)dither;
	return false;
}

/* Checkerboard in screen space (gabbro's mapped lobes match). Strictly inside
 * the filled circle so no checker pixel lands outside the cloud edge. */
static void prv_stipple_circle(GContext *ctx, GPoint c, int r) {
	for (int dy = -r; dy <= r; dy++)
		for (int dx = -r; dx <= r; dx++)
			if (dx * dx + dy * dy < r * r && (((c.x + dx) + (c.y + dy)) & 1) == 0)
				graphics_draw_pixel(ctx, GPoint(c.x + dx, c.y + dy));
}

static void prv_stipple_lobes(GContext *ctx, int cx, int cy, int r) {
	if (r <= 0)
		return;
	GlyphXf t = prv_xf(cx, cy);
	prv_stipple_circle(ctx, gx_pt(&t, 0, 0), r);
	prv_stipple_circle(ctx, gx_pt(&t, -r, r / 2), r * 2 / 3);
	prv_stipple_circle(ctx, gx_pt(&t, r, r / 2), r * 2 / 3);
}

/* Pass halo=true then fill=true so same-band clouds merge; halo only
 * remains where this band overlaps a band drawn earlier. layer_bit selects
 * the Split layer (0 = Total) for the per-layer heavy rule. */
static void prv_draw_band(GContext *ctx, CloudLayer *cl, const uint8_t *cover,
                          int total_hours, int graph_x, int graph_w, int cy,
                          int clear_th, int small_th, int med_th, uint8_t layer_bit,
                          bool is_light, bool with_layer_halo) {
	if (with_layer_halo) {
		graphics_context_set_fill_color(ctx, is_light ? GColorWhite : GColorBlack);
		for (int i = total_hours - 1; i >= 0; i--) {
			int r = prv_radius_for_cover(cover[i], clear_th, small_th, med_th);
			if (r <= 0)
				continue;
			int cx = graph_x + (long)(i * 2 + 1) * graph_w / (total_hours * 2);
			prv_draw_cloud_lobes(ctx, cx, cy, r + 1);
		}
	}
	bool thick_on = settings_get()->cloud_thick == CLOUD_THICKNESS_ON;
	for (int i = total_hours - 1; i >= 0; i--) {
		int r = prv_radius_for_cover(cover[i], clear_th, small_th, med_th);
		if (r <= 0)
			continue;
		int cx = graph_x + (long)(i * 2 + 1) * graph_w / (total_hours * 2);
		uint8_t code = cl->hourly_code[i];
		bool heavy = cloud_hour_heavy(code, cl->have_mm, cl->mm[i]);
		uint8_t drawn = 0;
		if (layer_bit) {
			if (prv_radius_for_cover(cl->cover_low[i], clear_th, small_th, med_th) > 0)
				drawn |= CLOUD_LAYER_LOW;
			if (prv_radius_for_cover(cl->cover_mid[i], clear_th, small_th, med_th) > 0)
				drawn |= CLOUD_LAYER_MID;
			if (prv_radius_for_cover(cl->cover_high[i], clear_th, small_th, med_th) > 0)
				drawn |= CLOUD_LAYER_HIGH;
			heavy = (cloud_split_heavy_mask(code, heavy, drawn) & layer_bit) != 0;
		}
		uint8_t level = thick_on ? cloud_thick_for_layer(cl->thick[i], layer_bit, drawn)
		                         : CLOUD_THICK_NORMAL;
		GColor base, dither = GColorClear;
		bool dith = prv_cloud_style(level, heavy, is_light, &base, &dither);
		graphics_context_set_fill_color(ctx, base);
		prv_draw_cloud(ctx, cx, cy, r);
		if (dith) {
			graphics_context_set_stroke_color(ctx, dither);
			prv_stipple_lobes(ctx, cx, cy, r);
		}
	}
}

/* Sun-ray bars: vertical from the strip top (both modes since 3.1.2).
 * Fixed bar count (no gaps); intensity → length only (one color). */
static void prv_draw_sun_rays(GContext *ctx, int cx, int strip_h, int intensity,
                              int min_intensity, bool is_light, bool vertical) {
	if (intensity < min_intensity || strip_h <= 0)
		return;

	const int ray_count = 3; /* always the same — avoid gaps between hours */

#if defined(PBL_COLOR)
	/* One color; intensity is length only. */
	graphics_context_set_stroke_color(ctx,
	                                  is_light ? GColorChromeYellow : GColorYellow);
#else
	graphics_context_set_stroke_color(ctx, GColorWhite);
	(void)is_light;
#endif
	graphics_context_set_stroke_width(ctx, 1);

	int span = strip_h > 2 ? strip_h - 1 : 4;
	int dy = 3 + (intensity * (span - 3)) / 255;
	if (dy < 3)
		dy = 3;
	if (dy > span)
		dy = span;

	/* Always start at the top of the cloud strip; drawn after clouds (above). */
	int y0 = 0;
	GlyphXf t = prv_xf(cx, y0);
	if (vertical) {
		for (int r = 0; r < ray_count; r++) {
			int ox = -2 + r * 2; /* spaced like earlier look */
			graphics_draw_line(ctx, gx_pt(&t, ox, 0), gx_pt(&t, ox, dy));
		}
	} else {
		int dx = dy;
		if (dx < 2)
			dx = 2;
		if (dy > strip_h)
			dy = strip_h;
		for (int r = 0; r < ray_count; r++) {
			int ox = -2 + r * 2; /* spaced like earlier look */
			graphics_draw_line(ctx, gx_pt(&t, ox, 0), gx_pt(&t, ox + dx, dy));
		}
	}
}

/* Dim rays by total cloud cover (0% cover → full; 100% → none). */
static int prv_ray_intensity_for_cover(int base_intensity, uint8_t cover) {
	int open = 100 - (int)cover;
	if (open < 0)
		open = 0;
	return (base_intensity * open) / 100;
}

static void prv_draw(CloudLayer *cl, GContext *ctx) {
	GRect bounds = layer_get_bounds(cl->layer);
	int graph_x = GRAPH_OFFSET_X;
	int graph_w = bounds.size.w - graph_x;
	int h = bounds.size.h;
	int total_hours = GRAPH_HOURS;
	if (total_hours > MAX_GRAPH_HOURS) total_hours = MAX_GRAPH_HOURS;

	const Settings *settings = settings_get();
	int clear_th = 15, small_th = 40, med_th = 70;
	bool clouds_off = false;
	prv_get_thresholds(settings->cloud_sensitivity, &clear_th, &small_th,
	                   &med_th, &clouds_off);
	int sun_min = 20, sun_full = 800;
	bool rays_off = false;
	prv_get_sun_thresholds(settings->sunlight_sensitivity, &sun_min, &sun_full,
	                       &rays_off);
	bool draw_rays = !rays_off;
	bool is_light = settings->light_theme;
	bool split = (settings_effective_cloud_mode() == CLOUD_DISPLAY_SPLIT);

	graphics_context_set_antialiased(ctx, false);

	/* Clouds first, then sun rays on top (overlap clouds, start at strip top). */
	if (!clouds_off) {
		if (split) {
			/* Full-size lobes. High band matches Total top offset (cy_high = h/4).
			 * Pitch 7: +1px between high→mid and mid→low vs prior 6. */
			int pitch = 7;
			if (pitch * 2 + 8 > h)
				pitch = (h - 8) / 2;
			if (pitch < 5)
				pitch = 5;
			int cy_high = h / 4;
			if (cy_high < 4)
				cy_high = 4;
			int cy_mid = cy_high + pitch;
			int cy_low = cy_mid + pitch;
			if (cy_low > h - 5) {
				cy_low = h - 5;
				cy_mid = cy_low - pitch;
				if (cy_mid < cy_high + 4)
					cy_mid = cy_high + 4;
			}
			/* High first (no halo). Mid/low: halo pass then fill so same-band
			 * clouds merge; halo only shows against the band underneath. */
			prv_draw_band(ctx, cl, cl->cover_high, total_hours, graph_x, graph_w,
			              cy_high, clear_th, small_th, med_th, CLOUD_LAYER_HIGH, is_light, false);
			prv_draw_band(ctx, cl, cl->cover_mid, total_hours, graph_x, graph_w,
			              cy_mid, clear_th, small_th, med_th, CLOUD_LAYER_MID, is_light, true);
			prv_draw_band(ctx, cl, cl->cover_low, total_hours, graph_x, graph_w,
			              cy_low, clear_th, small_th, med_th, CLOUD_LAYER_LOW, is_light, true);
		} else {
			/* Total: 1px lower so rain sits closer under the cloud row. */
			int cy = h / 2;
			if (cy > h - 5)
				cy = h - 5;
			if (cy < 4)
				cy = 4;
			prv_draw_band(ctx, cl, cl->cover, total_hours, graph_x, graph_w, cy,
			              clear_th, small_th, med_th, 0, is_light, false);
		}
	}

	if (draw_rays) {
		for (int i = 0; i < total_hours; i++) {
			int cx = graph_x + (long)(i * 2 + 1) * graph_w / (total_hours * 2);
			int wm2 = (int)cl->shortwave[i] * 4; // unpack
			int base = wm2 >= sun_full ? 255 : (wm2 * 255) / sun_full;
			int intensity = prv_ray_intensity_for_cover(base, cl->cover[i]);
			/* 3.1.2: vertical rays in Total too (same as Split). */
			prv_draw_sun_rays(ctx, cx, h, intensity, sun_min, is_light, true);
		}
	}
}

static void prv_update_proc(Layer *layer, GContext *ctx) {
#if defined(PBL_ROUND)
	/* Gabbro: drawn after the warp by cloud_layer_draw_mapped(). */
	(void)layer;
	(void)ctx;
#else
	prv_draw(*(CloudLayer **)layer_get_data(layer), ctx);
#endif
}

#if defined(PBL_ROUND)
void cloud_layer_draw_mapped(CloudLayer *cl, GContext *ctx) {
	if (!cl)
		return;
	s_mapped = true;
	s_org = layer_get_frame(cl->layer).origin;
	prv_draw(cl, ctx);
	s_mapped = false;
	graphics_context_set_antialiased(ctx, true);
}
#endif

CloudLayer *cloud_layer_create(GRect frame) {
	CloudLayer *cl = malloc(sizeof(CloudLayer));
	if (!cl)
		return NULL;
	memset(cl->cover, 0, sizeof(cl->cover));
	memset(cl->cover_low, 0, sizeof(cl->cover_low));
	memset(cl->cover_mid, 0, sizeof(cl->cover_mid));
	memset(cl->cover_high, 0, sizeof(cl->cover_high));
	memset(cl->hourly_code, 0, sizeof(cl->hourly_code));
	memset(cl->shortwave, 0, sizeof(cl->shortwave));
	memset(cl->thick, 0, sizeof(cl->thick));
	memset(cl->mm, 0, sizeof(cl->mm));
	cl->have_mm = false;
	cl->current_hour = 0;

	cl->layer = layer_create_with_data(frame, sizeof(CloudLayer *));
	*(CloudLayer **)layer_get_data(cl->layer) = cl;
	layer_set_update_proc(cl->layer, prv_update_proc);
	return cl;
}

void cloud_layer_destroy(CloudLayer *layer) {
	if (!layer)
		return;
	layer_destroy(layer->layer);
	free(layer);
}

Layer *cloud_layer_get_layer(CloudLayer *layer) {
	return layer ? layer->layer : NULL;
}

void cloud_layer_set_data(CloudLayer *layer, const uint8_t *cover,
                          const uint8_t *hourly_code, const uint8_t *shortwave,
                          const uint8_t *cover_low, const uint8_t *cover_mid,
                          const uint8_t *cover_high, const uint8_t *thick,
                          const uint8_t *mm, bool have_mm, uint8_t current_hour) {
	if (!layer)
		return;
	int total_hours = GRAPH_HOURS;
	if (total_hours > MAX_GRAPH_HOURS) total_hours = MAX_GRAPH_HOURS;
	memcpy(layer->cover, cover, total_hours);
	memcpy(layer->hourly_code, hourly_code, total_hours);
	if (cover_low) {
		memcpy(layer->cover_low, cover_low, total_hours);
	} else {
		memset(layer->cover_low, 0, sizeof(layer->cover_low));
	}
	if (cover_mid) {
		memcpy(layer->cover_mid, cover_mid, total_hours);
	} else {
		memset(layer->cover_mid, 0, sizeof(layer->cover_mid));
	}
	if (cover_high) {
		memcpy(layer->cover_high, cover_high, total_hours);
	} else {
		memset(layer->cover_high, 0, sizeof(layer->cover_high));
	}
	if (shortwave) {
		memcpy(layer->shortwave, shortwave, total_hours);
	} else {
		memset(layer->shortwave, 0, sizeof(layer->shortwave));
	}
	if (thick)
		memcpy(layer->thick, thick, total_hours);
	else
		memset(layer->thick, 0, sizeof(layer->thick));
	if (mm)
		memcpy(layer->mm, mm, total_hours);
	else
		memset(layer->mm, 0, sizeof(layer->mm));
	layer->have_mm = have_mm && mm;
	layer->current_hour = current_hour;
	layer_mark_dirty(layer->layer);
}
