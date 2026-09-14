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

static void prv_draw_cloud(GContext *ctx, int cx, int cy, int r) {
	graphics_fill_circle(ctx, GPoint(cx, cy), r);
	graphics_fill_circle(ctx, GPoint(cx - r, cy + r / 2), r * 2 / 3);
	graphics_fill_circle(ctx, GPoint(cx + r, cy + r / 2), r * 2 / 3);
}

static void prv_draw_sun_rays(GContext *ctx, int cx, int cy, int intensity,
                              bool is_light) {
	// intensity 0–255 maps ~0–800 W/m². Skip near-zero / night.
	if (intensity < 20)
		return;

#if defined(PBL_COLOR)
	/* Light theme: orange reads better on pale bg than chrome yellow. */
	graphics_context_set_stroke_color(ctx,
	                                  is_light ? GColorOrange : GColorYellow);
#else
	graphics_context_set_stroke_color(ctx, GColorWhite);
	(void)is_light;
#endif
	graphics_context_set_stroke_width(ctx, 1);

	// 1–3 diagonal rays; length scales with intensity.
	int ray_count = 1 + (intensity / 100); // 1..3
	if (ray_count > 3)
		ray_count = 3;
	int len = 3 + (intensity * 7) / 255; // 3..10 px
	for (int r = 0; r < ray_count; r++) {
		int ox = cx - 1 + r;
		int oy = cy - 3 - (r % 2);
		graphics_draw_line(ctx, GPoint(ox, oy),
		                   GPoint(ox + len, oy + len));
	}
}

static void prv_update_proc(Layer *layer, GContext *ctx) {
	CloudLayer *cl = *(CloudLayer **)layer_get_data(layer);
	GRect bounds = layer_get_bounds(layer);
	int graph_x = GRAPH_OFFSET_X;
	int graph_w = bounds.size.w - graph_x;
	int cy = bounds.size.h / 2 - 2;
	int total_hours = GRAPH_HOURS;
	if (total_hours > MAX_GRAPH_HOURS) total_hours = MAX_GRAPH_HOURS;

	const Settings *settings = settings_get();
	int clear_th = 15, small_th = 40, med_th = 70;
	bool clouds_off = false;
	prv_get_thresholds(settings->cloud_sensitivity, &clear_th, &small_th,
	                   &med_th, &clouds_off);
	bool draw_rays = settings->sunlight_rays;
	bool is_light = settings->light_theme;

	graphics_context_set_antialiased(ctx, false);

	/* Clouds first, then rays on top so sunlight stays visible over cover. */
	if (!clouds_off) {
		for (int i = total_hours - 1; i >= 0; i--) {
			if (cl->cover[i] < clear_th)
				continue;

#if defined(PBL_COLOR)
			// Color clouds based on WMO severity for severe conditions only
			uint8_t code = cl->hourly_code[i];
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
#endif

			// Draw later hours first so sooner clouds overlap them.
			int cx = graph_x + (long)(i * 2 + 1) * graph_w / (total_hours * 2);
			int r;
			if (cl->cover[i] < small_th) {
				r = 2;
			} else if (cl->cover[i] < med_th) {
				r = 3;
			} else {
				r = 4;
			}
			prv_draw_cloud(ctx, cx, cy, r);
		}
	}

	if (draw_rays) {
		for (int i = 0; i < total_hours; i++) {
			int cx = graph_x + (long)(i * 2 + 1) * graph_w / (total_hours * 2);
			int wm2 = (int)cl->shortwave[i] * 4; // unpack
			int intensity = wm2 >= 800 ? 255 : (wm2 * 255) / 800;
			prv_draw_sun_rays(ctx, cx, cy, intensity, is_light);
		}
	}
}

CloudLayer *cloud_layer_create(GRect frame) {
	CloudLayer *cl = malloc(sizeof(CloudLayer));
	if (!cl)
		return NULL;
	memset(cl->cover, 0, sizeof(cl->cover));
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
                          uint8_t current_hour) {
	if (!layer)
		return;
	int total_hours = GRAPH_HOURS;
	if (total_hours > MAX_GRAPH_HOURS) total_hours = MAX_GRAPH_HOURS;
	memcpy(layer->cover, cover, total_hours);
	memcpy(layer->hourly_code, hourly_code, total_hours);
	if (shortwave) {
		memcpy(layer->shortwave, shortwave, total_hours);
	} else {
		memset(layer->shortwave, 0, sizeof(layer->shortwave));
	}
	layer->current_hour = current_hour;
	layer_mark_dirty(layer->layer);
}
