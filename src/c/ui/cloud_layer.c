/**
 * Cloud layer
 *
 * @author    Cory Hughart <cory@coryhughart.com>
 * @copyright 2026 Cory Hughart
 * @license   https://www.gnu.org/licenses/gpl-3.0.html GPL-3.0-or-later
 * @link      https://cr0ybot.com/project/pebble-watchface-carbon
 */

#include "cloud_layer.h"
#include "../modules/settings.h"
#include "graph_common.h"
#include <stddef.h>
#include <stdlib.h>

#define CLEAR_THRESHOLD 15

struct CloudLayer {
	Layer *layer;
	uint8_t cover[MAX_GRAPH_HOURS];
	uint8_t hourly_code[MAX_GRAPH_HOURS];
	uint8_t current_hour;
};

static void prv_draw_cloud(GContext *ctx, int cx, int cy, int r) {
	graphics_fill_circle(ctx, GPoint(cx, cy), r);
	graphics_fill_circle(ctx, GPoint(cx - r, cy + r / 2), r * 2 / 3);
	graphics_fill_circle(ctx, GPoint(cx + r, cy + r / 2), r * 2 / 3);
}

static void prv_update_proc(Layer *layer, GContext *ctx) {
	CloudLayer *cl = *(CloudLayer **)layer_get_data(layer);
	GRect bounds = layer_get_bounds(layer);
	int graph_x = GRAPH_OFFSET_X;
	int graph_w = bounds.size.w - graph_x;
	int cy = bounds.size.h / 2;
	int total_hours = GRAPH_HOURS;
	if (total_hours > MAX_GRAPH_HOURS) total_hours = MAX_GRAPH_HOURS;

	graphics_context_set_antialiased(ctx, false);

	for (int i = total_hours - 1; i >= 0; i--) {
		if (cl->cover[i] < CLEAR_THRESHOLD)
			continue;

#if defined(PBL_COLOR)
		bool is_light = settings_get()->light_theme;
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
		if (cl->cover[i] < 40) {
			r = 2;
		} else if (cl->cover[i] < 70) {
			r = 3;
		} else {
			r = 4;
		}
		prv_draw_cloud(ctx, cx, cy, r);
	}
}

CloudLayer *cloud_layer_create(GRect frame) {
	CloudLayer *cl = malloc(sizeof(CloudLayer));
	if (!cl)
		return NULL;
	memset(cl->cover, 0, sizeof(cl->cover));
	memset(cl->hourly_code, 0, sizeof(cl->hourly_code));
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
                          const uint8_t *hourly_code, uint8_t current_hour) {
	if (!layer)
		return;
	int total_hours = GRAPH_HOURS;
	if (total_hours > MAX_GRAPH_HOURS) total_hours = MAX_GRAPH_HOURS;
	memcpy(layer->cover, cover, total_hours);
	memcpy(layer->hourly_code, hourly_code, total_hours);
	layer->current_hour = current_hour;
	layer_mark_dirty(layer->layer);
}
