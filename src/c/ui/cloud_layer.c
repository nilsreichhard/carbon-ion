/**
 * Cloud layer
 *
 * @author    Nils Reich <https://github.com/nilsreichhard/carbon-ion>
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

#include "cloud_layer.h"
#include "../modules/settings.h"
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


static void prv_draw_cloud(GContext *ctx, int cx, int cy, int r) {
	if (r <= 0)
		return;
	graphics_fill_circle(ctx, GPoint(cx, cy), r);
	graphics_fill_circle(ctx, GPoint(cx - r, cy + r / 2), r * 2 / 3);
	graphics_fill_circle(ctx, GPoint(cx + r, cy + r / 2), r * 2 / 3);
}

static int prv_radius_for_cover(uint8_t cover, int clear_th, int small_th,
                                int med_th, bool split) {
	if (cover < clear_th)
		return 0;
	if (split) {
		/* Smaller lobes so three stacked bands fit in CLOUD_H. */
		if (cover < small_th)
			return 1;
		if (cover < med_th)
			return 2;
		return 3;
	}
	if (cover < small_th)
		return 2;
	if (cover < med_th)
		return 3;
	return 4;
}

static void prv_set_cloud_fill(GContext *ctx, uint8_t code, bool is_light) {
#if defined(PBL_COLOR)
	GColor cloud_color;
	if (code == 95 || code == 96 || code == 99) {
		cloud_color = GColorLightGray; // storm clouds — grey
	} else if (code == 75 || code == 77 || code == 85 || code == 86) {
		cloud_color = GColorCeleste; // blizzard clouds — light blue
	} else {
		cloud_color = is_light ? GColorLightGray : GColorWhite;
	}
	graphics_context_set_fill_color(ctx, cloud_color);
#else
	graphics_context_set_fill_color(ctx, GColorWhite);
	(void)code;
	(void)is_light;
#endif
}

static void prv_draw_band(GContext *ctx, CloudLayer *cl, const uint8_t *cover,
                          int total_hours, int graph_x, int graph_w, int cy,
                          int clear_th, int small_th, int med_th, bool split,
                          bool is_light) {
	for (int i = total_hours - 1; i >= 0; i--) {
		int r = prv_radius_for_cover(cover[i], clear_th, small_th, med_th, split);
		if (r <= 0)
			continue;
		prv_set_cloud_fill(ctx, cl->hourly_code[i], is_light);
		// Draw later hours first so sooner clouds overlap them.
		int cx = graph_x + (long)(i * 2 + 1) * graph_w / (total_hours * 2);
		prv_draw_cloud(ctx, cx, cy, r);
	}
}

/* Sun-ray bars: angled for Total, vertical-from-top for Split.
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
	if (vertical) {
		for (int r = 0; r < ray_count; r++) {
			int ox = cx - 2 + r * 2; /* spaced like earlier look */
			graphics_draw_line(ctx, GPoint(ox, y0), GPoint(ox, y0 + dy));
		}
	} else {
		int dx = dy;
		if (dx < 2)
			dx = 2;
		if (dy > strip_h)
			dy = strip_h;
		for (int r = 0; r < ray_count; r++) {
			int ox = cx - 2 + r * 2; /* spaced like earlier look */
			graphics_draw_line(ctx, GPoint(ox, y0), GPoint(ox + dx, y0 + dy));
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

static void prv_update_proc(Layer *layer, GContext *ctx) {
	CloudLayer *cl = *(CloudLayer **)layer_get_data(layer);
	GRect bounds = layer_get_bounds(layer);
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
	bool split = (settings->cloud_display_mode == CLOUD_DISPLAY_SPLIT);

	graphics_context_set_antialiased(ctx, false);

	/* Clouds first, then sun rays on top (overlap clouds, start at strip top). */
	if (!clouds_off) {
		if (split) {
			/* Full-size lobes (same as Total); reduce padding by tighter pitch. */
			int pitch = 9; /* row-center spacing; CLOUD_H≈14 left large gaps */
			if (pitch * 2 + 6 > h)
				pitch = (h - 6) / 2;
			if (pitch < 6)
				pitch = 6;
			int mid = h / 2;
			int cy_high = mid - pitch;
			int cy_mid = mid;
			int cy_low = mid + pitch;
			if (cy_high < 3)
				cy_high = 3;
			if (cy_low > h - 4)
				cy_low = h - 4;
			prv_draw_band(ctx, cl, cl->cover_high, total_hours, graph_x, graph_w,
			              cy_high, clear_th, small_th, med_th, false, is_light);
			prv_draw_band(ctx, cl, cl->cover_mid, total_hours, graph_x, graph_w,
			              cy_mid, clear_th, small_th, med_th, false, is_light);
			prv_draw_band(ctx, cl, cl->cover_low, total_hours, graph_x, graph_w,
			              cy_low, clear_th, small_th, med_th, false, is_light);
		} else {
			int cy = h / 2 - 2;
			prv_draw_band(ctx, cl, cl->cover, total_hours, graph_x, graph_w, cy,
			              clear_th, small_th, med_th, false, is_light);
		}
	}

	if (draw_rays) {
		for (int i = 0; i < total_hours; i++) {
			int cx = graph_x + (long)(i * 2 + 1) * graph_w / (total_hours * 2);
			int wm2 = (int)cl->shortwave[i] * 4; // unpack
			int base = wm2 >= sun_full ? 255 : (wm2 * 255) / sun_full;
			int intensity = prv_ray_intensity_for_cover(base, cl->cover[i]);
			prv_draw_sun_rays(ctx, cx, h, intensity, sun_min, is_light, split);
		}
	}
}

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
                          const uint8_t *cover_high, uint8_t current_hour) {
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
	layer->current_hour = current_hour;
	layer_mark_dirty(layer->layer);
}
